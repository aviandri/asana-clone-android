package com.mekari.project.core

import kotlinx.coroutines.flow.Flow

/**
 * The backend (BE docs/SPEC.md §6). Two implementations: [LiveApi] (OkHttp) and
 * [com.mekari.project.core.fake.FakeApi] (in memory). Main-safe: implementations move their own I/O
 * off the calling thread.
 *
 * Every failure is an [ApiError], except cancellation, which throws `CancellationException`.
 */
interface MekariApi {
    /** `X-Workspace-Id` for later requests; never sent on `/auth/…`, `/health`, `/workspaces…`. */
    suspend fun setWorkspace(id: String?)

    /** Hot, buffered; single consumer (AppSession). */
    val events: Flow<ApiEvent>

    // Health & auth

    /** `GET <server>/health` (no auth): validates a server URL. */
    suspend fun health()

    /** Stores the session token. */
    suspend fun login(email: String, password: String): Me

    /** `POST /auth/logout`, best effort; always clears the token. */
    suspend fun logout()

    /** `GET /auth/me`, sent WITHOUT the workspace header. */
    suspend fun me(): Me

    val hasSession: Boolean

    // Workspaces & users

    suspend fun switchWorkspace(id: String): Workspace

    suspend fun users(query: String? = null): List<PickableUser>

    // Projects & sections

    suspend fun projects(archived: Boolean = false): List<Project>

    suspend fun project(id: String): ProjectDetail

    /** `completed = null`: open and completed tasks. */
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

    /** Oldest first; stories of an unknown kind are skipped. */
    suspend fun stories(taskId: String): List<Story>

    /** [attachmentIds]: drafts I uploaded on this task (≤ 20); [body] may then be empty. */
    suspend fun addComment(taskId: String, body: String, attachmentIds: List<String> = emptyList()): Comment

    suspend fun editComment(id: String, body: String): Comment

    suspend fun deleteComment(id: String)

    // Inbox & search

    suspend fun notifications(unreadOnly: Boolean = false, limit: Int = 50): InboxPage

    suspend fun markRead(notificationId: String)

    suspend fun markAllRead()

    suspend fun search(query: String): SearchResults

    // Attachments & links (BE §13)

    /**
     * Streams [source] as one multipart part `file` (never fully in memory). [draft] sends `?draft=true` (the
     * comment composer). Over [AttachmentLimits.MAX_UPLOAD_BYTES] → PAYLOAD_TOO_LARGE without a request (also
     * when the stream turns out longer than its declared size). Cancel the coroutine to cancel the upload.
     * [progress] is called on an I/O thread.
     */
    suspend fun uploadAttachment(
        taskId: String,
        source: UploadSource,
        draft: Boolean,
        progress: (TransferProgress) -> Unit = {}
    ): Attachment

    suspend fun addLink(taskId: String, url: String, title: String?, draft: Boolean): Attachment

    suspend fun attachment(id: String): Attachment

    suspend fun refreshLinkPreview(id: String): Attachment

    suspend fun deleteAttachment(id: String)

    /**
     * GETs a server-relative attachment path (`contentPath`, `thumbnailPath(size)`…) into [destination],
     * replacing it only once a 200 body is complete. With [etag], sends `If-None-Match`; a 304 leaves
     * [destination] alone. Paths outside `/api/v1/attachments/` are refused (VALIDATION) without a request.
     * [destination]'s folder must exist (the download never creates it).
     */
    suspend fun download(
        path: String,
        destination: java.io.File,
        etag: String? = null,
        progress: (TransferProgress) -> Unit = {}
    ): DownloadOutcome

    /** Small bytes in memory (a link icon or image, a thumbnail). Same path rule as [download]. */
    suspend fun attachmentData(path: String): ByteArray
}

sealed interface ApiEvent {
    /** A 401 for the token still stored; the token is already cleared. */
    data object Unauthorized : ApiEvent

    /** A scoped collection endpoint returned 404 while this workspace was in the header. */
    data class WorkspaceLost(val workspaceId: String) : ApiEvent
}
