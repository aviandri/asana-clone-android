package com.mekari.project

import android.app.Application
import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import com.mekari.project.core.AttachmentCache
import com.mekari.project.core.DeepLink
import com.mekari.project.core.LiveApi
import com.mekari.project.core.ServerSettings
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.feature.AppSession
import com.mekari.project.feature.Auth
import com.mekari.project.feature.DeepLinkResult
import com.mekari.project.feature.FeaturePrefs
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.NavigationTarget
import com.mekari.project.feature.SignInError
import com.mekari.project.ui.attachments.Thumbnails
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mekari_project")

class MekariApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        InboxNotifications.createChannel(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as MekariApplication).container

/** App-only settings (SPEC-ANDROID §5): notifications and the completion sound. */
data class AppPrefs(val notificationsEnabled: Boolean = true, val completionSound: Boolean = false)

/**
 * The manual DI root (no framework, SPEC-ANDROID §2). Storage is read ONCE at launch, then served from
 * memory, and every write goes through to DataStore asynchronously (core's stores are synchronous).
 */
class AppContainer(private val context: Context) {
    /** Where every holder action runs: Main.immediate (holders are main-confined) and never cancelled by a
     *  screen going away, so a PATCH is never abandoned halfway. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * One blocking read at cold start, on purpose: :core's TokenStore and ServerSettings are synchronous
     * (OkHttp interceptors can't suspend), and AppSession.start() needs both before the first frame can
     * decide between sign-in and My Tasks. It's one small preferences file plus one Keystore unwrap.
     */
    private val stored: Preferences by lazy { runBlocking { context.dataStore.data.first() } }

    private val _prefs by lazy {
        MutableStateFlow(
            AppPrefs(
                notificationsEnabled = stored[Keys.NOTIFICATIONS] ?: true,
                completionSound = stored[Keys.SOUND] ?: false
            )
        )
    }
    val prefs: StateFlow<AppPrefs> get() = _prefs.asStateFlow()

    val featurePrefs: FeaturePrefs by lazy {
        object : FeaturePrefs {
            override var celebrationsEnabled: Boolean = stored[Keys.CELEBRATIONS] ?: true
                set(value) {
                    field = value
                    write { it[Keys.CELEBRATIONS] = value }
                }
        }
    }

    fun setNotifications(on: Boolean) {
        _prefs.update { it.copy(notificationsEnabled = on) }
        write { it[Keys.NOTIFICATIONS] = on }
        if (liveSession) updateBackgroundCheck(signedIn = currentSession?.state?.value?.me != null)
    }

    /**
     * Whether to ask for the notification permission (once per install, after the first sign-in). Never on the
     * fake API: UI tests and QA demos would get a system dialog over the app (the Mac disables its notifier
     * in UI tests too), and fake sessions never schedule background checks anyway.
     */
    fun shouldAskNotificationPermission(): Boolean = liveSession && !askedNotifications

    fun markNotificationPermissionAsked() {
        askedNotifications = true
        write { it[Keys.ASKED_NOTIFICATIONS] = true }
    }

    private var askedNotifications: Boolean = false
        get() = field || (stored[Keys.ASKED_NOTIFICATIONS] ?: false)

    fun setCompletionSound(on: Boolean) {
        _prefs.update { it.copy(completionSound = on) }
        write { it[Keys.SOUND] = on }
    }

    /** A mekariproject:// link (or a notification tap), resolved in the app scope (see [followLinks]). */
    val pendingLink = MutableStateFlow<DeepLink?>(null)

    /**
     * Where a resolved link goes. Resolving may switch workspace, which replaces the shell, so the result
     * can't be awaited inside it: whichever shell is current (the NEW one after a switch) consumes it once.
     */
    val pendingTarget = MutableStateFlow<NavigationTarget?>(null)

    /** "You don't have access…" for a link that couldn't be opened, shown by the root. */
    val linkNotice = MutableStateFlow<String?>(null)

    /** The launcher's quick-add shortcut, waiting for the shell. */
    val pendingQuickAdd = MutableStateFlow(false)

    private var currentSession: AppSession? = null

    /** Attachment downloads (SPEC-ANDROID §8): cacheDir/attachments, the FileProvider root, 500 MB LRU. */
    val attachmentCacheDir: File get() = File(context.cacheDir, "attachments")

    /** Coil for attachment thumbnails (memory + cacheDir/attachment-thumbs), cleared with every attachment wipe. */
    val thumbnailLoader: ImageLoader by lazy { Thumbnails.imageLoader(context) }

    /** After every wipe of the attachment cache (sign-out, lost workspace, start while signed out). */
    private val attachmentsWiped: () -> Unit = { Thumbnails.clear(thumbnailLoader) }

    /**
     * The session's own work (its coroutines and the follow* collectors). Production keeps one session for the
     * process; instrumented tests replace it per test ([resetSessionForTests]), which cancels this.
     */
    private var sessionWork = SupervisorJob(appScope.coroutineContext[Job])
    private val sessionScope: CoroutineScope get() = CoroutineScope(appScope.coroutineContext + sessionWork)
    private var foregroundObserver: LifecycleEventObserver? = null

    /**
     * Instrumented tests share one process (and so this container): each test gets a fresh fake session instead
     * of the previous test's state. Main thread only.
     */
    @VisibleForTesting
    internal fun resetSessionForTests() {
        foregroundObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        foregroundObserver = null
        sessionWork.cancel()
        sessionWork = SupervisorJob(appScope.coroutineContext[Job])
        currentSession = null
        pendingLink.value = null
        pendingTarget.value = null
        linkNotice.value = null
        pendingQuickAdd.value = false
    }

    /** The one session. With the fake-API launch flag it runs on FakeApi + demo data (tests, device QA). */
    fun session(fakeApi: Boolean, signedIn: Boolean = true, fake: FakeApi? = null): AppSession {
        currentSession?.let { return it }
        val work = sessionScope
        val session = if (fakeApi) {
            val api = fake ?: FakeApi(signedInAs = if (signedIn) "alice@example.com" else null)
            AppSession.uiTest(signedIn, work, api, AttachmentCache(attachmentCacheDir), attachmentsWiped)
        } else {
            val tokens = tokenStore.forSource("app") // first touch restores (and may keep a blob)
            retryKeptSession()
            AppSession(
                serverSettings,
                tokens,
                { url, store -> LiveApi(url, store) },
                featurePrefs,
                work,
                attachmentCache = AttachmentCache(attachmentCacheDir),
                onAttachmentsWiped = attachmentsWiped
            )
        }
        currentSession = session
        liveSession = !fakeApi
        work.launch { session.start() }
        followForeground(session)
        followLinks(session)
        followInboxBaseline(session)
        if (!fakeApi) followKeptSession(session)
        // Background inbox checks: live sessions only (the fake API never schedules WorkManager). Decided from
        // auth: Restoring (every cold start) changes nothing, so the periodic job isn't reset or dropped.
        if (!fakeApi) {
            work.launch {
                session.state.map { it.auth }
                    .filter { it !is Auth.Restoring }
                    .map { it is Auth.SignedIn }
                    .distinctUntilChanged()
                    .collect { signedIn -> updateBackgroundCheck(signedIn) }
            }
        }
        return session
    }

    /** Links resolve here, outside any shell: once signed in, resolve (switching workspace if needed), then hand off. */
    private fun followLinks(session: AppSession) {
        sessionScope.launch {
            pendingLink.filterNotNull().collect { link ->
                pendingLink.value = null
                session.state.first { it.auth is Auth.SignedIn }
                when (val result = session.resolve(link)) {
                    is DeepLinkResult.Open -> pendingTarget.value = result.target
                    else -> linkNotice.value = "You don't have access to this, or it no longer exists."
                }
            }
        }
    }

    /**
     * The background check's baseline follows what the app itself showed: each foreground inbox load becomes
     * the "seen" set for that user + workspace, so items already seen in the Inbox are never notified later.
     */
    private fun followInboxBaseline(session: AppSession) {
        sessionScope.launch {
            session.scope.collectLatest { scope ->
                scope ?: return@collectLatest
                scope.inbox.state
                    .filter { it.phase == LoadState.Loaded }
                    .map { state -> state.notifications.filter { !it.read }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .collect { unread ->
                        val key = seenKey(scope.me.id, scope.id)
                        write { it[key] = unread }
                    }
            }
        }
    }

    /**
     * The Sign in screen with a token still stored: the launch's `/auth/me` failed without a 401 (offline, a
     * frozen app's network not back yet), so the token is kept for the next launch. Logged (QA A5) because
     * on screen it looks just like a lost session.
     */
    private fun followKeptSession(session: AppSession) {
        sessionScope.launch {
            session.state.map { (it.auth is Auth.SignedOut) to it.signInError }
                .distinctUntilChanged()
                .collect { (signedOut, error) ->
                    if (signedOut && tokenStore.load() != null) {
                        val why = if (error == SignInError.Transport) "transport" else "error"
                        SessionLog.session("sign-in shown with a stored session: launch check failed ($why); kept")
                    }
                }
        }
    }

    private var liveSession = false

    private fun updateBackgroundCheck(signedIn: Boolean) {
        if (signedIn && _prefs.value.notificationsEnabled) {
            InboxNotifications.schedule(context)
        } else {
            InboxNotifications.cancel(context)
        }
    }

    /**
     * The background check (InboxCheckWorker): the stored session against the server, its current workspace's
     * unread items, diffed against the ids seen last time. Its 401s are logged as the worker's.
     */
    internal suspend fun checkInbox(): InboxCheck {
        val tokens = tokenStore.pinned("worker") // one session for the whole check
        return runInboxCheck(
            api = serverSettings.serverUrl?.let { LiveApi(it, tokens) },
            hasToken = tokens.load() != null,
            // Per user + workspace: a switch or a different account starts its own baseline (no flood).
            seen = { user, workspace -> context.dataStore.data.first()[seenKey(user, workspace)] },
            // Synchronously, before the worker returns: the process may die right after.
            setSeen = { user, workspace, ids -> context.dataStore.edit { it[seenKey(user, workspace)] = ids } }
        )
    }

    /**
     * The inbox poller runs only while the app is in the foreground (ProcessLifecycleOwner: rotation isn't a
     * stop), and coming back refreshes My Tasks, projects and the inbox. A new workspace scope starts its
     * own poller if we're in the foreground; its first load is the UI's.
     */
    private fun followForeground(session: AppSession) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            val scope = session.scope.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> {
                    scope.poller.start()
                    appScope.launch { scope.refresh() }
                }
                Lifecycle.Event.ON_STOP -> scope.poller.stop()
                else -> Unit
            }
        }
        foregroundObserver = observer
        lifecycle.addObserver(observer)
        sessionScope.launch {
            session.scope.collect { scope ->
                // The first load is the UI's (MainShell); here only the foreground poller.
                if (scope != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scope.poller.start()
            }
        }
    }

    private val serverSettings: ServerSettings by lazy {
        object : ServerSettings {
            override var serverUrl: String? = stored[Keys.SERVER]
                set(value) {
                    field = value
                    // Synchronous: sign-in stores the URL before the token (ServerSettings). If it can't be
                    // written, the token stored next could end up beside another server's URL: drop it next launch.
                    val written = writeDurably { prefs ->
                        if (value == null) prefs.remove(Keys.SERVER) else prefs[Keys.SERVER] = value
                    }
                    urlUnwritten = !written
                    if (!written) markMustClear("server URL")
                }
        }
    }

    /** A stored token the Keystore couldn't open at launch (Unavailable): retried once by [retryKeptSession]. */
    private var keptBlob: String? = null

    private val tokenStore: PersistentTokenStore by lazy {
        val sealed = stored[Keys.TOKEN]
        // Off main (a worker's cold start) a Keystore hiccup is retried with short pauses; on main it isn't,
        // since session() retries a kept blob once anyway (retryKeptSession).
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val restored =
            restoreSession(sealed, SessionCipher::open, mustClear = mustClear.exists(), attempts = if (onMain) 1 else 3)
        if (restored.token != null) {
            SessionLog.session("session restored at launch")
        } else {
            SessionLog.session("no session at launch: ${restored.reason}")
        }
        if (restored.dropBlob) writeToken(null)
        if (restored.token == null && !restored.dropBlob) keptBlob = sealed
        PersistentTokenStore(restored.token, SessionLog::session) { token ->
            // A Keystore failure keeps the token for this run only (in memory), rather than failing sign-in.
            writeToken(token?.let { SessionCipher.encrypt(it) })
        }
    }

    /**
     * A blob kept after a Keystore hiccup gets one more try when the UI starts the session, so a WorkManager
     * cold start that hit the hiccup doesn't leave the foreground signed out for the whole process (#11 review 3).
     */
    private fun retryKeptSession() {
        val sealed = keptBlob ?: return
        keptBlob = null
        if (tokenStore.load() != null) return
        val retried = restoreSession(sealed, SessionCipher::open, attempts = 1)
        val token = retried.token
        when {
            token != null && tokenStore.adopt(token) -> SessionLog.session("session restored on retry")
            token != null -> Unit // signed in meanwhile
            else -> SessionLog.session("no session on retry: ${retried.reason}")
        }
        if (retried.dropBlob) tokenStore.dropStoredIfSignedOut()
    }

    /**
     * Set when a token write failed even after a retry: storage may still hold a token this process replaced
     * or cleared (a sign-out!), so the next launch must not restore it. A plain file, not DataStore, since
     * DataStore is what just failed. Removed by the next successful token write (#11 review 4).
     */
    private val mustClear: File get() = File(context.noBackupFilesDir, "session-must-clear")

    /**
     * The token's own write, synchronous (not through the queue): sign-in completes only once the token is on
     * disk, so a process killed right after can't come back signed out (QA A5). Runs under the token store's
     * lock, so token writes stay in call order. Safe on main because the queue's consumer never needs main
     * (see PreferenceWrites).
     */
    private fun writeToken(sealed: String?) {
        val written = writeDurably { prefs ->
            if (sealed == null) prefs.remove(Keys.TOKEN) else prefs[Keys.TOKEN] = sealed
        }
        if (!written) {
            markMustClear("session token")
        } else if (!urlUnwritten && mustClear.exists() && !mustClear.delete()) {
            Log.w(SessionLog.TAG, "Couldn't remove the session-clear marker; the next launch will sign out")
        }
    }

    /** The last server URL write failed: storage may pair the next token with another server (see [mustClear]). */
    @Volatile private var urlUnwritten = false

    /** [PreferenceWrites.writeNow], retried once. False if it couldn't be written. */
    private fun writeDurably(change: (MutablePreferences) -> Unit): Boolean {
        repeat(2) {
            try {
                writes.writeNow(change)
                return true
            } catch (e: IOException) {
                Log.w(SessionLog.TAG, "Couldn't write to storage: ${e.javaClass.simpleName}")
            }
        }
        return false
    }

    private fun markMustClear(what: String) {
        try {
            mustClear.createNewFile()
            SessionLog.session("$what write failed twice: the stored session is dropped at the next launch")
        } catch (e: IOException) {
            Log.w(SessionLog.TAG, "Couldn't mark the session for clearing: ${e.javaClass.simpleName}")
        }
    }

    /** Every DataStore change, in call order, from any thread; consumed off main (see PreferenceWrites). */
    private val writes = PreferenceWrites(context.dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO)) { e ->
        Log.w(TAG, "Couldn't save a setting: ${e.javaClass.simpleName}")
    }

    private fun write(change: (MutablePreferences) -> Unit) = writes.enqueue(change)

    private fun seenKey(userId: String, workspaceId: String) =
        stringSetPreferencesKey("seenUnread.$userId.$workspaceId")

    private companion object {
        const val TAG = "AppContainer"
    }

    private object Keys {
        val SERVER = stringPreferencesKey("serverUrl")
        val TOKEN = stringPreferencesKey("session")
        val CELEBRATIONS = booleanPreferencesKey("celebrationsEnabled")
        val NOTIFICATIONS = booleanPreferencesKey("notificationsEnabled")
        val SOUND = booleanPreferencesKey("completionSound")
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("askedNotificationPermission")
    }
}

/**
 * The session token, sealed with an AES-GCM key that never leaves the Android Keystore
 * (SPEC-ANDROID §3). A token that can't be decrypted (key lost, e.g. after a restore) reads as signed out;
 * see [restoreSession] for which failures drop it.
 */
internal object SessionCipher {
    private const val ALIAS = "mekari.session"
    private const val TAG = "SessionCipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16

    /**
     * Null if the Keystore fails (logged by type only; never the token). A key that can't seal is replaced
     * once: this is the one safe moment, since the new token is about to replace the old blob anyway.
     */
    fun encrypt(plain: String): String? = try {
        seal(plain)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't seal the session token: ${e.javaClass.simpleName}; retrying with a new key")
        try {
            deleteKey()
            seal(plain)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't seal the session token: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /**
     * Never creates or deletes the key. Lost: no key, or a blob that fails authentication (AEADBadTag).
     * Unavailable: any other Keystore failure, which may pass, so the caller keeps the blob.
     */
    fun open(stored: String): Opened {
        val bytes = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return Opened.Lost("corrupt")
        }
        if (bytes.size < IV_BYTES + TAG_BYTES) return Opened.Lost("corrupt")
        return try {
            val key = existingKey() ?: return Opened.Lost("key-missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
            Opened.Token(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            Opened.Lost("corrupt")
        } catch (e: Exception) {
            Opened.Unavailable(e.javaClass.simpleName)
        }
    }

    @VisibleForTesting
    internal fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
