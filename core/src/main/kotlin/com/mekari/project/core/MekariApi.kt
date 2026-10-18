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
    /** A 401 for the token still stored; the token is already cleared. */
    data object Unauthorized : ApiEvent

    /** A scoped collection endpoint returned 404 while this workspace was in the header. */
    data class WorkspaceLost(val workspaceId: String) : ApiEvent
}
