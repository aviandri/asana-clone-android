# Mekari Project for Android: architecture (:core & :feature)

This is the public API of the two pure-Kotlin modules; the Compose app (`:app`) builds against it.
It implements [`SPEC-ANDROID.md`](SPEC-ANDROID.md). The API contract is the backend's `docs/SPEC.md`
(§5 shapes, §6 endpoints, §12 workspaces, W1–W8). Behaviour follows the Mac app
(`asana-clone-mac/docs/ARCHITECTURE.md`); this is a port of its Kit/Features, with every review fix.
The signatures here are the agreement between the data and UI engineers. Change them by PR, and
tell the other side.

```
:core     Kotlin/JVM + kotlinx.serialization + coroutines + OkHttp. No android.* imports.
  models (com.mekari.project.core), ApiError, MekariApi, LiveApi, TokenStore, ServerSettings,
  FakeApi + DemoIds (…core.fake), WebLinks, DeepLink, Mentions, Reorder, ServerUrl
      ▲
:feature  :core + kotlinx-coroutines only. State holders exposing StateFlow (com.mekari.project.feature).
      ▲
:app      Compose UI, Android ViewModel adapters, DataStore/Keystore, WorkManager. No business logic.
```

Both libraries build and unit-test on the JVM (`./gradlew :core:test :feature:test`).

## 0. Threading & lifetime (read first)

- **Every `:feature` state holder is main-confined.** Call its methods and read its `StateFlow`s on
  the main thread: `Dispatchers.Main.immediate` in the app, and the test dispatcher in tests
  (`Dispatchers.setMain`). Holders keep plain mutable fields and need no locks. This is the Android
  form of the Mac's `@MainActor` stores.
- **Actions are `suspend fun`s that never throw to the caller** (cancellation aside). Failures
  land in state as `lastError`. `:app` ViewModels launch them in `viewModelScope`, and tests call
  them directly inside `runTest`.
- **`MekariApi` is main-safe.** Its suspend functions move their I/O off the main thread themselves.
- **Cancellation is not an error.** A cancelled call throws `CancellationException`, which is never
  wrapped in `ApiError`. Holders let it propagate and never show it. A cancelled first load goes back
  to `LoadState.Idle`.
- **Time:** debounce, polling, linger and cooldown use coroutine `delay`, so tests run on virtual
  time (`advanceTimeBy`). "Today" comes from an injected `java.time.Clock` (its zone included).
- **Lifetime:** `AppSession` runs on an app-level `CoroutineScope` that the app hands in. Each
  `WorkspaceScope` owns a child scope (poller, linger timers, debouncers), which is
  cancelled when the workspace changes or on sign-out; the celebration cooldown belongs to `AppSession`
  (app-wide). Work launched elsewhere (a ViewModel action, a renumber loop) can't outlive it either:
  the scope's API client refuses every request once the scope is closed (as a cancellation, before it
  is sent), the old scope closes BEFORE the header changes, and a late `confirm()` from a closed scope
  neither celebrates nor starts the cooldown.

## 1. :core: models

`@Serializable` data classes. IDs are `String` (BE UUIDs, not parsed). JSON is camelCase. The shared
`MekariJson` config uses `ignoreUnknownKeys = true` and `explicitNulls = false`. Timestamps are
`java.time.Instant`, decoded from ISO-8601 with or without fractional seconds. `dueDate` and
`startDate` are `java.time.LocalDate` (`"YYYY-MM-DD"`, never a UTC instant).

Two names avoid clashes: **`TaskItem`** (a BE Task) and **`InboxNotification`** (a BE Notification).

```kotlin
/** BE enums are OPEN: an unknown value decodes as itself instead of failing a whole list. Compare with
 *  the constants; every `when` needs an `else` branch. `known` lists the constants, for pickers. */
@JvmInline @Serializable(with = WorkspaceRole.Serializer::class)
value class WorkspaceRole(val raw: String) {
  companion object { val ADMIN; val MEMBER; val GUEST; val known: List<WorkspaceRole> }   // unknown: neither admin nor guest
}
// Same shape for each, with these constants:
//   ProjectRole       OWNER, EDITOR, COMMENTER
//   ProjectPrivacy    PUBLIC, TEAM, PRIVATE
//   ProjectView       LIST, BOARD, CALENDAR                  (unknown → list)
//   Priority          NONE, LOW, MEDIUM, HIGH
//   NotificationType  ASSIGNED, COMMENTED, COMPLETED, MENTIONED
//   ActivityType      CREATED, COMPLETED, REOPENED, ASSIGNED, UNASSIGNED, DUE_DATE_CHANGED,
//                     MOVED_SECTION, RENAMED, PRIORITY_CHANGED, DESCRIPTION_CHANGED

data class UserSummary(val id: String, val name: String, val email: String, val avatarColor: String)
data class PickableUser(val user: UserSummary, val role: WorkspaceRole) { val isGuest: Boolean }   // GET /users item (W8); flat JSON
data class User(val id: String, val name: String, val email: String, val avatarColor: String,
                val role: WorkspaceRole, val isActive: Boolean, val createdAt: Instant)
data class Workspace(val id: String, val name: String, val isOrganization: Boolean, val domain: String?,
                     val color: String, val myRole: WorkspaceRole, val memberCount: Int, val createdAt: Instant) {
  val isGuest: Boolean; val isAdmin: Boolean
}
data class Me(val user: User, val currentWorkspace: Workspace?, val workspaces: List<Workspace>)   // flat JSON (User & {...})

data class Section(val id: String, val projectId: String, val name: String, val position: Double)
data class ProjectMember(val user: UserSummary, val role: ProjectRole)
data class Project(val id: String, val workspaceId: String, val teamId: String, val name: String,
                   val description: String?, val color: String, val privacy: ProjectPrivacy, val archived: Boolean,
                   val defaultView: ProjectView, val owner: UserSummary, val myRole: ProjectRole?,
                   val createdAt: Instant, val updatedAt: Instant)
data class ProjectDetail(val project: Project, val sections: List<Section>, val members: List<ProjectMember>,
                         val team: TeamRef)                                                   // flat JSON
data class TeamRef(val id: String, val name: String)
data class ProjectRef(val id: String, val name: String, val color: String)                    // TaskItem.project

data class TaskItem(
  val id: String, val workspaceId: String, val projectId: String?, val sectionId: String?, val parentId: String?,
  val title: String, val description: String?, val assignee: UserSummary?, val creator: UserSummary,
  val dueDate: LocalDate?, val startDate: LocalDate?, val priority: Priority,
  val completed: Boolean, val completedAt: Instant?, val position: Double,
  val subtaskCount: Int, val completedSubtaskCount: Int, val commentCount: Int,
  val project: ProjectRef?, val createdAt: Instant, val updatedAt: Instant,
)
data class TaskDetail(val task: TaskItem, val followers: List<UserSummary>, val subtasks: List<TaskItem>,
                      val parent: TaskParentRef?) { val id: String }                          // flat JSON
data class TaskParentRef(val id: String, val title: String)

sealed interface Story { val id: String; val createdAt: Instant }       // by "kind"; unknown kinds are skipped in feeds
data class Comment(override val id: String, val author: UserSummary, val body: String, override val createdAt: Instant,
                   val editedAt: Instant?) : Story { val segments: List<Mentions.Segment> }   // parsed body
data class Activity(override val id: String, val actor: UserSummary, val type: ActivityType,
                    val data: JsonObject, override val createdAt: Instant) : Story

data class InboxNotification(val id: String, val workspaceId: String, val type: NotificationType, val read: Boolean,
                             val createdAt: Instant, val actor: UserSummary, val task: NotificationTaskRef?)
data class NotificationTaskRef(val id: String, val title: String, val projectId: String?)
data class InboxPage(val data: List<InboxNotification>, val unreadCount: Int)
data class SearchResults(val tasks: List<TaskItem>, val projects: List<Project>, val users: List<UserSummary>) { val isEmpty: Boolean }
```

"Flat JSON" types decode and encode the BE's intersection types (`Task & {...}`): the nested
property is filled from the same JSON object.

### Writes

```kotlin
/** Tri-state for PATCH bodies: a null property is omitted; Patch.Clear → JSON null; Patch.Set(v) → v. */
sealed interface Patch<out T> { data class Set<T>(val value: T) : Patch<T>; data object Clear : Patch<Nothing> }

data class NewTask(                                   // POST /tasks
  val title: String, val projectId: String? = null, val sectionId: String? = null, val parentId: String? = null,
  val description: String? = null, val assigneeId: String? = null, val dueDate: LocalDate? = null,
  val startDate: LocalDate? = null, val priority: Priority? = null, val position: Double? = null,
)
data class TaskChanges(                               // PATCH /tasks/:id; null = untouched
  val title: String? = null, val description: Patch<String>? = null, val projectId: Patch<String>? = null,
  val sectionId: Patch<String>? = null, val assigneeId: Patch<String>? = null, val dueDate: Patch<LocalDate>? = null,
  val startDate: Patch<LocalDate>? = null, val priority: Priority? = null, val completed: Boolean? = null,
  val position: Double? = null,
)
data class SectionChanges(val name: String? = null, val position: Double? = null)
```

## 2. :core: errors

```kotlin
class ApiError(
  val code: Code,                    // open: BE codes + client-side TRANSPORT / DECODING
  val status: Int?,                  // HTTP status; null for TRANSPORT
  override val message: String,      // the BE message; the UI shows this
  val issues: List<FieldIssue> = emptyList(),   // error.details on VALIDATION
) : Exception(message) {
  @JvmInline value class Code(val raw: String) { companion object {
    val NOT_FOUND; val VALIDATION; val UNAUTHORIZED; val FORBIDDEN; val CONFLICT; val GONE; val RATE_LIMITED; val INTERNAL
    val TRANSPORT    // no response (offline, DNS, TLS, timeout)
    val DECODING     // 2xx with a body we couldn't decode; the message names the JSON path
  } }
  fun issue(field: String): FieldIssue?                  // e.g. "assigneeId"
  val isNoWorkspace: Boolean                             // 403 + details [{path:["workspace"], message:"NO_WORKSPACE"}]
}
data class FieldIssue(val path: List<String>, val message: String)
```

A non-JSON error body becomes `Code("HTTP <status>")`. Equality is by code, status, message and issues.

## 3. :core: the API

One interface, two implementations: `LiveApi` (OkHttp) and `FakeApi` (in memory).
`:feature` depends only on the interface.

```kotlin
interface MekariApi {
  /** X-Workspace-Id for later requests; never sent on /auth/*, /health, /workspaces*. */
  suspend fun setWorkspace(id: String?)
  val events: Flow<ApiEvent>                             // hot; single consumer: AppSession

  // Health & auth
  suspend fun health()                                   // GET <server>/health (no auth); validates a server URL
  suspend fun login(email: String, password: String): Me // stores the sid token
  suspend fun logout()                                   // POST /auth/logout, best effort; always clears the token
  suspend fun me(): Me                                   // GET /auth/me, WITHOUT the workspace header
  val hasSession: Boolean

  // Workspaces & users
  suspend fun switchWorkspace(id: String): Workspace
  suspend fun users(query: String?): List<PickableUser>

  // Projects & sections
  suspend fun projects(archived: Boolean = false): List<Project>
  suspend fun project(id: String): ProjectDetail
  suspend fun projectTasks(projectId: String, completed: Boolean? = null): List<TaskItem>
  suspend fun createSection(projectId: String, name: String, position: Double? = null): Section
  suspend fun updateSection(id: String, changes: SectionChanges): Section
  suspend fun deleteSection(id: String)

  // Tasks
  suspend fun myTasks(includeCompleted: Boolean): List<TaskItem>
  suspend fun task(id: String): TaskDetail
  suspend fun createTask(task: NewTask): TaskDetail
  suspend fun updateTask(id: String, changes: TaskChanges): TaskDetail
  suspend fun deleteTask(id: String)
  suspend fun addFollower(taskId: String, userId: String): TaskDetail
  suspend fun removeFollower(taskId: String, userId: String): TaskDetail

  // Stories
  suspend fun stories(taskId: String): List<Story>
  suspend fun addComment(taskId: String, body: String): Comment
  suspend fun editComment(id: String, body: String): Comment
  suspend fun deleteComment(id: String)

  // Inbox & search
  suspend fun notifications(unreadOnly: Boolean = false, limit: Int = 50): InboxPage
  suspend fun markRead(notificationId: String)
  suspend fun markAllRead()
  suspend fun search(query: String): SearchResults
}

sealed interface ApiEvent {
  data object Unauthorized : ApiEvent                       // the stored token is already cleared
  data class WorkspaceLost(val workspaceId: String) : ApiEvent
}
```

Every failure is an `ApiError`, except cancellation, which throws `CancellationException`.

### LiveApi

```kotlin
class LiveApi(serverUrl: String, tokens: TokenStore, client: OkHttpClient = LiveApi.defaultClient()) : MekariApi
```

- Base URL = server URL + `/api/v1` (`/health` is called at the server root).
- **Session cookie:** OkHttp has **no CookieJar**.
  - `login` reads `sid` from the response's `Set-Cookie` headers with `SessionCookie.token(headers)`. It tolerates several cookies, folded headers, attributes in any order, and the comma in `Expires`. The token is then saved to `TokenStore`.
  - Every request sends `Cookie: sid=<token>`.
- **401 race:** each request remembers the token it was sent with. A 401 clears the token and emits `Unauthorized` **only if that token is still the stored one**, via `TokenStore.clearIf`, which is atomic with `save` (a re-login's save can't be wiped between the compare and the clear). A late 401 for an older token (the poller, a request from before a re-login) is ignored. `login` and `logout` never emit it.
- **Lost access:** a 404 on a *scoped collection* path while the header is set emits `WorkspaceLost(header)`, then throws as usual. The paths are the web FE's list: `/users`, `/projects`, `/me/tasks`, `POST /tasks`, `/notifications`, `/notifications/read-all` and `/search`.
- **Errors:** the BE envelope decodes into `ApiError`. A decoding failure names the type and the problem: kotlinx's own errors include the JSON path (`$.data[0].position`); the strict date/time serializers name the bad value (`Expected YYYY-MM-DD, got "…"`).
- **Cancellation:** cancelling the coroutine cancels the OkHttp call and throws `CancellationException`, never an `ApiError`.
- No retries and no caching in v1.

### TokenStore & settings

```kotlin
/** Synchronous: OkHttp interceptors read it on a background thread. The app's Keystore + DataStore
 *  implementation reads storage ONCE before AppSession.start(), then serves from memory and writes
 *  through asynchronously. It never logs the token. */
interface TokenStore {
  fun load(): String?; fun save(token: String); fun clear()
  fun clearIf(expected: String): Boolean   // atomic with save (one lock): clears only if still `expected`
}
class InMemoryTokenStore(token: String? = null) : TokenStore

interface ServerSettings { var serverUrl: String? }      // the app: DataStore, read once at start
class InMemoryServerSettings(serverUrl: String? = null) : ServerSettings
object ServerUrl { fun normalize(input: String): String? }   // adds https://, trims "/", rejects junk
```

### FakeApi

```kotlin
class FakeApi(seed: FakeSeed = FakeSeed.demo(), signedInAs: String? = "alice@example.com") : MekariApi {
  // Test knobs
  fun failNext(endpoint: FakeEndpoint, error: ApiError, times: Int = 1)
  fun hold(endpoint: FakeEndpoint)                       // park later calls (recorded, not yet run)…
  fun release(endpoint: FakeEndpoint, all: Boolean = false, newest: Boolean = false)   // …and let one (or all) run
  fun parkedCount(endpoint: FakeEndpoint): Int
  var latency: Duration                                  // previews: 300.milliseconds
  val calls: List<FakeCall>                              // recorded requests (endpoint + workspace header)
  fun emit(event: ApiEvent)
  fun addNotification(notification: InboxNotification, forUser: String)
  fun removeMembership(userId: String, workspaceId: String)
  fun currentTasks(): List<TaskItem>
}
enum class FakeEndpoint { HEALTH, LOGIN, LOGOUT, ME, SWITCH_WORKSPACE, USERS, PROJECTS, PROJECT, PROJECT_TASKS,
  CREATE_SECTION, UPDATE_SECTION, DELETE_SECTION, MY_TASKS, TASK, CREATE_TASK, UPDATE_TASK, DELETE_TASK,
  ADD_FOLLOWER, REMOVE_FOLLOWER, STORIES, ADD_COMMENT, EDIT_COMMENT, DELETE_COMMENT,
  NOTIFICATIONS, MARK_READ, MARK_ALL_READ, SEARCH }
class FakeSeed { companion object { fun demo(today: LocalDate = LocalDate.now()): FakeSeed; val empty: FakeSeed } }

/** Stable ids in FakeSeed.demo, for previews and UI tests (the Mac's DemoIDs, same values). */
object DemoIds {
  const val MEKARI: String          // workspace "Mekari" (organization)
  const val ACME_PILOT: String      // workspace "Acme Pilot"
  const val BOARD_PROJECT: String   // "Mobile App v2": 3 sections with tasks in each; alice is EDITOR
  const val READ_ONLY_PROJECT: String   // alice is COMMENTER
  const val CAMPAIGN_PROJECT: String
  const val PILOT_PROJECT: String   // Acme Pilot; the guest is EDITOR there
  const val TASK_WITH_STORIES: String   // a comment that mentions bob, 2 subtasks, followers
  const val ALICE: String; const val BOB: String; const val ADMIN: String; const val GUEST: String
  const val GUEST_EMAIL = "guest@partner.test"; const val PASSWORD = "password123"
}
```

`FakeSeed.demo` mirrors the BE seed and the Mac's: workspaces **Mekari** (organization, example.com;
admin, alice, bob) and **Acme Pilot** (alice ADMIN, bob MEMBER, the guest on one project), projects,
sections, ~30 tasks (overdue / today / this week / later / no date, plus subtasks), comments with a
mention, and notifications. Every password is `password123`. The fake applies the BE rules the UI
depends on: workspace scoping, guest visibility (W1, W7, W8), COMMENTER read-only (403), and a 404 +
`WorkspaceLost` on scoped collections after `removeMembership`. Its calls are main-safe, guarded by
a `Mutex`.

### Small helpers in :core

```kotlin
object Mentions {                                        // "@[Name](userId)" in comment bodies
  sealed interface Segment { data class Text(val text: String) : Segment; data class Mention(val name: String, val userId: String) : Segment }
  fun parse(body: String): List<Segment>
  fun token(user: UserSummary): String                   // "@[Alice Johnson](id)"
}
object WebLinks {                                        // Open in browser (Custom Tabs); every link carries ?workspace=<id>
  fun project(id: String, server: String, workspace: String): String          // /projects/:id
  fun task(task: TaskItem, server: String, workspace: String): String         // /projects/:pid?task=:id or /my-tasks?task=:id
  fun workspaceSettings(server: String, workspace: String): String
  fun acceptInvite(server: String): String
}
sealed interface DeepLink {                              // mekariproject://task/<uuid>, …/project/<uuid>
  data class Task(val id: String) : DeepLink; data class Project(val id: String) : DeepLink
  companion object { fun parse(uri: String): DeepLink? } // exactly one segment, a UUID (lowercased); else null
}
object Reorder {                                         // BE §2 float positions
  const val STEP = 1000.0
  fun position(before: Double?, after: Double?): Double  // (a+b)/2, last+1000, first/2, 1000 when empty
  fun position(insertingAt: Int, positions: List<Double>): Double   // positions = the destination without the moved task
  fun needsRenumber(before: Double?, after: Double?): Boolean
}
```

## 4. :feature: state holders

Every holder (state types are nested: `MyTasksStore.State`, `ProjectStore.State`, …) is a plain class built synchronously by `AppSession`/`WorkspaceScope` (internal
constructors), with no network work in its constructor, so previews work. It exposes **one `state: StateFlow<XxxState>`** (an immutable data class) plus
`suspend` actions. Collect it in Compose with `collectAsStateWithLifecycle()`.

**Conventions (all holders)**
- **Synchronous state:** holders recompute their state on every TaskCache change, synchronously (no flow
  hop), so right after an optimistic edit every holder's `state.value` already shows it.
- **Loading:** `phase: LoadState`. **Errors:** `lastError: ApiError?` is transient; the UI shows it, then calls `clearError()`.
- **Optimistic edits** go through the shared `TaskCache`, once. On failure, only that edit reverts, and `lastError` is set on the calling holder.
- **Loads** apply a response unless something newer has already been applied (a later load, or a local change). An older response never overwrites newer state, but a caller awaiting a load still gets its data.

```kotlin
sealed interface LoadState { data object Idle; data object Loading; data object Loaded; data class Failed(val error: ApiError) }
sealed interface NavigationTarget { data class Task(val id: String, val projectId: String?); data class Project(val id: String) }
```

### AppSession (root, one per app; the app's `AppContainer` owns it)

```kotlin
class AppSession(
  settings: ServerSettings, tokens: TokenStore,
  apiFactory: (serverUrl: String, tokens: TokenStore) -> MekariApi,
  prefs: FeaturePrefs,                                   // celebrationsEnabled (the app: DataStore)
  appScope: CoroutineScope,                              // Main.immediate + SupervisorJob
  clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) {
  val state: StateFlow<SessionState>
  val scope: StateFlow<WorkspaceScope?>                  // a NEW object per workspace (switch, recovery, sign-in): key UI state on it;
                                                         // null = signed out / no workspace

  suspend fun start()                                    // stored token + /auth/me → SignedIn, else SignedOut
  suspend fun signIn(server: String, email: String, password: String)   // normalize → /health → login
  suspend fun signOut()
  suspend fun switchWorkspace(id: String)                // serialized; newest wins; header + scope change together
  suspend fun resolve(link: DeepLink): DeepLinkResult    // auto-switches workspace like the web FE
  fun webUrl(path: String): String?                      // server + path + ?workspace=<current>
  fun dismissNotice()

  companion object {
    fun preview(signedIn: Boolean = true, asEmail: String = "alice@example.com", appScope: CoroutineScope): AppSession
    fun uiTest(signedIn: Boolean, appScope: CoroutineScope, fake: FakeApi = FakeApi(signedInAs = null)): AppSession
                                                         // the fake-API launch flag; any server URL. Pass your own `fake` to drive
                                                         // failNext/hold/release from Robolectric and instrumented tests
  }
}
data class SessionState(
  val auth: Auth,                                        // Restoring / SignedOut / SignedIn(me)
  val isSigningIn: Boolean, val signInError: SignInError?,
  val notice: String?,                                   // "You no longer have access to X", …
  val isSwitching: Boolean,                              // a switch or recovery is queued or running: show progress, hold edits
  val generation: Int,                                   // bumps when a switch commits or lost access resets
) { val current: Workspace?; val all: List<Workspace>; val isGuest: Boolean; val isAdmin: Boolean }
sealed interface Auth { data object Restoring; data object SignedOut; data class SignedIn(val me: Me) }
sealed interface SignInError { WrongCredentials; RateLimited; BadServer; Transport; data class Other(val message: String) }
sealed interface DeepLinkResult { data class Open(val target: NavigationTarget); data object NoAccess }
interface FeaturePrefs { var celebrationsEnabled: Boolean }   // default true; InMemoryFeaturePrefs for tests
```

**Rules ported from the Mac reviews:**
- **Serialized switching:** switches and lost-access recoveries run one at a time. A switch that is superseded before it starts is skipped *before its POST*, so the app and the server's `lastWorkspaceId` end on the newest choice. A user switch supersedes everything queued before it. Recovery supersedes nothing: it never cancels a user's switch.
- **Header and scope move together.** Recovery never clears the header. Until a new scope is adopted, both still name the lost workspace, so requests 404 instead of showing another workspace's data under its label. If recovery fails, the notice stays and the next scoped request retries.
- **Events:** `Unauthorized` → SignedOut and `scope = null`. `WorkspaceLost` → `/auth/me` without the header, adopt its current workspace, post a notice. `currentWorkspace == null` → `scope == null` ("No workspace — open in browser").
- `resolve` accepts only UUID ids.

### WorkspaceScope (everything for the current workspace)

```kotlin
class WorkspaceScope {                                   // built by AppSession; cancelled on switch/sign-out
  val id: String; val workspace: Workspace; val me: UserSummary
  val tasks: TaskCache
  val projects: ProjectListStore                         // the Projects tab (the Mac's sidebar)
  val myTasks: MyTasksStore
  val inbox: InboxStore
  val poller: NotificationPoller
  val search: SearchStore
  val quickAdd: QuickAddModel
  val completionFeedback: CompletionFeedback
  fun project(id: String): ProjectStore                  // cached per id
  fun taskDetail(id: String): TaskDetailStore            // cached per id; its role is seeded from `projects`
  fun userSearch(): UserSearch                           // new instance each time
  fun canEdit(task: TaskItem): Boolean                   // personal tasks / workspace admin: yes; a project task only if its project
                                                         // is known and I'm not COMMENTER (fail closed); read-only checkboxes swallow taps.
                                                         // Reads `projects.state`: collect it, so rows recompose when roles arrive
  suspend fun refresh()                                  // on foreground: My Tasks + projects + inbox
}
```

### TaskCache (single source of truth for tasks)

```kotlin
class TaskCache {
  val tasks: StateFlow<Map<String, TaskItem>>            // what views see: confirmed + edits in flight
  val details: StateFlow<Map<String, TaskDetail>>
  val lingering: StateFlow<Set<String>>                  // just completed; lists keep them in place
  var lingerDuration: Duration                           // 300.milliseconds; read when each linger starts; ZERO = off
  operator fun get(id: String): TaskItem?
  fun observe(id: String): Flow<TaskItem?>               // one row: emits only when THIS task changes (distinctUntilChanged)
  fun detail(id: String): TaskDetail?
  fun isLingering(id: String): Boolean
  fun hasPendingEdits(id: String): Boolean
  fun upsert(items: List<TaskItem>); fun upsert(detail: TaskDetail)
  fun remove(id: String)                                 // tombstone: later copies never bring it back
  /** Shows `changes` at once, PATCHes, stores the server's TaskDetail. On failure only this edit drops out
   *  and the error is rethrown (ApiError, or CancellationException). */
  suspend fun update(id: String, changes: TaskChanges, assignee: Patch<UserSummary>? = null)   // assignee: shown at once when assigneeId is set
}
```

- What views see is the last server-confirmed task with the edits still in flight replayed on top, in order. A failed edit drops out; later edits stay.
- A server copy older than the confirmed one (`updatedAt`) is ignored, so a slow list load can't snap a just-moved task back.
- **Linger:** completing a task starts a linger (`lingerDuration`). An un-complete, a failed completion, or a removal ends it at once. An older timer or an older failure never ends a newer linger.

Holders store **task ids** and read tasks from the cache, so an edit anywhere shows up everywhere.
Rows are keyed by task id, and each one reads `observe(id)` (or the `tasks` map collected once per list).
Other rows keep their identity because an edit replaces only that task's entry; unchanged `TaskItem`s
are the same instances. `:core` can't use `@Immutable`, so `:app` lists `com.mekari.project.core.**` in
its Compose stability config file.

### ProjectListStore

```kotlin
data class ProjectListStore.State(val projects: List<Project>, val phase: LoadState, val lastError: ApiError?)   // non-archived, by name
suspend fun load(); fun clearError()
```

### MyTasksStore

```kotlin
enum class DueBucket(val title: String) { OVERDUE, TODAY, UPCOMING, LATER, NO_DATE, COMPLETED }   // upcoming = 1…7 days
data class TaskGroup(val bucket: DueBucket, val taskIds: List<String>)
data class MyTasksStore.State(
  val groups: List<TaskGroup>,                           // non-empty buckets in order; by due date, then title
  val showCompleted: Boolean, val phase: LoadState, val lastError: ApiError?,
)
suspend fun refresh()
suspend fun setShowCompleted(show: Boolean)              // reloads incl. completed
suspend fun toggleComplete(id: String)
suspend fun addTask(title: String, dueDate: LocalDate? = null): Boolean   // personal, assigned to me; true once inserted
fun clearError()
```

- A task reassigned away from me drops out at once.
- A lingering task stays in its **previous** bucket, unless completed tasks are shown.

### ProjectStore

```kotlin
enum class ViewMode { LIST, BOARD }                      // initial from defaultView; CALENDAR/unknown → LIST
data class ProjectStore.State(
  val detail: ProjectDetail?, val sections: List<Section>,   // by position
  val viewMode: ViewMode, val showCompleted: Boolean, val canEdit: Boolean,
  val phase: LoadState, val lastError: ApiError?,
) { fun taskIds(sectionId: String?): List<String> }      // by position; null = "No section"; lingering tasks stay
fun setViewMode(mode: ViewMode); fun setShowCompleted(show: Boolean)   // local: completed tasks are always loaded
suspend fun load()
suspend fun addTask(title: String, sectionId: String?): Boolean   // true once the created task is in the list (clear the field then)
suspend fun addSection(name: String); suspend fun renameSection(id: String, name: String)
/** Anchored moves: insert before `beforeTaskId`, or at the end of the section when null. A no-op move
 *  (same place, or before == itself) sends nothing. */
suspend fun move(taskId: String, toSection: String?, before: String?)
suspend fun moveSection(id: String, before: String?)
suspend fun toggleComplete(id: String)
fun clearError()
```

- **canEdit:** workspace ADMIN, or `myRole != COMMENTER`.
- **Moves:** positions come from `Reorder`. When the gap is too small, the whole section is renumbered first, **including hidden completed tasks** (it fetches with `completed = null`). Every PATCH is tried; failed ones are retried once; if any still fail, the project reloads and `lastError` says "Couldn't reorder; try again".

### TaskDetailStore

```kotlin
data class TaskDetailStore.State(
  val task: TaskItem?,                                   // the cached row: shown at once
  val detail: TaskDetail?,                               // followers/subtasks/parent: "Loading details…" until loaded
  val stories: List<Story>,                              // oldest first
  val canEdit: Boolean, val isFollowing: Boolean, val phase: LoadState, val lastError: ApiError?,
)
val taskId: String
val composer: CommentComposer
suspend fun load()                                       // detail + stories in parallel, then the project role
// Final values only: the UI keeps text-field state and commits on done or focus loss. All optimistic.
suspend fun setTitle(title: String); suspend fun setDescription(text: String?)
suspend fun setAssignee(user: UserSummary?); suspend fun setDueDate(date: LocalDate?); suspend fun setPriority(p: Priority)
suspend fun toggleComplete(); suspend fun addSubtask(title: String); suspend fun toggleSubtask(id: String)
suspend fun follow(); suspend fun unfollow(); suspend fun addFollower(user: UserSummary); suspend fun removeFollower(user: UserSummary)
suspend fun editComment(id: String, body: String); suspend fun deleteComment(id: String)
fun clearError()
```

- **canEdit:**
  - personal tasks and workspace admins: always;
  - a project task: the role is seeded from the scope's known project (`projects`) and overridden by the store's own `GET /projects/:id`, which falls back to the seed if it fails;
  - fail closed (read-only) while neither knows the project.

```kotlin
/** The UI owns the comment text (a TextField bound to a StateFlow drops characters). Storage form:
 *  mentions as "@[Name](id)" in the text the UI holds; render them with a VisualTransformation. */
class CommentComposer {
  data class State(val mentionQuery: String?, val isSending: Boolean, val lastError: ApiError?)
  val state: StateFlow<State>
  val users: UserSearch                                  // follows mentionQuery
  fun draftChanged(text: String, cursor: Int)            // the "@word" ending at the cursor → mentionQuery → users
  fun insertMention(user: PickableUser, text: String, cursor: Int): Pair<String, Int>   // new text + cursor after "@[Name](id) "
  fun canSend(text: String): Boolean
  suspend fun send(body: String): Boolean                // true when sent (the comment is appended to stories); the UI then
                                                         // clears its text only if it's unchanged
}
class UserSearch {                                       // assignee, followers, mentions
  data class State(val query: String, val results: List<PickableUser>, val isLoading: Boolean, val lastError: ApiError?)
  val state: StateFlow<State>
  fun setQuery(query: String)                            // debounced 250 ms → GET /users?q=; role → "Guest" badge (W8)
  fun clearError()
}
```

### InboxStore & NotificationPoller

```kotlin
data class InboxStore.State(val notifications: List<InboxNotification>, val unreadCount: Int, val phase: LoadState, val lastError: ApiError?)
suspend fun load(); suspend fun markRead(id: String); suspend fun markAllRead()
suspend fun open(id: String): NavigationTarget?          // marks read
fun clearError()

data class LocalNotice(val id: String, val title: String, val taskId: String?)     // "Alice commented on Fix login"
class NotificationPoller {                               // drives InboxStore.load every 60 s while started (foreground)
  val newItems: SharedFlow<List<LocalNotice>>            // new unread since the last poll (never on the first poll)
  fun start(); fun stop()                                // idempotent; the scope starts it; :app stops it in the background
}
```

The background check is the app's WorkManager job (15 min). It uses `LiveApi` directly, plus a
`NotificationDiff.newUnread(previousIds, page)` helper from `:feature`.

### SearchStore

```kotlin
data class SearchStore.State(val query: String, val results: SearchResults?, val isSearching: Boolean, val lastError: ApiError?) { val isEmpty: Boolean }
fun setQuery(query: String)                              // min 2 chars, debounced 250 ms; stale responses dropped
fun clearError()
fun target(taskId: String): NavigationTarget             // people open mailto: in the app
```

### QuickAddModel (FAB + launcher shortcut)

```kotlin
data class QuickAddModel.State(val isSubmitting: Boolean, val lastError: ApiError?)   // the UI owns title + date
fun canSubmit(title: String): Boolean
suspend fun submit(title: String, dueDate: LocalDate?): Boolean   // personal task assigned to me; true on success (the UI clears
                                                                  // its fields if unchanged); My Tasks updates
```

### CompletionFeedback (Mac 0.1.1 rules)

```kotlin
sealed interface Celebration {
  data class SectionCleared(val projectId: String, val sectionId: String?) : Celebration
  data class ProjectCleared(val projectId: String) : Celebration
  data object DueTodayCleared : Celebration
}
class CompletionFeedback {
  data class Event(val id: Int, val taskId: String, val celebration: Celebration)   // id increments per event
  val lastEvent: StateFlow<Event?>                       // LaunchedEffect(event?.id) plays the burst
  val rolledBack: SharedFlow<String>                     // task id of a completion the server rejected: "Couldn't complete X"
  var celebrationsEnabled: Boolean                       // backed by FeaturePrefs; default true
}
```

- **When it's decided:** judged against the cache at the optimistic apply, judged **again** once the PATCH succeeds, and published only if it still holds. So these never celebrate:
  - an un-complete;
  - a rejected completion;
  - a completion un-completed before the server answered;
  - a completion whose "last" stopped being true meanwhile (a sibling's failed completion reopened it, or a refresh brought in a new task).
- **Which celebration** (highest first):
  - `ProjectCleared`: the last open top-level task in the project, anyone's;
  - `SectionCleared`: the last open top-level task in its section;
  - `DueTodayCleared`: my last open **top-level** task due today; subtasks don't count, and overdue isn't today.
- Subtasks never celebrate.
- **Data needed:** the project and section rules need that project's `ProjectStore` loaded; the due-today rule needs My Tasks loaded.
- **Rate limit:** at most one event per 30 s, **app-wide**. The cooldown belongs to `AppSession`, so it survives workspace switches. While celebrations are off, no event fires and no window starts.

## 5. Testing

- **:core unit tests (JVM):**
  - decoding every model from recorded BE JSON fixtures, including unknown enum values and unknown story kinds;
  - LocalDate and Instant formats, `Set-Cookie` parsing, error envelopes;
  - the workspace-header rules, the 401 sent-token rule and `WorkspaceLost`, via OkHttp `MockWebServer`;
  - cancellation becoming `CancellationException`;
  - Reorder, Mentions, WebLinks, DeepLink, ServerUrl.
- **:feature unit tests:** every holder against `FakeApi` in `runTest`, on virtual time. Interleavings are ordered with `hold`/`release`, releasing **one call at a time** (released together, the fake may run them in either order). Flows are asserted with Turbine.
- **Contract tests** (skipped unless `MP_BASE_URL` is set): `LiveApi` and the `:feature` flows against a freshly seeded BE:
  - login and cookie, `/auth/me`, the header and switching, lost access;
  - My Tasks, project tasks and moves, create, patch and complete;
  - comments and mentions, notifications, search;
  - the guest rules (W1, W7, W8).

  Locally they run against a throwaway DB, never the shared `asana` DB.
- New tests are mutation-checked: removing each rule they guard must make at least one fail.

## 6. Decisions

1. **One current workspace per app**, shared by every screen and the background worker (SPEC-ANDROID §3).
2. **Open enums are value classes over the raw string**, not Kotlin `enum`s. Unknown values round-trip, and `when` needs an `else`.
3. **State holders are main-confined plain classes, not ViewModels.** `:app` wraps them in ViewModels. They expose one state data class each, so Compose recomposes on one flow, and they never touch Android.
   *(As built in :app, PR 4.)* The session and its holders live in the Application-scoped `AppContainer`,
   not in ViewModels, so they survive rotation and process-wide state (one workspace) stays single.
   Holder actions run in `AppContainer.appScope` (Main.immediate + SupervisorJob), never a composable
   scope, so a screen going away can't abandon a write. Compose reads `state` with
   `collectAsStateWithLifecycle`.
4. **Text the user types lives in the UI**, never in store state (the Mac crash lesson: never push text into a
   focused field; on Android a TextField bound to a StateFlow drops characters). That covers add fields,
   quick add, the comment composer, and the search and user-search queries, which the UI only *sends*
   via `setQuery`. `addTask`/`submit`/`send` return `true` once the result is in, and the UI then clears
   its field if the text is unchanged. `addTask` waits for the server, because ids come
   from the BE: there are no temporary rows.
5. **`TokenStore` and `ServerSettings` are synchronous.** The app's DataStore/Keystore implementations read once at launch and write through; OkHttp interceptors can't suspend.

## 7. Attachments & links (v0.2: SPEC-ANDROID §8, BE SPEC §13 A1–A12)

The :core/:feature part, the same shape as the Mac's (asana-clone-mac `docs/ARCHITECTURE.md` §7, reviewed in
mac #37). `:app` adds the pickers (Photo Picker, OpenMultipleDocuments, TakePicture), Coil thumbnails, the
viewers, FileProvider/ACTION_VIEW/Share/SAF and the chips. Nothing here imports Android.

### :core

```kotlin
value class AttachmentKind(raw)          // FILE, LINK; open
value class LinkPreviewStatus(raw)       // PENDING, READY, FAILED; open
@Serializable data class Attachment(
    id, workspaceId, taskId, commentId: String?, kind, draft, uploader: UserSummary, createdAt: Instant,
    name, mimeType, size: Long?, width, height, hasThumbnail,                       // FILE
    url, title, description, siteName, provider, previewStatus, hasIcon, hasImage,   // LINK
    contentPath, thumbnailPath, iconPath, imagePath                                 // server-relative; never build them
) {
    val linkUrl: String?        // LINK url only when absolute http(s): anything else is never opened
    val host: String?           // without "www."
    val displayName: String     // FILE name; LINK title, else host, else url
    val isImage: Boolean
    fun thumbnailPath(size: ThumbnailSize): String?   // ?size=small|large, null without a thumbnail
}
object AttachmentLimits { MAX_UPLOAD_BYTES = 104_857_600; MAX_PER_COMMENT = 20; MAX_CONCURRENT_UPLOADS = 3; fun tooLarge() }
data class TransferProgress(completed: Long, total: Long?) { val fraction: Float? }
sealed interface DownloadOutcome { NotModified; Downloaded(etag) }

/** What an upload streams from: the app wraps a content:// Uri (ContentResolver + OpenableColumns). */
interface UploadSource { val name: String; val size: Long?; fun open(): InputStream; fun delete() {} }
class FileUploadSource(file, name = file.name, owned = false) : UploadSource   // tests, camera captures

// MekariApi additions
suspend fun uploadAttachment(taskId, source: UploadSource, draft: Boolean, progress: (TransferProgress) -> Unit = {}): Attachment
suspend fun addLink(taskId, url, title: String?, draft: Boolean): Attachment
suspend fun attachment(id): Attachment
suspend fun refreshLinkPreview(id): Attachment
suspend fun deleteAttachment(id)
suspend fun addComment(taskId, body, attachmentIds: List<String> = emptyList()): Comment
suspend fun download(path, destination: File, etag: String? = null, progress = {}): DownloadOutcome
suspend fun attachmentData(path): ByteArray

class AttachmentCache(directory: File, capacity = 500 MB, io = Dispatchers.IO) {   // app: cacheDir/attachments
    suspend fun file(attachment, api, progress = {}): File        // <dir>/<id>/<sanitised name>; ETag; offline → cached
    suspend fun cachedFile(attachmentId): File?
    suspend fun cancelDownload(attachmentId)                       // for every caller
    suspend fun wipe()
    suspend fun diskUsage(): Long
    companion object { fun scratch(): AttachmentCache }            // a temp folder (previews, tests)
}
```

- `TaskItem.attachmentCount`, `TaskDetail.attachments` (newest first), `Comment.attachments` (oldest first) default
  for older servers; a comment's body may be empty when it has attachments. `ActivityType.ATTACHMENT_ADDED/REMOVED`.
- `ApiError.Code.PAYLOAD_TOO_LARGE` (413, or the local pre-check with `status == null`) and `INSUFFICIENT_STORAGE`
  (507); 429 is `RATE_LIMITED`. A non-JSON 413/429/507 (a proxy page) maps by status.
- **LiveApi:** the upload is a one-shot multipart body streamed from `UploadSource.open()` in 64 KiB pieces; a
  stream longer than 100 MB is stopped (PAYLOAD_TOO_LARGE). OkHttp writes `filename="…"` as UTF-8 with browser
  escaping (the BE ignores `filename*`). Uploads and downloads use a 300 s read/write timeout and are never retried
  by OkHttp. Downloads stream to `.<uuid>.part` next to the destination and move into place on a complete 200.
  Byte GETs are ID-addressed (no `X-Workspace-Id`), carry the cookie, never follow redirects, and accept only
  `/api/v1/attachments/…` paths.
- **AttachmentCache safety:** only UUID ids name folders; every delete is checked to sit inside `directory`; index
  entries that don't name a UUID folder and a plain file name are dropped; `.part` leftovers are removed.
- **FakeApi:** the same seeded attachments as the Mac (`DemoIds.TASK_WITH_ATTACHMENTS`, `ATTACHMENT_*`), the BE
  rules (drafts, commenters, A7 deletes, empty comment deleted with its last attachment), `currentAttachments()`,
  `failNextUploadAfterBody(error)`, `addCommentDirectly(...)`.

### :feature

```kotlin
data class AttachmentUpload(id, taskId, source: Source, state: State, size: Long?, deletesSource: Boolean) {
    sealed interface Source { File(source: UploadSource); Link(url, title) }
    sealed interface State { Queued; Uploading(progress); Finishing; Finished(attachment); Failed(error) }
    val name; val isActive; val isFinishing; val isFinished; val isFailed; val attachment: Attachment?
}
sealed interface LinkResult { Added; NoRoom; Failed(error) }       // for the Add link sheet

class AttachmentUploads {                 // AppSession.uploads: session-scoped, keyed by task
    val state: StateFlow<List<AttachmentUpload>>
    fun uploads(taskId): List<AttachmentUpload>
    fun retry(id); fun cancel(id): Boolean                            // false while finishing
}

// TaskDetailStore.State additions: attachments, uploads, canAttach
fun canDelete(attachment): Boolean                                    // uploader, project OWNER, workspace ADMIN; personal: + creator
fun attach(source: UploadSource, deletesSource: Boolean = false)      // task-level; survives leaving the task
suspend fun addLink(url, title): LinkResult
fun retryUpload(id); fun cancelUpload(id): Boolean
suspend fun deleteAttachment(id)                                      // optimistic; restores + lastError on refusal
suspend fun refreshLinkPreview(id)

// CommentComposer.State additions: attachments (chips), sendingIds, isUploading, hasRoomForAnother
fun canSend(text): Boolean                // text or ≥ 1 finished chip; nothing active; failed chips don't block
suspend fun send(body): Boolean           // posts finished chips as attachmentIds; drops them and failed ones on success
fun attach(source, deletesSource = false): Boolean                    // false = no room (20)
suspend fun attachLink(url, title): LinkResult                        // a failed link leaves no chip
fun retry(id); suspend fun remove(id): Boolean                       // false while finishing or being posted
suspend fun discard()                     // the UI calls it when the user leaves the task (not on rotation)

class AttachmentFiles {                   // WorkspaceScope.attachmentFiles
    val state: StateFlow<State(downloads: Map<id, TransferProgress>, lastError)>
    suspend fun file(attachment, revalidate = true): File?           // revalidate = false: a cached copy at once
    suspend fun cancelDownload(attachmentId)
    suspend fun cachedFile(attachment): File?
}

// AppSession(..., attachmentCache: AttachmentCache = AttachmentCache.scratch(), onAttachmentsWiped: () -> Unit = {})
//   the app passes AttachmentCache(cacheDir/attachments) and clears Coil's memory + disk caches in onAttachmentsWiped
```

- **Shared slots:** uploads, links and preview refreshes take one of 3 slots app-wide (the BE counts them all). A
  429 is retried once after 1.5 s, but not when the whole body was already sent (the BE's draft-quota check).
- **Finishing:** once the body is sent, cancel/remove refuse (it lands anyway). `discard()` lets a finishing chip
  land, then deletes its draft; chips being posted are left to the send.
- **After a failed send**, discarded drafts are deleted only on a 4xx; after a transport error or a 5xx the client
  first checks the attachment is still an unposted draft.
- **Wipe rules:** sign-out (including a 401) cancels uploads and wipes the cache (awaited by `signOut()`); starting
  signed out wipes; losing a workspace cancels its uploads and wipes before `/auth/me`. A closed scope cancels its
  composer uploads (drafts are swept server-side after 24 h).
- Deleting a comment removes its attachments from the task; a comment with attachments may be edited to an empty body (A9).
- Process death drops in-flight uploads (the user retries), per SPEC-ANDROID §8.
