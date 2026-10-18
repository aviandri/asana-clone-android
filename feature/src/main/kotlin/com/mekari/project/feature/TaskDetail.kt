package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.Comment
import com.mekari.project.core.MekariApi
import com.mekari.project.core.Mentions
import com.mekari.project.core.NewTask
import com.mekari.project.core.Patch
import com.mekari.project.core.PickableUser
import com.mekari.project.core.Priority
import com.mekari.project.core.Project
import com.mekari.project.core.ProjectRole
import com.mekari.project.core.Story
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskDetail
import com.mekari.project.core.TaskItem
import com.mekari.project.core.UserSummary
import com.mekari.project.core.WorkspaceRole
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The task detail screen (a full screen on phones, a pane on large screens). Every edit is optimistic through TaskCache. */
class TaskDetailStore internal constructor(
    private val api: MekariApi,
    private val tasks: TaskCache,
    val taskId: String,
    private val me: UserSummary,
    private val workspaceRole: WorkspaceRole,
    /** What the scope already knows about a project (the Projects tab's copy, with my role). */
    private val knownProject: (String) -> Project?,
    scope: CoroutineScope
) {
    data class State(
        /** The cached row: shown at once. */
        val task: TaskItem? = null,
        /** Followers, subtasks, parent: "Loading details…" until loaded. */
        val detail: TaskDetail? = null,
        /** Comments and activity, oldest first. */
        val stories: List<Story> = emptyList(),
        val canEdit: Boolean = false,
        val isFollowing: Boolean = false,
        val phase: LoadState = LoadState.Idle,
        val lastError: ApiError? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val composer: CommentComposer = CommentComposer(api, taskId, UserSearch(api, scope)) { comment ->
        _state.update { it.copy(stories = it.stories + comment) }
    }

    /** My role in a project, as fetched on load(). */
    private var fetchedRole: Pair<String, ProjectRole?>? = null

    init {
        tasks.changes.add(::recompute)
        recompute()
    }

    /** Re-derives canEdit when the scope's project list changes (wired by WorkspaceScope). */
    internal fun projectsChanged() = recompute()

    /** The detail and stories in parallel, then the project role. */
    suspend fun load() {
        if (tasks.detail(taskId) == null) _state.update { it.copy(phase = LoadState.Loading) }
        attempt(
            onError = { e -> _state.update { it.copy(phase = LoadState.Failed(e), lastError = e) } },
            onCancel = { _state.update { if (it.phase == LoadState.Loading) it.copy(phase = LoadState.Idle) else it } }
        ) {
            val (detail, stories) = coroutineScope {
                val detail = async { api.task(taskId) }
                val stories = async { api.stories(taskId) }
                detail.await() to stories.await()
            }
            tasks.upsert(detail)
            _state.update { it.copy(stories = stories) }
            detail.task.projectId?.let { projectId ->
                fetchedRole = try {
                    projectId to api.project(projectId).project.myRole
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // back to what the scope knows, or read-only
                }
            }
            _state.update { it.copy(phase = LoadState.Loaded) }
            recompute()
        }
    }

    // Final values only: the UI keeps text-field state and commits on done or focus loss.

    suspend fun setTitle(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == tasks[taskId]?.title) return
        apply(TaskChanges(title = trimmed))
    }

    suspend fun setDescription(text: String?) {
        val value = text?.takeIf { it.isNotBlank() }
        if (value == tasks[taskId]?.description) return
        apply(TaskChanges(description = value?.let { Patch.Set(it) } ?: Patch.Clear))
    }

    suspend fun setAssignee(user: UserSummary?) {
        if (user?.id == tasks[taskId]?.assignee?.id) return
        apply(
            TaskChanges(assigneeId = user?.let { Patch.Set(it.id) } ?: Patch.Clear),
            assignee = user?.let { Patch.Set(it) } ?: Patch.Clear
        )
    }

    suspend fun setDueDate(date: LocalDate?) {
        if (date == tasks[taskId]?.dueDate) return
        apply(TaskChanges(dueDate = date?.let { Patch.Set(it) } ?: Patch.Clear))
    }

    suspend fun setPriority(priority: Priority) {
        if (priority == tasks[taskId]?.priority) return
        apply(TaskChanges(priority = priority))
    }

    suspend fun toggleComplete() {
        val task = tasks[taskId] ?: return
        apply(TaskChanges(completed = !task.completed))
    }

    suspend fun addSubtask(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        return attempt(onError = ::fail) {
            api.createTask(NewTask(title = trimmed, parentId = taskId))
            tasks.upsert(api.task(taskId))
            true
        } ?: false
    }

    suspend fun toggleSubtask(id: String) {
        val subtask = tasks[id] ?: tasks.detail(taskId)?.subtasks?.firstOrNull { it.id == id } ?: return
        attempt(onError = ::fail) { tasks.update(id, TaskChanges(completed = !subtask.completed)) }
    }

    suspend fun follow() = addFollower(me)

    suspend fun unfollow() = removeFollower(me)

    suspend fun addFollower(user: UserSummary) {
        if (tasks.detail(taskId)?.followers?.any { it.id == user.id } == true) return
        attempt(onError = ::fail) { tasks.upsert(api.addFollower(taskId, user.id)) }
    }

    suspend fun removeFollower(user: UserSummary) {
        attempt(onError = ::fail) { tasks.upsert(api.removeFollower(taskId, user.id)) }
    }

    suspend fun editComment(id: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        attempt(onError = ::fail) {
            val edited = api.editComment(id, trimmed)
            _state.update { s -> s.copy(stories = s.stories.map { if (it is Comment && it.id == id) edited else it }) }
        }
    }

    suspend fun deleteComment(id: String) {
        attempt(onError = ::fail) {
            api.deleteComment(id)
            _state.update { s -> s.copy(stories = s.stories.filterNot { it is Comment && it.id == id }) }
        }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    private suspend fun apply(changes: TaskChanges, assignee: Patch<UserSummary>? = null) {
        attempt(onError = ::fail) { tasks.update(taskId, changes, assignee) }
    }

    private fun fail(error: ApiError) = _state.update { it.copy(lastError = error) }

    /**
     * canEdit: personal tasks and workspace admins always; a project task by my role, from our own fetch,
     * else what the scope already knows (so the screen isn't briefly read-only); while neither knows the
     * project, read-only (fail closed).
     */
    private fun recompute() {
        val task = tasks[taskId]
        val detail = tasks.detail(taskId)
        val canEdit = when {
            task == null -> false
            task.projectId == null || workspaceRole == WorkspaceRole.ADMIN -> true
            else -> {
                val fetched = fetchedRole
                if (fetched != null && fetched.first == task.projectId) {
                    fetched.second != ProjectRole.COMMENTER
                } else {
                    knownProject(task.projectId!!)?.let { it.myRole != ProjectRole.COMMENTER } ?: false
                }
            }
        }
        val isFollowing = detail?.followers?.any { it.id == me.id } ?: false
        _state.update { it.copy(task = task, detail = detail, canEdit = canEdit, isFollowing = isFollowing) }
    }
}

/**
 * The comment composer. The UI owns the text (a TextField bound to a StateFlow drops characters);
 * mentions are stored in it as `@[Name](id)` and rendered with a VisualTransformation.
 */
class CommentComposer internal constructor(
    private val api: MekariApi,
    private val taskId: String,
    /** Follows mentionQuery. */
    val users: UserSearch,
    private val onSent: (Comment) -> Unit
) {
    data class State(val mentionQuery: String? = null, val isSending: Boolean = false, val lastError: ApiError? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The "@word" ending at the cursor → mentionQuery → user search. */
    fun draftChanged(text: String, cursor: Int) {
        val query = mentionQuery(text, cursor)
        if (query != _state.value.mentionQuery) _state.update { it.copy(mentionQuery = query) }
        if (query != null) users.setQuery(query)
    }

    /** Replaces the "@word" at the cursor with the mention token and a space: the new text and cursor. */
    fun insertMention(user: PickableUser, text: String, cursor: Int): Pair<String, Int> {
        val at = mentionStart(text, cursor) ?: return text to cursor
        val token = Mentions.token(user.user) + " "
        val end = cursor.coerceIn(0, text.length)
        val newText = text.substring(0, at) + token + text.substring(end)
        _state.update { it.copy(mentionQuery = null) }
        return newText to at + token.length
    }

    fun canSend(text: String): Boolean = text.isNotBlank() && !_state.value.isSending

    /** True when sent (the comment is appended to the stories); the UI then clears its text if unchanged. */
    suspend fun send(body: String): Boolean {
        if (!canSend(body)) return false
        _state.update { it.copy(isSending = true) }
        try {
            return attempt(onError = { e -> _state.update { it.copy(lastError = e) } }) {
                val comment = api.addComment(taskId, body.trim())
                _state.update { it.copy(lastError = null, mentionQuery = null) }
                onSent(comment)
                true
            } ?: false
        } finally {
            _state.update { it.copy(isSending = false) }
        }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    internal companion object {
        /** "@" at the start or after whitespace, then a word up to the cursor; finished tokens don't count. */
        fun mentionStart(text: String, cursor: Int): Int? {
            val end = cursor.coerceIn(0, text.length)
            val at = text.lastIndexOf('@', end - 1).takeIf { it >= 0 } ?: return null
            if (at > 0 && !text[at - 1].isWhitespace()) return null
            val word = text.substring(at + 1, end)
            if (word.any { it.isWhitespace() || it == '[' || it == '(' || it == ')' }) return null
            return at
        }

        fun mentionQuery(text: String, cursor: Int): String? = mentionStart(text, cursor)?.let {
            text.substring(it + 1, cursor.coerceIn(0, text.length))
        }
    }
}

/** People pickers (assignee, followers, mentions): `GET /users?q=`, debounced. Roles badge guests (W8). */
class UserSearch internal constructor(private val api: MekariApi, scope: CoroutineScope) {
    data class State(
        val query: String = "",
        val results: List<PickableUser> = emptyList(),
        val isLoading: Boolean = false,
        val lastError: ApiError? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val debouncer = Debouncer(scope, 250.milliseconds)
    private var generation = 0

    /** The UI only sends the query (never binds a TextField to state.query). */
    fun setQuery(query: String) {
        generation += 1
        val current = generation
        val q = query.trim()
        _state.update { it.copy(query = query, isLoading = true) }
        debouncer.schedule {
            attempt(onError = { e ->
                if (current ==
                    generation
                ) {
                    _state.update { it.copy(lastError = e, isLoading = false) }
                }
            }) {
                val found = api.users(q.ifEmpty { null })
                if (current ==
                    generation
                ) {
                    _state.update { it.copy(results = found, lastError = null, isLoading = false) }
                }
            }
        }
    }
}
