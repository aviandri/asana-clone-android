package com.mekari.project.feature

import com.mekari.project.core.ApiEvent
import com.mekari.project.core.Comment
import com.mekari.project.core.InboxPage
import com.mekari.project.core.Me
import com.mekari.project.core.MekariApi
import com.mekari.project.core.NewTask
import com.mekari.project.core.PickableUser
import com.mekari.project.core.Project
import com.mekari.project.core.ProjectDetail
import com.mekari.project.core.SearchResults
import com.mekari.project.core.Section
import com.mekari.project.core.SectionChanges
import com.mekari.project.core.Story
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskDetail
import com.mekari.project.core.TaskItem
import com.mekari.project.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * The API as one WorkspaceScope sees it. Once the scope is closed (a switch, lost access, sign-out),
 * every call fails as a cancellation BEFORE it is sent: work started in the old workspace (a renumber
 * loop, an edit, a load launched from a ViewModel) can never go out under the next workspace's header.
 * AppSession closes the old scope before it changes the header. Header and events belong to AppSession.
 */
internal class ScopedApi(private val api: MekariApi) : MekariApi {
    @Volatile var closed = false
        private set

    fun close() {
        closed = true
    }

    private fun check() {
        if (closed) throw CancellationException("workspace scope closed")
    }

    private suspend inline fun <T> call(block: () -> T): T {
        check()
        return block()
    }

    override suspend fun setWorkspace(id: String?) =
        throw UnsupportedOperationException("the header belongs to AppSession")
    override val events: Flow<ApiEvent> get() = throw UnsupportedOperationException("events belong to AppSession")
    override val hasSession: Boolean get() = api.hasSession

    override suspend fun health() = call { api.health() }
    override suspend fun login(email: String, password: String): Me = call { api.login(email, password) }
    override suspend fun logout() = call { api.logout() }
    override suspend fun me(): Me = call { api.me() }
    override suspend fun switchWorkspace(id: String): Workspace = call { api.switchWorkspace(id) }
    override suspend fun users(query: String?): List<PickableUser> = call { api.users(query) }
    override suspend fun projects(archived: Boolean): List<Project> = call { api.projects(archived) }
    override suspend fun project(id: String): ProjectDetail = call { api.project(id) }
    override suspend fun projectTasks(projectId: String, completed: Boolean?): List<TaskItem> =
        call { api.projectTasks(projectId, completed) }
    override suspend fun createSection(projectId: String, name: String, position: Double?): Section =
        call { api.createSection(projectId, name, position) }
    override suspend fun updateSection(id: String, changes: SectionChanges): Section =
        call { api.updateSection(id, changes) }
    override suspend fun deleteSection(id: String) = call { api.deleteSection(id) }
    override suspend fun myTasks(includeCompleted: Boolean): List<TaskItem> = call { api.myTasks(includeCompleted) }
    override suspend fun task(id: String): TaskDetail = call { api.task(id) }
    override suspend fun createTask(task: NewTask): TaskDetail = call { api.createTask(task) }
    override suspend fun updateTask(id: String, changes: TaskChanges): TaskDetail = call { api.updateTask(id, changes) }
    override suspend fun deleteTask(id: String) = call { api.deleteTask(id) }
    override suspend fun addFollower(taskId: String, userId: String): TaskDetail =
        call { api.addFollower(taskId, userId) }
    override suspend fun removeFollower(taskId: String, userId: String): TaskDetail =
        call { api.removeFollower(taskId, userId) }
    override suspend fun stories(taskId: String): List<Story> = call { api.stories(taskId) }
    override suspend fun addComment(taskId: String, body: String): Comment = call { api.addComment(taskId, body) }
    override suspend fun editComment(id: String, body: String): Comment = call { api.editComment(id, body) }
    override suspend fun deleteComment(id: String) = call { api.deleteComment(id) }
    override suspend fun notifications(unreadOnly: Boolean, limit: Int): InboxPage =
        call { api.notifications(unreadOnly, limit) }
    override suspend fun markRead(notificationId: String) = call { api.markRead(notificationId) }
    override suspend fun markAllRead() = call { api.markAllRead() }
    override suspend fun search(query: String): SearchResults = call { api.search(query) }
}
