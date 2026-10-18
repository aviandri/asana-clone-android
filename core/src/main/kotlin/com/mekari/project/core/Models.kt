@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class)

package com.mekari.project.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate

// IDs are Strings (BE UUIDs, not parsed). JSON is camelCase, as the properties are named.

@Serializable
data class UserSummary(val id: String, val name: String, val email: String, val avatarColor: String)

/** A `GET /users` entry: the person plus their role in the current workspace (W8). Flat in JSON. */
@Serializable(with = PickableUser.Serializer::class)
data class PickableUser(val user: UserSummary, val role: WorkspaceRole) {
    val id: String get() = user.id
    val name: String get() = user.name
    val isGuest: Boolean get() = role == WorkspaceRole.GUEST

    internal object Serializer : FlatSerializer<PickableUser>("PickableUser") {
        override fun read(json: Json, obj: JsonObject) =
            PickableUser(json.decodeFromJsonElement(obj), json.decodeFromJsonElement(obj["role"] ?: missing("role")))
        override fun write(json: Json, value: PickableUser) = merge(
            json.encodeToJsonElement(value.user).jsonObject,
            buildJsonObject { put("role", json.encodeToJsonElement(value.role)) },
        )
    }
}

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String,
    val avatarColor: String,
    val role: WorkspaceRole,
    val isActive: Boolean,
    val createdAt: Instant,
) {
    val summary: UserSummary get() = UserSummary(id, name, email, avatarColor)
}

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val isOrganization: Boolean,
    val domain: String? = null,
    val color: String,
    val myRole: WorkspaceRole,
    val memberCount: Int,
    val createdAt: Instant,
) {
    val isGuest: Boolean get() = myRole == WorkspaceRole.GUEST
    val isAdmin: Boolean get() = myRole == WorkspaceRole.ADMIN
}

/** `GET /auth/me`: the user plus their workspaces. Flat in JSON (`User & {...}`). */
@Serializable(with = Me.Serializer::class)
data class Me(val user: User, val currentWorkspace: Workspace?, val workspaces: List<Workspace>) {
    val id: String get() = user.id

    internal object Serializer : FlatSerializer<Me>("Me") {
        override fun read(json: Json, obj: JsonObject) = Me(
            json.decodeFromJsonElement(obj),
            obj["currentWorkspace"]?.let { json.decodeFromJsonElement<Workspace?>(it) },
            json.decodeFromJsonElement(obj["workspaces"] ?: missing("workspaces")),
        )
        override fun write(json: Json, value: Me) = merge(
            json.encodeToJsonElement(value.user).jsonObject,
            buildJsonObject {
                put("currentWorkspace", json.encodeToJsonElement(value.currentWorkspace))
                put("workspaces", json.encodeToJsonElement(value.workspaces))
            },
        )
    }
}

@Serializable
data class Section(val id: String, val projectId: String, val name: String, val position: Double)

@Serializable
data class ProjectMember(val user: UserSummary, val role: ProjectRole)

@Serializable
data class TeamRef(val id: String, val name: String)

/** `TaskItem.project`. */
@Serializable
data class ProjectRef(val id: String, val name: String, val color: String)

@Serializable
data class Project(
    val id: String,
    val workspaceId: String,
    val teamId: String,
    val name: String,
    val description: String? = null,
    val color: String,
    val privacy: ProjectPrivacy,
    val archived: Boolean = false,
    val defaultView: ProjectView,
    val owner: UserSummary,
    /** My role; null when I'm not a member (e.g. a workspace admin browsing). */
    val myRole: ProjectRole? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val ref: ProjectRef get() = ProjectRef(id, name, color)
}

/** `GET /projects/:id`. Flat in JSON (`Project & {...}`). */
@Serializable(with = ProjectDetail.Serializer::class)
data class ProjectDetail(
    val project: Project,
    /** Ordered by position. */
    val sections: List<Section>,
    val members: List<ProjectMember>,
    val team: TeamRef,
) {
    val id: String get() = project.id

    internal object Serializer : FlatSerializer<ProjectDetail>("ProjectDetail") {
        override fun read(json: Json, obj: JsonObject) = ProjectDetail(
            json.decodeFromJsonElement(obj),
            json.decodeFromJsonElement<List<Section>>(obj["sections"] ?: missing("sections")).sortedBy { it.position },
            json.decodeFromJsonElement(obj["members"] ?: missing("members")),
            json.decodeFromJsonElement(obj["team"] ?: missing("team")),
        )
        override fun write(json: Json, value: ProjectDetail) = merge(
            json.encodeToJsonElement(value.project).jsonObject,
            buildJsonObject {
                put("sections", json.encodeToJsonElement(value.sections))
                put("members", json.encodeToJsonElement(value.members))
                put("team", json.encodeToJsonElement(value.team))
            },
        )
    }
}

/** A BE Task. (Named `TaskItem` to avoid clashing with coroutine and Android "task"s.) */
@Serializable
data class TaskItem(
    val id: String,
    val workspaceId: String,
    val projectId: String? = null,
    val sectionId: String? = null,
    val parentId: String? = null,
    val title: String,
    val description: String? = null,
    val assignee: UserSummary? = null,
    val creator: UserSummary,
    val dueDate: LocalDate? = null,
    val startDate: LocalDate? = null,
    val priority: Priority = Priority.NONE,
    val completed: Boolean = false,
    val completedAt: Instant? = null,
    val position: Double = Reorder.STEP,
    val subtaskCount: Int = 0,
    val completedSubtaskCount: Int = 0,
    val commentCount: Int = 0,
    val project: ProjectRef? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class TaskParentRef(val id: String, val title: String)

/** `GET /tasks/:id`. Flat in JSON (`Task & {...}`). */
@Serializable(with = TaskDetail.Serializer::class)
data class TaskDetail(
    val task: TaskItem,
    val followers: List<UserSummary> = emptyList(),
    val subtasks: List<TaskItem> = emptyList(),
    val parent: TaskParentRef? = null,
) {
    val id: String get() = task.id

    internal object Serializer : FlatSerializer<TaskDetail>("TaskDetail") {
        override fun read(json: Json, obj: JsonObject) = TaskDetail(
            json.decodeFromJsonElement(obj),
            obj["followers"]?.let { json.decodeFromJsonElement<List<UserSummary>>(it) } ?: emptyList(),
            obj["subtasks"]?.let { json.decodeFromJsonElement<List<TaskItem>>(it) } ?: emptyList(),
            obj["parent"]?.let { json.decodeFromJsonElement<TaskParentRef?>(it) },
        )
        override fun write(json: Json, value: TaskDetail) = merge(
            json.encodeToJsonElement(value.task).jsonObject,
            buildJsonObject {
                put("followers", json.encodeToJsonElement(value.followers))
                put("subtasks", json.encodeToJsonElement(value.subtasks))
                put("parent", json.encodeToJsonElement(value.parent))
            },
        )
    }
}

@Serializable
data class NotificationTaskRef(val id: String, val title: String, val projectId: String? = null)

/** A BE Notification. (Named `InboxNotification` to avoid clashing with Android's.) */
@Serializable
data class InboxNotification(
    val id: String,
    val workspaceId: String,
    val type: NotificationType,
    val read: Boolean,
    val createdAt: Instant,
    val actor: UserSummary,
    val task: NotificationTaskRef? = null,
) {
    /** "Alice Johnson commented on Fix login" (inbox rows and local notifications). */
    val summary: String
        get() {
            val verb = when (type) {
                NotificationType.ASSIGNED -> "assigned you"
                NotificationType.COMMENTED -> "commented on"
                NotificationType.COMPLETED -> "completed"
                NotificationType.MENTIONED -> "mentioned you on"
                else -> "updated"
            }
            return if (task == null) "${actor.name} $verb" else "${actor.name} $verb ${task.title}"
        }
}

@Serializable
data class InboxPage(val data: List<InboxNotification>, val unreadCount: Int)

@Serializable
data class SearchResults(
    val tasks: List<TaskItem> = emptyList(),
    val projects: List<Project> = emptyList(),
    val users: List<UserSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = tasks.isEmpty() && projects.isEmpty() && users.isEmpty()
}

/** `{ "data": [...] }`. */
@Serializable
internal data class ListResponse<T>(val data: List<T>)

private fun missing(key: String): Nothing = throw kotlinx.serialization.SerializationException("missing key \"$key\"")
