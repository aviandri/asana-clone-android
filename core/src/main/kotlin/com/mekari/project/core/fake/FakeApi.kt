package com.mekari.project.core.fake

import com.mekari.project.core.Activity
import com.mekari.project.core.ActivityType
import com.mekari.project.core.ApiError
import com.mekari.project.core.UploadSource
import com.mekari.project.core.TransferProgress
import com.mekari.project.core.LinkPreviewStatus
import com.mekari.project.core.DownloadOutcome
import com.mekari.project.core.AttachmentLinks
import com.mekari.project.core.AttachmentLimits
import com.mekari.project.core.AttachmentKind
import com.mekari.project.core.Attachment
import com.mekari.project.core.ApiEvent
import com.mekari.project.core.Comment
import com.mekari.project.core.FieldIssue
import com.mekari.project.core.InboxNotification
import com.mekari.project.core.InboxPage
import com.mekari.project.core.Me
import com.mekari.project.core.MekariApi
import com.mekari.project.core.Mentions
import com.mekari.project.core.NewTask
import com.mekari.project.core.NotificationTaskRef
import com.mekari.project.core.NotificationType
import com.mekari.project.core.Patch
import com.mekari.project.core.PickableUser
import com.mekari.project.core.Priority
import com.mekari.project.core.Project
import com.mekari.project.core.ProjectDetail
import com.mekari.project.core.ProjectPrivacy
import com.mekari.project.core.ProjectRole
import com.mekari.project.core.Reorder
import com.mekari.project.core.SearchResults
import com.mekari.project.core.Section
import com.mekari.project.core.SectionChanges
import com.mekari.project.core.Story
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskDetail
import com.mekari.project.core.TaskItem
import com.mekari.project.core.TaskParentRef
import com.mekari.project.core.TeamRef
import com.mekari.project.core.User
import com.mekari.project.core.UserSummary
import com.mekari.project.core.Workspace
import com.mekari.project.core.WorkspaceRole
import com.mekari.project.core.value
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The endpoints [FakeApi] records and can be told to fail or hold. */
enum class FakeEndpoint {
    HEALTH,
    LOGIN,
    LOGOUT,
    ME,
    SWITCH_WORKSPACE,
    USERS,
    PROJECTS,
    PROJECT,
    PROJECT_TASKS,
    CREATE_SECTION,
    UPDATE_SECTION,
    DELETE_SECTION,
    MY_TASKS,
    TASK,
    CREATE_TASK,
    UPDATE_TASK,
    DELETE_TASK,
    ADD_FOLLOWER,
    REMOVE_FOLLOWER,
    STORIES,
    ADD_COMMENT,
    EDIT_COMMENT,
    DELETE_COMMENT,
    NOTIFICATIONS,
    MARK_READ,
    MARK_ALL_READ,
    SEARCH,
    UPLOAD_ATTACHMENT,
    ADD_LINK,
    ATTACHMENT,
    REFRESH_LINK_PREVIEW,
    DELETE_ATTACHMENT,
    DOWNLOAD,
    ATTACHMENT_DATA
}

/** A recorded request: the endpoint and the workspace header in effect (null for unscoped endpoints). */
data class FakeCall(val endpoint: FakeEndpoint, val workspaceId: String?)

/**
 * An in-memory [MekariApi] seeded with demo data, for previews, unit tests and the fake-API UI tests.
 * It applies the BE rules the UI depends on: sessions (401), workspace scoping and lost access, project
 * visibility per role (BE §4, §12 guests), COMMENTER read-only (403), cross-workspace checks (400),
 * and notifications for assignments, comments and mentions.
 *
 * Main-safe: the data is guarded by a lock that is never held across a suspension; a held call parks
 * outside it.
 */
class FakeApi(seed: FakeSeed = FakeSeed.demo(), signedInAs: String? = "alice@example.com") : MekariApi {
    companion object {
        /** Every image path serves this 1×1 PNG (the fake has no real files). */
        val PNG_BYTES: ByteArray = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    }

    private val lock = Any()
    private val users = seed.users.toMutableList()
    private val workspaces = seed.workspaces.toMutableList()
    private val memberships = seed.memberships.toMutableList()
    private val teams = seed.teams.toMutableList()
    private val projects = seed.projects.toMutableList()
    private val tasks = seed.tasks.toMutableList()
    private val followers = seed.followers.mapValues { it.value.toMutableList() }.toMutableMap()
    private val stories = seed.stories.mapValues { it.value.toMutableList() }.toMutableMap()
    private val notifications = seed.notifications.toMutableList()
    private val attachments = seed.attachments.toMutableList()
    private val blobs = mutableMapOf<String, ByteArray>()
    private val afterBodyFailures = ArrayDeque<ApiError>()
    private val lastWorkspace = mutableMapOf<String, String>()
    private var currentUserId: String? = signedInAs?.let { email ->
        users.firstOrNull {
            it.summary.email ==
                email.lowercase()
        }?.summary?.id
    }
    private var workspaceId: String? = null
    private val failures = mutableMapOf<FakeEndpoint, Pair<ApiError, Int>>()
    private val holds = mutableSetOf<FakeEndpoint>()
    private val parked = mutableMapOf<FakeEndpoint, MutableList<CompletableDeferred<Unit>>>()
    private val recorded = mutableListOf<FakeCall>()
    private val eventChannel = Channel<ApiEvent>(Channel.UNLIMITED)

    override val events: Flow<ApiEvent> = eventChannel.receiveAsFlow()

    // Test knobs

    /** Every call waits this long first (previews: 300.milliseconds). Virtual time in tests. */
    @Volatile var latency: Duration = Duration.ZERO

    /** Recorded requests, for assertions. */
    val calls: List<FakeCall> get() = synchronized(lock) { recorded.toList() }

    /** The next [times] calls to [endpoint] fail with [error]. */
    fun failNext(endpoint: FakeEndpoint, error: ApiError, times: Int = 1) = synchronized(lock) {
        failures[endpoint] = error to maxOf(1, times)
    }

    /**
     * Parks every later call to [endpoint] (after it is recorded, before it runs) until [release]. Lets
     * tests interleave concurrent requests deterministically. Release them ONE AT A TIME when order
     * matters: released together, they may run in either order.
     */
    fun hold(endpoint: FakeEndpoint) = synchronized(lock) {
        holds += endpoint
    }

    /** Lets the oldest (or [newest]) parked call continue. With [all], releases every one and stops holding. */
    fun release(endpoint: FakeEndpoint, all: Boolean = false, newest: Boolean = false) {
        val toResume = synchronized(lock) {
            if (all) holds -= endpoint
            val waiting = parked[endpoint] ?: return
            when {
                waiting.isEmpty() -> emptyList()
                all -> waiting.toList().also { parked.remove(endpoint) }
                newest -> listOf(waiting.removeAt(waiting.lastIndex))
                else -> listOf(waiting.removeAt(0))
            }
        }
        toResume.forEach { it.complete(Unit) }
    }

    fun parkedCount(endpoint: FakeEndpoint): Int = synchronized(lock) { parked[endpoint]?.size ?: 0 }

    fun emit(event: ApiEvent) {
        eventChannel.trySend(event)
    }

    fun addNotification(notification: InboxNotification, forUser: String) = synchronized(lock) {
        notifications += FakeSeed.NotificationRecord(forUser, notification)
    }

    /** Removes a membership (to simulate losing access mid-session). */
    fun removeMembership(userId: String, workspaceId: String) = synchronized(lock) {
        memberships.removeAll { it.userId == userId && it.workspaceId == workspaceId }
    }

    fun currentTasks(): List<TaskItem> = synchronized(lock) { tasks.map(::render) }

    /** Every attachment in the fake (drafts included), for assertions. */
    fun currentAttachments(): List<Attachment> = synchronized(lock) { attachments.toList() }

    /**
     * The next upload receives its whole body (progress reaches the total), then fails with [error]: the BE's
     * second draft-quota check (429), or a proxy error after the upload.
     */
    fun failNextUploadAfterBody(error: ApiError) = synchronized(lock) { afterBodyFailures.addLast(error) }

    /**
     * Posts drafts as a comment by the signed-in user without going through the gate (no hold, no failNext): the
     * server committing a request whose answer is then lost.
     */
    fun addCommentDirectly(taskId: String, attachmentIds: List<String>, body: String = ""): Comment = synchronized(lock) {
        val user = requireUser()
        val comment = Comment(newId(), summary(user)!!, body, Instant.now())
        attachments.replaceAll { if (it.id in attachmentIds) it.copy(draft = false, commentId = comment.id) else it }
        stories.getOrPut(taskId) { mutableListOf() } += comment
        render(comment)
    }

    // Session

    override suspend fun setWorkspace(id: String?) = synchronized(lock) {
        workspaceId = id
    }

    override val hasSession: Boolean get() = synchronized(lock) { currentUserId != null }

    override suspend fun health() = gate(FakeEndpoint.HEALTH, scoped = false) { }

    override suspend fun login(email: String, password: String): Me = gate(FakeEndpoint.LOGIN, scoped = false) {
        val user = users.firstOrNull { it.summary.email == email.lowercase() && it.password == password }
            ?: throw ApiError(ApiError.Code.UNAUTHORIZED, 401, "Invalid email or password")
        if (!user.isActive) throw ApiError(ApiError.Code.UNAUTHORIZED, 401, "This account has been deactivated")
        currentUserId = user.summary.id
        makeMe()
    }

    override suspend fun logout() {
        try {
            gate(FakeEndpoint.LOGOUT, scoped = false) { currentUserId = null }
        } catch (_: ApiError) {
            synchronized(lock) { currentUserId = null }
        }
    }

    override suspend fun me(): Me = gate(FakeEndpoint.ME, scoped = false) {
        requireUser()
        makeMe()
    }

    override suspend fun switchWorkspace(id: String): Workspace = gate(FakeEndpoint.SWITCH_WORKSPACE, scoped = false) {
        val user = requireUser()
        val membership = activeMembership(user, id) ?: throw notFound("Workspace")
        lastWorkspace[user] = id
        workspaceShape(id, membership.role)
    }

    // Users

    override suspend fun users(query: String?): List<PickableUser> = gate(FakeEndpoint.USERS) {
        val (user, ws, role) = scope(collection = true)
        val q = query?.lowercase().orEmpty()
        pickable(user, ws, role).filter {
            q.isEmpty() ||
                it.name.lowercase().contains(q) ||
                it.user.email.lowercase().contains(q)
        }
            .sortedBy { it.name }
    }

    // Projects & sections

    override suspend fun projects(archived: Boolean): List<Project> = gate(FakeEndpoint.PROJECTS) {
        val (user, ws, role) = scope(collection = true)
        projects.filter { it.project.workspaceId == ws && it.project.archived == archived && canSee(it, user, role) }
            .map { render(it, user) }
            .sortedBy { it.name }
    }

    override suspend fun project(id: String): ProjectDetail = gate(FakeEndpoint.PROJECT, scoped = false) {
        val (record, user, _) = visibleProject(id)
        ProjectDetail(
            render(record, user),
            record.sections.sortedBy {
                it.position
            },
            record.members,
            TeamRef(record.project.teamId, teamName(record.project.teamId))
        )
    }

    override suspend fun projectTasks(projectId: String, completed: Boolean?): List<TaskItem> =
        gate(FakeEndpoint.PROJECT_TASKS, scoped = false) {
            val (record, _, _) = visibleProject(projectId)
            val sectionPos = record.sections.associate { it.id to it.position }
            tasks.filter {
                it.projectId == projectId &&
                    it.parentId == null &&
                    (completed == null || it.completed == completed)
            }
                .sortedWith(
                    compareBy<TaskItem> {
                        it.sectionId?.let(sectionPos::get) ?: Double.POSITIVE_INFINITY
                    }.thenBy { it.position }
                )
                .map(::render)
        }

    override suspend fun createSection(projectId: String, name: String, position: Double?): Section =
        gate(FakeEndpoint.CREATE_SECTION, scoped = false) {
            val (record, user, role) = visibleProject(projectId)
            requireEdit(record, user, role)
            val section = Section(
                newId(),
                projectId,
                nonEmpty(name, "name"),
                position ?: Reorder.position(record.sections.maxOfOrNull { it.position }, null)
            )
            mutateProject(projectId) { it.copy(sections = it.sections + section) }
            section
        }

    override suspend fun updateSection(id: String, changes: SectionChanges): Section =
        gate(FakeEndpoint.UPDATE_SECTION, scoped = false) {
            val record = projects.firstOrNull { p -> p.sections.any { it.id == id } } ?: throw notFound("Section")
            val (_, user, role) = visibleProject(record.project.id)
            requireEdit(record, user, role)
            var updated: Section? = null
            mutateProject(record.project.id) { p ->
                p.copy(
                    sections = p.sections.map { s ->
                        if (s.id != id) {
                            s
                        } else {
                            s.copy(name = changes.name ?: s.name, position = changes.position ?: s.position).also {
                                updated =
                                    it
                            }
                        }
                    }
                )
            }
            updated!!
        }

    override suspend fun deleteSection(id: String) = gate(FakeEndpoint.DELETE_SECTION, scoped = false) {
        val record = projects.firstOrNull { p -> p.sections.any { it.id == id } } ?: throw notFound("Section")
        val (_, user, role) = visibleProject(record.project.id)
        requireEdit(record, user, role)
        mutateProject(record.project.id) { p -> p.copy(sections = p.sections.filter { it.id != id }) }
        tasks.replaceAll { if (it.sectionId == id) it.copy(sectionId = null) else it }
    }

    // Tasks

    override suspend fun myTasks(includeCompleted: Boolean): List<TaskItem> = gate(FakeEndpoint.MY_TASKS) {
        val (user, ws, _) = scope(collection = true)
        tasks.filter {
            it.workspaceId == ws && it.assignee?.id == user && (includeCompleted || !it.completed)
        }.map(::render)
    }

    override suspend fun task(id: String): TaskDetail = gate(FakeEndpoint.TASK, scoped = false) {
        detail(visibleTask(id).first)
    }

    override suspend fun createTask(task: NewTask): TaskDetail = gate(FakeEndpoint.CREATE_TASK) {
        val user = requireUser()
        val title = nonEmpty(task.title, "title")
        var projectId = task.projectId
        var workspace: String
        val parentId = task.parentId
        if (parentId != null) {
            val (parent, _) = visibleTask(parentId)
            requireEditTask(parent, user)
            if (parent.parentId != null) throw validation("parentId", "Subtasks cannot have subtasks")
            if (task.sectionId != null &&
                parent.projectId == null
            ) {
                throw validation("sectionId", "Parent is a personal task")
            }
            projectId = parent.projectId
            workspace = parent.workspaceId
        } else if (projectId != null) {
            val (record, _, role) = visibleProject(projectId)
            requireEdit(record, user, role)
            workspace = record.project.workspaceId
        } else {
            workspace = scope(collection = true).second
        }
        task.sectionId?.let { sectionId ->
            val record =
                projects.firstOrNull { p -> p.sections.any { it.id == sectionId } } ?: throw notFound("Section")
            if (projectId != null && record.project.id != projectId) throw validation("sectionId", "Wrong project")
            val (_, _, role) = visibleProject(record.project.id)
            requireEdit(record, user, role)
            projectId = record.project.id
            workspace = record.project.workspaceId
        }
        task.assigneeId?.let {
            if (activeMembership(it, workspace) ==
                null
            ) {
                throw validation("assigneeId", "Not an active workspace member")
            }
        }
        val siblings = tasks.filter {
            it.projectId == projectId &&
                it.sectionId == task.sectionId &&
                it.parentId == task.parentId
        }
        val now = Instant.now()
        val created = TaskItem(
            id = newId(), workspaceId = workspace, projectId = projectId, sectionId = task.sectionId,
            parentId = task.parentId,
            title = title, description = task.description,
            assignee = task.assigneeId?.let(
                ::summary
            ),
            creator = summary(user)!!,
            dueDate = task.dueDate, startDate = task.startDate, priority = task.priority ?: Priority.NONE,
            position = task.position ?: Reorder.position(siblings.maxOfOrNull { it.position }, null),
            createdAt = now, updatedAt = now
        )
        tasks += created
        followers[created.id] = (listOf(user) + listOfNotNull(task.assigneeId)).toSortedSet().toMutableList()
        appendActivity(created.id, user, ActivityType.CREATED)
        task.assigneeId?.let { notify(listOf(it), NotificationType.ASSIGNED, user, created) }
        detail(created)
    }

    override suspend fun updateTask(id: String, changes: TaskChanges): TaskDetail =
        gate(FakeEndpoint.UPDATE_TASK, scoped = false) {
            val user = requireUser()
            val (task, _) = visibleTask(id)
            requireEditTask(task, user)
            changes.title?.let { nonEmpty(it, "title") }
            var assignee: Patch<UserSummary>? = null
            when (val patch = changes.assigneeId) {
                is Patch.Set -> {
                    if (activeMembership(patch.value, task.workspaceId) ==
                        null
                    ) {
                        throw validation("assigneeId", "Not an active workspace member")
                    }
                    assignee = Patch.Set(summary(patch.value)!!)
                }
                Patch.Clear -> assignee = Patch.Clear
                null -> Unit
            }
            var effective = changes
            val projectPatch = changes.projectId
            if (projectPatch != null && projectPatch.value != task.projectId) {
                // Moving between projects (BE): subtasks follow their parent; the target must be visible,
                // editable and in the same workspace; the old section never carries over.
                if (task.parentId != null) throw validation("projectId", "Move the parent instead")
                if (projectPatch is Patch.Set) {
                    val target =
                        projects.firstOrNull { it.project.id == projectPatch.value } ?: throw notFound("Project")
                    if (target.project.workspaceId !=
                        task.workspaceId
                    ) {
                        throw validation("projectId", "Project is in another workspace")
                    }
                    val (_, _, role) = visibleProject(projectPatch.value)
                    requireEdit(target, user, role)
                }
                if (changes.sectionId == null) effective = effective.copy(sectionId = Patch.Clear)
            }
            (effective.sectionId as? Patch.Set)?.let { section ->
                val projectId = effective.projectId?.value ?: if (effective.projectId == null) task.projectId else null
                val record = projects.firstOrNull { it.project.id == projectId }
                if (record == null || record.sections.none { it.id == section.value }) {
                    throw validation("sectionId", "Section belongs to a different project")
                }
            }
            val index = tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw notFound("Task")
            val updated = effective.applied(tasks[index], assignee).copy(updatedAt = Instant.now())
            tasks[index] = updated
            if (effective.projectId !=
                null
            ) {
                tasks.replaceAll { if (it.parentId == id) it.copy(projectId = updated.projectId) else it }
            }
            val completed = changes.completed
            if (completed != null && completed != task.completed) {
                appendActivity(id, user, if (completed) ActivityType.COMPLETED else ActivityType.REOPENED)
                if (completed) notify(followers[id].orEmpty(), NotificationType.COMPLETED, user, updated)
            }
            val newAssignee = (assignee as? Patch.Set)?.value
            if (newAssignee != null && newAssignee.id != task.assignee?.id) {
                followers[id] = (followers[id].orEmpty() + newAssignee.id).toSortedSet().toMutableList()
                appendActivity(
                    id,
                    user,
                    ActivityType.ASSIGNED,
                    buildJsonObject {
                        put("assigneeId", JsonPrimitive(newAssignee.id))
                    }
                )
                notify(listOf(newAssignee.id), NotificationType.ASSIGNED, user, updated)
            } else if (assignee == Patch.Clear && task.assignee != null) {
                appendActivity(
                    id,
                    user,
                    ActivityType.UNASSIGNED,
                    buildJsonObject {
                        put("assigneeId", JsonPrimitive(task.assignee.id))
                    }
                )
            }
            detail(updated)
        }

    override suspend fun deleteTask(id: String) = gate(FakeEndpoint.DELETE_TASK, scoped = false) {
        val user = requireUser()
        val (task, _) = visibleTask(id)
        requireEditTask(task, user)
        tasks.removeAll { it.id == id || it.parentId == id }
        Unit
    }

    override suspend fun addFollower(taskId: String, userId: String): TaskDetail =
        gate(FakeEndpoint.ADD_FOLLOWER, scoped = false) {
            val (task, _) = visibleTask(taskId)
            if (activeMembership(userId, task.workspaceId) ==
                null
            ) {
                throw validation("userId", "Not an active workspace member")
            }
            followers[taskId] = (followers[taskId].orEmpty() + userId).toSortedSet().toMutableList()
            detail(task)
        }

    override suspend fun removeFollower(taskId: String, userId: String): TaskDetail =
        gate(FakeEndpoint.REMOVE_FOLLOWER, scoped = false) {
            val (task, _) = visibleTask(taskId)
            followers[taskId]?.remove(userId)
            detail(task)
        }

    // Stories

    override suspend fun stories(taskId: String): List<Story> = gate(FakeEndpoint.STORIES, scoped = false) {
        visibleTask(taskId)
        stories[taskId].orEmpty().sortedBy { it.createdAt }.map { if (it is Comment) render(it) else it }
    }

    override suspend fun addComment(taskId: String, body: String, attachmentIds: List<String>): Comment =
        gate(FakeEndpoint.ADD_COMMENT, scoped = false) {
            val user = requireUser()
            val (task, _) = visibleTask(taskId)
            if (attachmentIds.size > AttachmentLimits.MAX_PER_COMMENT) throw validation("attachmentIds", "At most 20 attachments")
            val ids = attachmentIds.toSet()
            // BE: each must be a draft I uploaded on this task.
            if (!ids.all { id -> attachments.any { it.id == id && it.draft && it.uploader.id == user && it.taskId == taskId } }) {
                throw validation("attachmentIds", "Attachments must be your drafts on this task")
            }
            val text = if (ids.isEmpty()) nonEmpty(body, "body") else body.trim()
            val comment = Comment(newId(), summary(user)!!, text, Instant.now())
            val mentioned = comment.segments.filterIsInstance<Mentions.Segment.Mention>().map { it.userId }
            // BE: only members of the task's workspace can be mentioned.
            if (mentioned.any { activeMembership(it, task.workspaceId) == null }) {
                throw validation("body", "Only members of this workspace can be mentioned")
            }
            attachments.replaceAll { if (it.id in ids) it.copy(draft = false, commentId = comment.id) else it }
            stories.getOrPut(taskId) { mutableListOf() } += comment
            notify(mentioned, NotificationType.MENTIONED, user, task)
            notify(followers[taskId].orEmpty().filter { it !in mentioned }, NotificationType.COMMENTED, user, task)
            followers[taskId] = (followers[taskId].orEmpty() + user).toSortedSet().toMutableList()
            render(comment)
        }

    override suspend fun editComment(id: String, body: String): Comment =
        gate(FakeEndpoint.EDIT_COMMENT, scoped = false) {
            val user = requireUser()
            val (taskId, comment) = findComment(id)
            if (comment.author.id !=
                user
            ) {
                throw ApiError(ApiError.Code.FORBIDDEN, 403, "Only the author can edit a comment")
            }
            // A9: the body may be empty when the comment has attachments.
            val hasAttachments = attachments.any { it.commentId == id }
            val text = if (hasAttachments) body.trim() else nonEmpty(body, "body")
            val edited = comment.copy(body = text, editedAt = Instant.now())
            stories[taskId]?.replaceAll { if (it is Comment && it.id == id) edited else it }
            render(edited)
        }

    override suspend fun deleteComment(id: String) = gate(FakeEndpoint.DELETE_COMMENT, scoped = false) {
        val user = requireUser()
        val (taskId, comment) = findComment(id)
        val task = tasks.firstOrNull { it.id == taskId }
        val isAdmin = task?.let { activeMembership(user, it.workspaceId)?.role } == WorkspaceRole.ADMIN
        if (comment.author.id != user && !isAdmin) {
            throw ApiError(ApiError.Code.FORBIDDEN, 403, "Only the author or an admin can delete a comment")
        }
        stories[taskId]?.removeAll { it is Comment && it.id == id }
        attachments.removeAll { it.commentId == id }
        Unit
    }

    // Inbox & search

    override suspend fun notifications(unreadOnly: Boolean, limit: Int): InboxPage = gate(FakeEndpoint.NOTIFICATIONS) {
        val (user, ws, _) = scope(collection = true)
        val mine = notifications.filter {
            it.userId == user && it.notification.workspaceId == ws
        }.map { it.notification }
        InboxPage(
            mine.filter {
                !unreadOnly || !it.read
            }.sortedByDescending { it.createdAt }.take(limit),
            mine.count { !it.read }
        )
    }

    override suspend fun markRead(notificationId: String) = gate(FakeEndpoint.MARK_READ, scoped = false) {
        val user = requireUser()
        val i = notifications.indexOfFirst { it.notification.id == notificationId && it.userId == user }
        if (i < 0) throw notFound("Notification")
        notifications[i] = notifications[i].copy(notification = notifications[i].notification.copy(read = true))
    }

    override suspend fun markAllRead() = gate(FakeEndpoint.MARK_ALL_READ) {
        val (user, ws, _) = scope(collection = true)
        notifications.replaceAll {
            if (it.userId == user &&
                it.notification.workspaceId == ws
            ) {
                it.copy(notification = it.notification.copy(read = true))
            } else {
                it
            }
        }
    }

    override suspend fun search(query: String): SearchResults = gate(FakeEndpoint.SEARCH) {
        val (user, ws, role) = scope(collection = true)
        val q = query.trim().lowercase()
        if (q.length < 2) throw validation("q", "At least 2 characters")
        val visible = projects.filter { it.project.workspaceId == ws && canSee(it, user, role) }
        val visibleIds = visible.map { it.project.id }.toSet()
        val found = tasks.filter {
            it.workspaceId == ws &&
                it.title.lowercase().contains(q) &&
                taskVisible(it, user, role, visibleIds)
        }
        val people = pickable(user, ws, role).filter {
            it.name.lowercase().contains(q) ||
                it.user.email.lowercase().contains(q)
        }
        SearchResults(
            found.take(10).map(::render),
            visible.filter { it.project.name.lowercase().contains(q) }.take(10).map { render(it, user) },
            people.take(10).map { it.user }
        )
    }

    // Attachments & links

    override suspend fun uploadAttachment(
        taskId: String,
        source: UploadSource,
        draft: Boolean,
        progress: (TransferProgress) -> Unit
    ): Attachment {
        val declared = source.size
        if (declared != null && declared > AttachmentLimits.MAX_UPLOAD_BYTES) throw AttachmentLimits.tooLarge()
        // Read outside the lock (the gate's body runs under it).
        val (size, kept) = try {
            source.open().use { input ->
                val buffer = ByteArray(64 * 1024)
                val head = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > AttachmentLimits.MAX_UPLOAD_BYTES) throw AttachmentLimits.tooLarge()
                    if (total <= 1 shl 20) head.write(buffer, 0, read)
                }
                total to head.toByteArray().takeIf { total <= 1 shl 20 }
            }
        } catch (e: ApiError) {
            throw e
        } catch (_: java.io.IOException) {
            throw validation("file", "Can't read that file")
        }
        val user = gate(FakeEndpoint.UPLOAD_ATTACHMENT, scoped = false) {
            val user = requireUser()
            val (task, _) = visibleTask(taskId)
            if (!draft) requireEditTask(task, user)
            user
        }
        progress(TransferProgress(0, size))
        synchronized(lock) { afterBodyFailures.removeFirstOrNull() }?.let {
            progress(TransferProgress(size, size))
            throw it
        }
        val attachment = synchronized(lock) {
            val task = tasks.first { it.id == taskId }
            val name = AttachmentLinks.safeFileName(source.name)
            val mime = mimeType(name)
            val id = newId()
            val isImage = mime.startsWith("image/")
            val base = "/api/v1/attachments/$id"
            val created = Attachment(
                id = id, workspaceId = task.workspaceId, taskId = taskId, kind = AttachmentKind.FILE, draft = draft,
                uploader = summary(user)!!, createdAt = Instant.now(), name = name, mimeType = mime, size = size,
                width = if (isImage) 1 else null, height = if (isImage) 1 else null, hasThumbnail = isImage,
                contentPath = "$base/content", thumbnailPath = if (isImage) "$base/thumbnail" else null
            )
            kept?.let { blobs[id] = it }
            attachments += created
            if (!draft) taskLevelAdded(created, user)
            created
        }
        progress(TransferProgress(size, size))
        return attachment
    }

    override suspend fun addLink(taskId: String, url: String, title: String?, draft: Boolean): Attachment =
        gate(FakeEndpoint.ADD_LINK, scoped = false) {
            val user = requireUser()
            val (task, _) = visibleTask(taskId)
            if (!draft) requireEditTask(task, user)
            if (!AttachmentLinks.isValidLink(url)) throw validation("url", "Enter an http(s) link")
            val clean = url.trim()
            val host = java.net.URI(clean).host.lowercase()
            val created = Attachment(
                id = newId(), workspaceId = task.workspaceId, taskId = taskId, kind = AttachmentKind.LINK, draft = draft,
                uploader = summary(user)!!, createdAt = Instant.now(), url = clean,
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: host, siteName = host,
                provider = if ("figma.com" in host) "figma" else null, previewStatus = LinkPreviewStatus.READY
            )
            attachments += created
            if (!draft) taskLevelAdded(created, user)
            created
        }

    override suspend fun attachment(id: String): Attachment =
        gate(FakeEndpoint.ATTACHMENT, scoped = false) { visibleAttachment(id) }

    override suspend fun refreshLinkPreview(id: String): Attachment =
        gate(FakeEndpoint.REFRESH_LINK_PREVIEW, scoped = false) {
            visibleAttachment(id).also { if (it.kind != AttachmentKind.LINK) throw validation("id", "Not a link") }
        }

    override suspend fun deleteAttachment(id: String) = gate(FakeEndpoint.DELETE_ATTACHMENT, scoped = false) {
        val user = requireUser()
        val attachment = visibleAttachment(id)
        val task = tasks.firstOrNull { it.id == attachment.taskId } ?: throw notFound("Attachment")
        val isAdmin = activeMembership(user, task.workspaceId)?.role == WorkspaceRole.ADMIN
        val allowed = if (task.projectId != null) {
            val owner = projects.firstOrNull { it.project.id == task.projectId }
                ?.members?.firstOrNull { it.user.id == user }?.role == ProjectRole.OWNER
            attachment.uploader.id == user || owner || isAdmin
        } else {
            attachment.uploader.id == user || task.creator.id == user || isAdmin // A7
        }
        if (!allowed) throw ApiError(ApiError.Code.FORBIDDEN, 403, "You can't delete this attachment")
        attachments.removeAll { it.id == id }
        blobs.remove(id)
        val commentId = attachment.commentId
        if (commentId != null) {
            // A comment that was only this attachment goes with it.
            val comment = stories[task.id]?.firstOrNull { it is Comment && it.id == commentId } as? Comment
            if (comment != null && comment.body.isEmpty() && attachments.none { it.commentId == commentId }) {
                stories[task.id]?.removeAll { it is Comment && it.id == commentId }
            }
        } else if (!attachment.draft) {
            appendActivity(
                task.id,
                user,
                ActivityType.ATTACHMENT_REMOVED,
                buildJsonObject {
                    put("kind", JsonPrimitive(attachment.kind.raw))
                    put("name", JsonPrimitive(attachment.displayName))
                }
            )
        }
        Unit
    }

    override suspend fun download(
        path: String,
        destination: java.io.File,
        etag: String?,
        progress: (TransferProgress) -> Unit
    ): DownloadOutcome {
        val (attachment, data) = gate(FakeEndpoint.DOWNLOAD, scoped = false) { bytes(path) }
        val current = "\"fake-${attachment.id}\""
        if (etag == current) return DownloadOutcome.NotModified
        destination.absoluteFile.parentFile?.mkdirs()
        destination.writeBytes(data)
        progress(TransferProgress(data.size.toLong(), data.size.toLong()))
        return DownloadOutcome.Downloaded(current)
    }

    override suspend fun attachmentData(path: String): ByteArray =
        gate(FakeEndpoint.ATTACHMENT_DATA, scoped = false) { bytes(path).second }

    private fun bytes(path: String): Pair<Attachment, ByteArray> {
        if (!AttachmentLinks.isAttachmentPath(path)) throw ApiError(ApiError.Code.VALIDATION, null, "Not an attachment address")
        val rest = path.removePrefix(AttachmentLinks.PATH_PREFIX)
        val id = rest.takeWhile { it != '/' && it != '?' }
        val what = rest.removePrefix(id).substringBefore('?')
        val attachment = visibleAttachment(id)
        return attachment to when {
            what == "/content" -> blobs[id] ?: if (attachment.isImage) PNG_BYTES else (attachment.name ?: "file").toByteArray()
            what == "/thumbnail" && attachment.hasThumbnail -> PNG_BYTES
            what == "/icon" && attachment.hasIcon -> PNG_BYTES
            what == "/image" && attachment.hasImage -> PNG_BYTES
            else -> throw notFound("Attachment")
        }
    }

    /** Visible iff I can see the task; drafts only to their uploader (BE §13). */
    private fun visibleAttachment(id: String): Attachment {
        val user = requireUser()
        val attachment = attachments.firstOrNull { it.id == id } ?: throw notFound("Attachment")
        if (attachment.draft && attachment.uploader.id != user) throw notFound("Attachment")
        try {
            visibleTask(attachment.taskId)
        } catch (_: ApiError) {
            throw notFound("Attachment")
        }
        return attachment
    }

    private fun taskLevelAdded(attachment: Attachment, user: String) {
        appendActivity(
            attachment.taskId,
            user,
            ActivityType.ATTACHMENT_ADDED,
            buildJsonObject {
                put("attachmentId", JsonPrimitive(attachment.id))
                put("kind", JsonPrimitive(attachment.kind.raw))
                put("name", JsonPrimitive(attachment.displayName))
            }
        )
        val i = tasks.indexOfFirst { it.id == attachment.taskId }
        if (i >= 0) tasks[i] = tasks[i].copy(updatedAt = Instant.now())
    }

    // Rules

    /** Records the call, parks it while held, waits [latency], applies [failNext]; then runs [body] under the lock. */
    private suspend fun <T> gate(endpoint: FakeEndpoint, scoped: Boolean = true, body: () -> T): T {
        val parkedCall = synchronized(lock) {
            recorded += FakeCall(endpoint, if (scoped) workspaceId else null)
            if (endpoint in
                holds
            ) {
                CompletableDeferred<Unit>().also { parked.getOrPut(endpoint) { mutableListOf() } += it }
            } else {
                null
            }
        }
        // Like the live client: a caller cancelled while waiting gets CancellationException.
        parkedCall?.await()
        if (latency > Duration.ZERO) delay(latency)
        return synchronized(lock) {
            failures[endpoint]?.let { (failure, remaining) ->
                if (remaining > 1) failures[endpoint] = failure to remaining - 1 else failures.remove(endpoint)
                // Like LiveApi: only a request that carried a session ends it; login and logout never emit.
                val unauthorized = failure.code == ApiError.Code.UNAUTHORIZED
                if (unauthorized &&
                    currentUserId != null &&
                    endpoint != FakeEndpoint.LOGIN &&
                    endpoint != FakeEndpoint.LOGOUT
                ) {
                    currentUserId = null
                    eventChannel.trySend(ApiEvent.Unauthorized)
                }
                throw failure
            }
            body()
        }
    }

    /** Signed out means no token was sent, so (like LiveApi) a 401 without an Unauthorized event. */
    private fun requireUser(): String =
        currentUserId ?: throw ApiError(ApiError.Code.UNAUTHORIZED, 401, "Not signed in")

    private fun activeMembership(userId: String, workspaceId: String): FakeSeed.Membership? {
        if (users.none { it.summary.id == userId && it.isActive }) return null
        return memberships.firstOrNull { it.userId == userId && it.workspaceId == workspaceId && it.isActive }
    }

    /** The current workspace for a scoped request: header → last used → first membership (BE §12). */
    private fun scope(collection: Boolean): Triple<String, String, WorkspaceRole> {
        val user = requireUser()
        workspaceId?.let { header ->
            val m = activeMembership(user, header) ?: run {
                if (collection) eventChannel.trySend(ApiEvent.WorkspaceLost(header))
                throw notFound("Workspace")
            }
            return Triple(user, header, m.role)
        }
        val ws = currentWorkspaceId(user)
        val m = ws?.let { activeMembership(user, it) } ?: throw ApiError(
            ApiError.Code.FORBIDDEN,
            403,
            "You are not a member of any workspace",
            listOf(FieldIssue(listOf("workspace"), "NO_WORKSPACE"))
        )
        return Triple(user, ws, m.role)
    }

    private fun currentWorkspaceId(user: String): String? {
        lastWorkspace[user]?.let { if (activeMembership(user, it) != null) return it }
        return memberships.firstOrNull {
            it.userId == user && activeMembership(user, it.workspaceId) != null
        }?.workspaceId
    }

    private fun canSee(record: FakeSeed.ProjectRecord, user: String, role: WorkspaceRole): Boolean {
        val isMember = record.members.any { it.user.id == user }
        return when (role) {
            WorkspaceRole.ADMIN -> true
            WorkspaceRole.MEMBER -> when (record.project.privacy) {
                ProjectPrivacy.PUBLIC -> true
                ProjectPrivacy.TEAM ->
                    isMember ||
                        teams.firstOrNull { it.id == record.project.teamId }?.memberIds?.contains(user) == true
                else -> isMember
            }
            else -> isMember
        }
    }

    private fun visibleProject(id: String): Triple<FakeSeed.ProjectRecord, String, WorkspaceRole> {
        val user = requireUser()
        val record = projects.firstOrNull { it.project.id == id } ?: throw notFound("Project")
        val m = activeMembership(user, record.project.workspaceId) ?: throw notFound("Project")
        if (!canSee(record, user, m.role)) throw notFound("Project")
        return Triple(record, user, m.role)
    }

    private fun requireEdit(record: FakeSeed.ProjectRecord, user: String, role: WorkspaceRole) {
        val myRole = record.members.firstOrNull { it.user.id == user }?.role
        if (role != WorkspaceRole.ADMIN && myRole == ProjectRole.COMMENTER) {
            throw ApiError(ApiError.Code.FORBIDDEN, 403, "Commenters can only add comments in this project")
        }
    }

    private fun requireEditTask(task: TaskItem, user: String) {
        val projectId = task.projectId ?: return
        val (record, _, role) = visibleProject(projectId)
        requireEdit(record, user, role)
    }

    private fun taskVisible(task: TaskItem, user: String, role: WorkspaceRole, visibleProjects: Set<String>): Boolean {
        task.projectId?.let { return it in visibleProjects }
        if (role == WorkspaceRole.ADMIN) return true
        var root = task
        while (true) {
            val parent = root.parentId?.let { id -> tasks.firstOrNull { it.id == id } } ?: break
            root = parent
        }
        return root.creator.id == user || root.assignee?.id == user || followers[root.id].orEmpty().contains(user)
    }

    private fun visibleTask(id: String): Pair<TaskItem, WorkspaceRole> {
        val user = requireUser()
        val task = tasks.firstOrNull { it.id == id } ?: throw notFound("Task")
        val m = activeMembership(user, task.workspaceId) ?: throw notFound("Task")
        val visibleProjects = projects.filter {
            it.project.workspaceId == task.workspaceId && canSee(it, user, m.role)
        }.map { it.project.id }.toSet()
        if (!taskVisible(task, user, m.role, visibleProjects)) throw notFound("Task")
        return task to m.role
    }

    /** W8 pickers: guests only see people who share a project with them. */
    private fun pickable(user: String, ws: String, role: WorkspaceRole): List<PickableUser> {
        val coMembers: Set<String>? = if (role == WorkspaceRole.GUEST) {
            projects.filter { p -> p.project.workspaceId == ws && p.members.any { it.user.id == user } }
                .flatMap { p -> p.members.map { it.user.id } }.toSet() + user
        } else {
            null
        }
        return memberships.filter { it.workspaceId == ws && activeMembership(it.userId, ws) != null }
            .filter { coMembers?.contains(it.userId) ?: true }
            .mapNotNull { m -> summary(m.userId)?.let { PickableUser(it, m.role) } }
    }

    // Shapes & mutation helpers

    private fun makeMe(): Me {
        val user = requireUser()
        val record = users.firstOrNull { it.summary.id == user } ?: throw notFound("User")
        val mine = memberships.filter { it.userId == user && activeMembership(user, it.workspaceId) != null }
        val shapes = mine.map { workspaceShape(it.workspaceId, it.role) }.sortedBy { it.name }
        val current = currentWorkspaceId(user)?.let { id -> shapes.firstOrNull { it.id == id } }
        val u = record.summary
        return Me(
            User(
                u.id,
                u.name,
                u.email,
                u.avatarColor,
                current?.myRole ?: WorkspaceRole.MEMBER,
                record.isActive,
                record.createdAt
            ),
            current,
            shapes
        )
    }

    private fun workspaceShape(id: String, role: WorkspaceRole): Workspace = workspaces.first { it.id == id }.copy(
        myRole = role,
        memberCount = memberships.count { it.workspaceId == id && activeMembership(it.userId, id) != null }
    )

    private fun render(record: FakeSeed.ProjectRecord, user: String): Project =
        record.project.copy(myRole = record.members.firstOrNull { it.user.id == user }?.role)

    private fun render(task: TaskItem): TaskItem {
        val subtasks = tasks.filter { it.parentId == task.id }
        return task.copy(
            subtaskCount = subtasks.size,
            completedSubtaskCount = subtasks.count { it.completed },
            commentCount = stories[task.id].orEmpty().count { it is Comment },
            attachmentCount = attachments.count { it.taskId == task.id && !it.draft },
            project = task.projectId?.let { id -> projects.firstOrNull { it.project.id == id }?.project?.ref }
        )
    }

    private fun detail(task: TaskItem): TaskDetail {
        val current = tasks.firstOrNull { it.id == task.id } ?: task
        return TaskDetail(
            render(current),
            followers[task.id].orEmpty().mapNotNull(::summary).sortedBy { it.name },
            tasks.filter { it.parentId == task.id }.sortedBy { it.position }.map(::render),
            current.parentId?.let { id -> tasks.firstOrNull { it.id == id } }?.let { TaskParentRef(it.id, it.title) },
            attachments.filter { it.taskId == task.id && !it.draft }.sortedByDescending { it.createdAt }
        )
    }

    private fun render(comment: Comment): Comment = comment.copy(
        attachments = attachments.filter { it.commentId == comment.id && !it.draft }.sortedBy { it.createdAt }
    )

    private fun summary(id: String): UserSummary? = users.firstOrNull { it.summary.id == id }?.summary

    private fun teamName(id: String) = teams.firstOrNull { it.id == id }?.name ?: "Team"

    private fun mutateProject(id: String, change: (FakeSeed.ProjectRecord) -> FakeSeed.ProjectRecord) {
        val i = projects.indexOfFirst { it.project.id == id }
        if (i >= 0) projects[i] = change(projects[i])
    }

    private fun findComment(id: String): Pair<String, Comment> {
        for ((taskId, list) in stories) {
            val comment = list.firstOrNull { it is Comment && it.id == id } as? Comment ?: continue
            visibleTask(taskId)
            return taskId to comment
        }
        throw notFound("Comment")
    }

    private fun appendActivity(taskId: String, actor: String, type: ActivityType, data: JsonElement = JsonNull) {
        val who = summary(actor) ?: return
        stories.getOrPut(taskId) { mutableListOf() } += Activity(newId(), who, type, data, Instant.now())
    }

    /** Nobody is notified about their own actions (BE §3). */
    private fun notify(userIds: List<String>, type: NotificationType, actor: String, task: TaskItem) {
        val who = summary(actor) ?: return
        for (userId in userIds.toSet()) {
            if (userId == actor) continue
            notifications += FakeSeed.NotificationRecord(
                userId,
                InboxNotification(
                    newId(),
                    task.workspaceId,
                    type,
                    false,
                    Instant.now(),
                    who,
                    NotificationTaskRef(task.id, task.title, task.projectId)
                )
            )
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "csv", "json", "log" -> "text/plain"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun notFound(what: String) = ApiError(ApiError.Code.NOT_FOUND, 404, "$what not found")

    private fun validation(field: String, message: String) =
        ApiError(ApiError.Code.VALIDATION, 400, message, listOf(FieldIssue(listOf(field), message)))

    private fun nonEmpty(value: String, field: String): String = value.trim().ifEmpty {
        throw validation(field, "Required")
    }
}
