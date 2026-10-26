package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.ApiEvent
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentCache
import com.mekari.project.core.DeepLink
import com.mekari.project.core.InMemoryServerSettings
import com.mekari.project.core.InMemoryTokenStore
import com.mekari.project.core.Me
import com.mekari.project.core.MekariApi
import com.mekari.project.core.ProjectRole
import com.mekari.project.core.ServerSettings
import com.mekari.project.core.ServerUrl
import com.mekari.project.core.TaskItem
import com.mekari.project.core.TokenStore
import com.mekari.project.core.UserSummary
import com.mekari.project.core.WebLinks
import com.mekari.project.core.Workspace
import com.mekari.project.core.WorkspaceRole
import com.mekari.project.core.fake.FakeApi
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything for the current workspace. A new instance on every switch, so all state resets together. */
class WorkspaceScope internal constructor(
    sessionApi: MekariApi,
    val workspace: Workspace,
    val me: UserSummary,
    parent: CoroutineScope,
    clock: Clock,
    prefs: FeaturePrefs,
    cooldown: CelebrationCooldown,
    private val uploads: AttachmentUploads,
    private val slots: UploadSlots,
    cache: AttachmentCache
) {
    /** Every request from this scope; refused once the scope is closed (see [ScopedApi]). */
    private val api = ScopedApi(sessionApi)

    /** True once this workspace was left (switch, lost access, sign-out). */
    val isClosed: Boolean get() = api.closed

    /** Poller, linger and cooldown timers, debouncers: cancelled with the scope. */
    internal val coroutineScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    val id: String get() = workspace.id
    val tasks = TaskCache(api, coroutineScope)
    val projects = ProjectListStore(api)
    val myTasks = MyTasksStore(api, tasks, me, clock)
    val inbox = InboxStore(api)
    val poller = NotificationPoller(inbox, coroutineScope)
    val search = SearchStore(api, coroutineScope)
    val quickAdd = QuickAddModel(api, me, myTasks)
    val completionFeedback = CompletionFeedback(prefs, cooldown) { api.closed }

    /** Downloads for this workspace (through the session's cache). */
    val attachmentFiles = AttachmentFiles(api, cache, coroutineScope)

    private val projectStores = mutableMapOf<String, ProjectStore>()
    private val detailStores = mutableMapOf<String, TaskDetailStore>()

    init {
        tasks.feedback = completionFeedback
        completionFeedback.tasks = tasks
        completionFeedback.myTasks = myTasks
        completionFeedback.meId = me.id
        completionFeedback.projectStore = { projectStores[it] }
        projects.changes.add { detailStores.values.forEach { it.projectsChanged() } }
    }

    /** One store per project, kept for the life of the scope. */
    fun project(id: String): ProjectStore =
        projectStores.getOrPut(id) { ProjectStore(api, tasks, id, workspace.myRole) }

    /** One store per task; its role is seeded from [projects]. */
    fun taskDetail(id: String): TaskDetailStore = detailStores.getOrPut(id) {
        TaskDetailStore(
            api,
            tasks,
            id,
            me,
            workspace.myRole,
            { projectId -> knownProject(projectId) },
            coroutineScope,
            uploads,
            slots,
            workspace.id
        )
    }

    fun userSearch(): UserSearch = UserSearch(api, coroutineScope)

    /**
     * Whether I may edit [task] without opening it (My Tasks checkboxes): personal tasks and workspace
     * admins yes; a project task only if its project is known and I'm not a COMMENTER there. Fails closed.
     * Reads `projects.state`: collect it, so rows recompose when roles arrive.
     */
    fun canEdit(task: TaskItem): Boolean {
        val projectId = task.projectId ?: return true
        if (workspace.myRole == WorkspaceRole.ADMIN) return true
        val project = knownProject(projectId) ?: return false
        return project.myRole != ProjectRole.COMMENTER
    }

    /** Coming to the foreground: My Tasks, projects and the inbox, in parallel. */
    suspend fun refresh() {
        kotlinx.coroutines.coroutineScope {
            launch { myTasks.refresh() }
            launch { projects.load() }
            launch { inbox.load() }
        }
    }

    /** Nothing from this scope reaches the server or the app-wide cooldown afterwards. */
    internal fun close() {
        api.close()
        poller.stop()
        detailStores.values.forEach { it.close() }
        coroutineScope.cancel()
    }

    /** A task-level upload finished (possibly after leaving its task): show it if the task is loaded here. */
    internal fun attachmentUploaded(attachment: Attachment) {
        if (attachment.workspaceId != id || isClosed) return
        val store = detailStores[attachment.taskId]
        if (store != null) {
            store.attachmentAdded(attachment)
        } else {
            tasks.editAttachments(attachment.taskId, bumpedAt = attachment.createdAt) { list ->
                list.filterNot { it.id == attachment.id } + attachment
            }
        }
    }

    private fun knownProject(projectId: String) = projects.state.value.projects.firstOrNull { it.id == projectId }
}

sealed interface Auth {
    data object Restoring : Auth
    data object SignedOut : Auth
    data class SignedIn(val me: Me) : Auth
}

sealed interface SignInError {
    val message: String

    data object WrongCredentials : SignInError {
        override val message = "Wrong email or password"
    }

    data object RateLimited : SignInError {
        override val message = "Too many attempts. Try again in a minute."
    }

    data object BadServer : SignInError {
        override val message = "That doesn't look like a Mekari Project server"
    }

    data object Transport : SignInError {
        override val message = "Can't reach the server"
    }

    data class Other(override val message: String) : SignInError
}

data class SessionState(
    val auth: Auth = Auth.Restoring,
    val isSigningIn: Boolean = false,
    val signInError: SignInError? = null,
    /** A short message for a snackbar, e.g. "Switched to Acme Pilot". */
    val notice: String? = null,
    /** A switch or lost-access recovery is queued or running: show progress, hold edits. */
    val isSwitching: Boolean = false,
    /** Bumps whenever the scope is replaced (switch, lost access, sign-in, sign-out). */
    val generation: Int = 0,
    /** The workspace the scope belongs to. */
    val current: Workspace? = null
) {
    val me: Me? get() = (auth as? Auth.SignedIn)?.me

    /** Me.workspaces order (by name). */
    val all: List<Workspace> get() = me?.workspaces.orEmpty()
    val isGuest: Boolean get() = current?.isGuest ?: false
    val isAdmin: Boolean get() = current?.isAdmin ?: false
}

/**
 * The app's root: sign-in state, the current workspace and its holders (SPEC-ANDROID §3–§4).
 *
 * Workspace switches and lost-access recovery run one at a time, in order. A switch superseded before
 * it starts is skipped before its POST, so the app and the server's lastWorkspaceId both end on the
 * newest choice; recovery supersedes nothing (it never cancels a user's switch). The header and the
 * scope always change together. Jobs run in [appScope], so a screen going away can't abort one halfway.
 */
class AppSession(
    val settings: ServerSettings,
    private val tokens: TokenStore,
    private val apiFactory: (serverUrl: String, tokens: TokenStore) -> MekariApi,
    private val prefs: FeaturePrefs,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Downloads on disk. The app passes `cacheDir/attachments` (the FileProvider path); the default is a temp folder. */
    val attachmentCache: AttachmentCache = AttachmentCache.scratch(),
    /** After every wipe of [attachmentCache] (sign-out, lost access): the app clears Coil's memory and disk caches too. */
    private val onAttachmentsWiped: () -> Unit = {}
) {
    private val _state = MutableStateFlow(SessionState())
    private val _scope = MutableStateFlow<WorkspaceScope?>(null)

    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** A NEW object per workspace (switch, recovery, sign-in): key per-workspace UI on it; null = signed out / no workspace. */
    val scope: StateFlow<WorkspaceScope?> = _scope.asStateFlow()

    private var api: MekariApi? = null
    private var eventLoop: Job? = null
    private val queue = Mutex()
    private var switchRequest = 0

    /** The celebration rate limit, kept across workspace switches. */
    private val celebrationCooldown = CelebrationCooldown(appScope)

    /** At most 3 uploads, links and refreshes at once across the app (A12). */
    internal val uploadSlots = UploadSlots()

    /** Task-level uploads, kept across leaving the task and workspace switches (keyed by task). */
    val uploads = AttachmentUploads(uploadSlots, appScope) { api }

    /** The last wipe a sign-out started (sign-out awaits it; tests too). */
    internal var pendingWipe: Job? = null
        private set

    init {
        uploads.onFinished = { attachment -> _scope.value?.attachmentUploaded(attachment) }
    }

    /** Jobs queued and not finished, and jobs skipped as superseded (tests pin the queue with these). */
    internal var queuedJobs = 0
        private set(value) {
            field = value
            if (_state.value.isSwitching != (value > 0)) _state.update { it.copy(isSwitching = value > 0) }
        }
    internal var skippedJobs = 0
        private set

    /** The workspace the server will open next (its lastWorkspaceId), as far as we know. */
    private var serverWorkspaceId: String? = null

    /** The live client (tests only). */
    internal val currentApi: MekariApi? get() = api

    /** Restores a saved session (`/auth/me`) or ends signed out. */
    suspend fun start() {
        val server = settings.serverUrl ?: return signedOut()
        val api = makeApi(server)
        if (!api.hasSession) return signedOut()
        try {
            val me = api.me()
            serverWorkspaceId = me.currentWorkspace?.id
            adopt(me, api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline at launch keeps the token for next time.
            val transport = e.asApiError().code == ApiError.Code.TRANSPORT
            wipeAttachments()
            _state.update {
                it.copy(auth = Auth.SignedOut, signInError = if (transport) SignInError.Transport else null)
            }
        }
    }

    /** Validates the server with `/health`, then signs in. */
    suspend fun signIn(server: String, email: String, password: String) {
        if (_state.value.isSigningIn) return
        _state.update { it.copy(signInError = null) }
        val url = ServerUrl.normalize(server) ?: return _state.update { it.copy(signInError = SignInError.BadServer) }
        _state.update { it.copy(isSigningIn = true) }
        val previousUrl = settings.serverUrl
        try {
            val api = makeApi(url)
            try {
                api.health()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val transport = e.asApiError().code == ApiError.Code.TRANSPORT
                _state.update { it.copy(signInError = if (transport) SignInError.Transport else SignInError.BadServer) }
                return
            }
            try {
                // The URL is stored BEFORE the token: whatever token storage ends up holding, it's for the stored
                // server, even if the process dies right after the login (a token next to the old server's URL
                // would go to the old host on the next launch). A failed sign-in puts the old URL back.
                // Any session for the old server goes (stored-only ones too: a blob the Keystore couldn't open yet),
                // but only now, once the new server answered: a mistyped address must not cost a valid session.
                // Cleared before the URL is stored, so storage never pairs the old token with the new URL. (No
                // request carries it meanwhile: /health and /auth/login never send a cookie.)
                if (url != previousUrl) tokens.clear()
                settings.serverUrl = url
                val me = api.login(email.trim(), password)
                serverWorkspaceId = me.currentWorkspace?.id
                adopt(me, api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only while no session was stored for the new URL (a failure after login keeps the pair).
                if (tokens.load() == null) settings.serverUrl = previousUrl
                val error = e.asApiError()
                val signInError = when (error.code) {
                    ApiError.Code.UNAUTHORIZED -> SignInError.WrongCredentials
                    ApiError.Code.RATE_LIMITED -> SignInError.RateLimited
                    ApiError.Code.TRANSPORT -> SignInError.Transport
                    else -> SignInError.Other(error.message)
                }
                _state.update { it.copy(signInError = signInError) }
            }
        } finally {
            _state.update { it.copy(isSigningIn = false) }
        }
    }

    suspend fun signOut() {
        val api = api
        endSession()
        pendingWipe?.join()
        api?.logout()
    }

    /** Switches the app's workspace (serialized; the newest wins; header and scope change together). */
    suspend fun switchWorkspace(id: String) {
        serialized(supersedes = true) { request -> performSwitch(id, request) }
    }

    /** Opens a deep link, switching workspace first when the resource lives in another one I belong to. */
    suspend fun resolve(link: DeepLink): DeepLinkResult {
        val api = api ?: return DeepLinkResult.NoAccess
        // DeepLink.parse only yields UUIDs, but the cases can be built directly: never let anything else
        // reach a request path.
        val id = when (link) {
            is DeepLink.Task -> link.id
            is DeepLink.Project -> link.id
        }
        if (!UUID.matches(id)) return DeepLinkResult.NoAccess
        return try {
            val (workspaceId, target) = when (link) {
                is DeepLink.Task -> api.task(id).task.let { it.workspaceId to NavigationTarget.Task(id, it.projectId) }
                is DeepLink.Project -> api.project(id).project.workspaceId to NavigationTarget.Project(id)
            }
            if (workspaceId != _state.value.current?.id) {
                if (_state.value.all.none { it.id == workspaceId }) return DeepLinkResult.NoAccess
                switchWorkspace(workspaceId)
                if (_state.value.current?.id != workspaceId) return DeepLinkResult.NoAccess
            }
            DeepLinkResult.Open(target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DeepLinkResult.NoAccess
        }
    }

    /** server + path + ?workspace=<current>, for Open in browser. */
    fun webUrl(path: String): String? = settings.serverUrl?.let { WebLinks.url(it, path, _state.value.current?.id) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // Internals

    /** Signed out at start: nothing from a previous session stays on disk. */
    private suspend fun signedOut() {
        wipeAttachments()
        _state.update { it.copy(auth = Auth.SignedOut) }
    }

    private suspend fun wipeAttachments() {
        attachmentCache.wipe()
        onAttachmentsWiped()
    }

    /**
     * Queues a workspace job after the ones already queued (the Mutex is fair: FIFO). A job is skipped
     * before it starts, or not committed, if a newer superseding job was queued after it. The job runs in
     * [appScope] (entered at once, to take its place in the queue) and the caller awaits it.
     */
    private suspend fun serialized(supersedes: Boolean, job: suspend (request: Int) -> Unit) {
        if (supersedes) switchRequest += 1
        val request = switchRequest
        queuedJobs += 1
        appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                queue.withLock {
                    if (request != switchRequest) {
                        skippedJobs += 1
                        return@withLock
                    }
                    job(request)
                }
            } finally {
                queuedJobs -= 1
            }
        }.await()
    }

    private suspend fun performSwitch(id: String, request: Int) {
        val api = api ?: return
        val me = _state.value.me ?: return
        // Nothing to do only if both the app and the server are already there.
        if (id == _state.value.current?.id && id == serverWorkspaceId) return
        try {
            val workspace = api.switchWorkspace(id)
            serverWorkspaceId = id
            if (request != switchRequest) return
            val updated = me.copy(
                user = me.user.copy(role = workspace.myRole),
                currentWorkspace = workspace,
                workspaces = me.workspaces.map { if (it.id == id) workspace else it }
            )
            adopt(updated, api)
            _state.update { it.copy(notice = "Switched to ${workspace.name}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (request == switchRequest) _state.update { it.copy(notice = e.asApiError().message) }
        }
    }

    /**
     * Lost access: adopt the workspace `/auth/me` gives us (it never carries the header). The header is
     * never cleared here: until adopt() swaps header and scope together, both still name the lost
     * workspace, so requests 404 instead of silently showing another workspace's data under this one's
     * label. On failure the notice stays and the next scoped request (foreground) 404s again and retries.
     */
    private suspend fun recoverLostAccess(workspaceId: String, request: Int) {
        val api = api ?: return
        val current = _state.value.current
        if (workspaceId != current?.id) return
        val lostName = current.name
        // Nothing from the lost workspace stays on disk or keeps uploading, whatever /auth/me says next.
        uploads.cancelWorkspace(workspaceId)
        wipeAttachments()
        try {
            val me = api.me()
            if (request != switchRequest) return
            adopt(me, api)
            _state.update { it.copy(notice = "You no longer have access to $lostName") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 401s arrive as their own event and sign out.
            if (request != switchRequest || _state.value.current?.id != workspaceId) return
            _state.update {
                it.copy(
                    notice = "You no longer have access to $lostName. Couldn't load your other workspaces; " +
                        "retrying when you come back."
                )
            }
        }
    }

    private fun makeApi(server: String): MekariApi {
        val api = apiFactory(server, tokens)
        eventLoop?.cancel()
        eventLoop = appScope.launch { api.events.collect(::handle) }
        this.api = api
        return api
    }

    /** Signed in as [me]: header, scope and generation change together. */
    private suspend fun adopt(me: Me, api: MekariApi) {
        // Close first: the old scope's requests stop before the header names the next workspace.
        _scope.value?.close()
        api.setWorkspace(me.currentWorkspace?.id)
        val scope = me.currentWorkspace?.let {
            WorkspaceScope(
                api,
                it,
                me.user.summary,
                appScope,
                clock,
                prefs,
                celebrationCooldown,
                uploads,
                uploadSlots,
                attachmentCache
            )
        }
        _scope.value = scope
        _state.update {
            it.copy(
                auth = Auth.SignedIn(me),
                current = me.currentWorkspace,
                generation = it.generation + 1,
                signInError = null
            )
        }
        scope?.poller?.start()
    }

    private fun handle(event: ApiEvent) {
        when (event) {
            ApiEvent.Unauthorized -> if (_state.value.auth != Auth.SignedOut) endSession()
            is ApiEvent.WorkspaceLost -> {
                if (event.workspaceId != _state.value.current?.id) return
                appScope.launch {
                    serialized(supersedes = false) { request -> recoverLostAccess(event.workspaceId, request) }
                }
            }
        }
    }

    private fun endSession() {
        switchRequest += 1 // queued switches are void now
        serverWorkspaceId = null
        _scope.value?.close()
        _scope.value = null
        _state.update { it.copy(auth = Auth.SignedOut, current = null, generation = it.generation + 1) }
        uploads.cancelAll()
        pendingWipe = appScope.launch { wipeAttachments() }
    }

    companion object {
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        const val DEMO_SERVER = "https://demo.invalid"

        /** Backed by FakeApi with the demo seed (previews). Starts itself. */
        fun preview(
            signedIn: Boolean = true,
            asEmail: String = "alice@example.com",
            appScope: CoroutineScope
        ): AppSession {
            val fake = FakeApi(signedInAs = if (signedIn) asEmail else null)
            val session =
                AppSession(InMemoryServerSettings(DEMO_SERVER), InMemoryTokenStore(), { _, _ ->
                    fake
                }, InMemoryFeaturePrefs(), appScope)
            appScope.launch { session.start() }
            return session
        }

        /**
         * The fake-API launch flag: FakeApi with in-memory stores. With signedIn = false, start() ends in
         * SignedOut and sign-in works with any server URL and password123. Pass your own [fake] to drive
         * failNext/hold/release from tests. The caller runs start().
         */
        fun uiTest(
            signedIn: Boolean,
            appScope: CoroutineScope,
            fake: FakeApi = FakeApi(signedInAs = if (signedIn) "alice@example.com" else null)
        ): AppSession = AppSession(InMemoryServerSettings(DEMO_SERVER), InMemoryTokenStore(), { _, _ ->
            fake
        }, InMemoryFeaturePrefs(), appScope)
    }
}
