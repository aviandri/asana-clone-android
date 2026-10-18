package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.MekariApi
import com.mekari.project.core.NewTask
import com.mekari.project.core.Project
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskDetail
import com.mekari.project.core.TaskItem
import com.mekari.project.core.UserSummary
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The Projects tab: the workspace's visible, non-archived projects by name. */
class ProjectListStore internal constructor(private val api: MekariApi) {
    data class State(
        val projects: List<Project> = emptyList(),
        val phase: LoadState = LoadState.Idle,
        val lastError: ApiError? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Fired when the list changes (task detail holders re-derive canEdit). */
    internal val changes = Listeners()

    suspend fun load() {
        if (_state.value.projects.isEmpty()) _state.update { it.copy(phase = LoadState.Loading) }
        attempt(
            onError = { e -> _state.update { it.copy(phase = LoadState.Failed(e), lastError = e) } },
            onCancel = { _state.update { if (it.phase == LoadState.Loading) it.copy(phase = LoadState.Idle) else it } }
        ) {
            val projects = api.projects(archived = false).sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) {
                    it.name
                }
            )
            _state.update { it.copy(projects = projects, phase = LoadState.Loaded) }
            changes.fire()
        }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }
}

/** My Tasks buckets (matches the web FE: upcoming = due in the next 7 days). */
enum class DueBucket(val title: String) {
    OVERDUE("Overdue"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    LATER("Later"),
    NO_DATE("No date"),
    COMPLETED("Completed")
    ;

    companion object {
        fun of(task: TaskItem, today: LocalDate): DueBucket = if (task.completed) COMPLETED else of(task.dueDate, today)

        /** The bucket for an open task due on [due]. */
        fun of(due: LocalDate?, today: LocalDate): DueBucket {
            due ?: return NO_DATE
            val days = ChronoUnit.DAYS.between(today, due)
            return when {
                days < 0 -> OVERDUE
                days == 0L -> TODAY
                days <= 7 -> UPCOMING
                else -> LATER
            }
        }
    }
}

data class TaskGroup(val bucket: DueBucket, val taskIds: List<String>) {
    val title: String get() = bucket.title
}

class MyTasksStore internal constructor(
    private val api: MekariApi,
    private val tasks: TaskCache,
    private val me: UserSummary,
    private val clock: Clock
) {
    data class State(
        /** Non-empty buckets in order, each by due date then title. */
        val groups: List<TaskGroup> = emptyList(),
        val showCompleted: Boolean = false,
        val phase: LoadState = LoadState.Idle,
        val lastError: ApiError? = null
    ) {
        /** Overdue and due today (the quick glance). */
        val dueSoon: List<String> get() = groups.filter {
            it.bucket == DueBucket.OVERDUE || it.bucket == DueBucket.TODAY
        }.flatMap { it.taskIds }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var ids: List<String> = emptyList()
    private var loadVersion = 0
    private var appliedVersion = 0

    init {
        tasks.changes.add(::recompute)
    }

    internal val today: LocalDate get() = LocalDate.now(clock)

    suspend fun refresh() {
        if (ids.isEmpty()) _state.update { it.copy(phase = LoadState.Loading) }
        loadVersion += 1
        val mine = loadVersion
        attempt(
            onError = { e ->
                if (mine > appliedVersion) _state.update { it.copy(phase = LoadState.Failed(e), lastError = e) }
            },
            onCancel = { _state.update { if (it.phase == LoadState.Loading) it.copy(phase = LoadState.Idle) else it } }
        ) {
            val list = api.myTasks(includeCompleted = _state.value.showCompleted)
            // Applied unless a later refresh already landed (e.g. a quick second toggle answered first).
            if (mine <= appliedVersion) return
            appliedVersion = mine
            ids = list.map { it.id }
            _state.update { it.copy(phase = LoadState.Loaded) }
            tasks.upsert(list) // recomputes
            recompute()
        }
    }

    /** Includes completed tasks (reloads). */
    suspend fun setShowCompleted(show: Boolean) {
        if (show == _state.value.showCompleted) return
        _state.update { it.copy(showCompleted = show) }
        recompute()
        refresh()
    }

    suspend fun toggleComplete(id: String) {
        val task = tasks[id] ?: return
        attempt(onError = ::fail) { tasks.update(id, TaskChanges(completed = !task.completed)) }
    }

    /** A personal task assigned to me. True once it's in the list. */
    suspend fun addTask(title: String, dueDate: LocalDate? = null): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        return attempt(onError = ::fail) {
            didCreate(api.createTask(NewTask(title = trimmed, assigneeId = me.id, dueDate = dueDate)))
            true
        } ?: false
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    /** Another holder created a task (quick add); show it without a reload. */
    internal fun didCreate(detail: TaskDetail) {
        if (detail.id !in ids) ids = ids + detail.id
        tasks.upsert(detail)
        recompute()
    }

    /** My open top-level tasks due today (for CompletionFeedback; subtasks don't hold it back). */
    internal fun openTasksDueToday(): List<TaskItem> {
        val today = today
        return ids.mapNotNull { tasks[it] }.filter {
            it.assignee?.id == me.id &&
                it.parentId == null &&
                !it.completed &&
                it.dueDate == today
        }
    }

    private fun fail(error: ApiError) = _state.update { it.copy(lastError = error) }

    /**
     * A task reassigned away from me drops out at once; a just-completed one lingers in its old bucket
     * (unless completed tasks are shown).
     */
    private fun recompute() {
        val today = today
        val showCompleted = _state.value.showCompleted
        val buckets = mutableMapOf<DueBucket, MutableList<TaskItem>>()
        for (id in ids) {
            val task = tasks[id] ?: continue
            if (task.assignee?.id != me.id) continue
            var bucket = DueBucket.of(task, today)
            if (bucket == DueBucket.COMPLETED && !showCompleted) {
                if (!tasks.isLingering(id)) continue
                bucket = DueBucket.of(task.dueDate, today)
            }
            buckets.getOrPut(bucket) { mutableListOf() } += task
        }
        val groups = DueBucket.entries.mapNotNull { bucket ->
            val list = buckets[bucket]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sorted = list.sortedWith(
                compareBy<TaskItem, LocalDate?>(nullsLast()) {
                    it.dueDate
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            TaskGroup(bucket, sorted.map { it.id })
        }
        if (groups != _state.value.groups) _state.update { it.copy(groups = groups) }
    }
}

/** Quick add (FAB + launcher shortcut): a personal task assigned to me. The UI owns title and date. */
class QuickAddModel internal constructor(
    private val api: MekariApi,
    private val me: UserSummary,
    private val myTasks: MyTasksStore
) {
    data class State(val isSubmitting: Boolean = false, val lastError: ApiError? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun canSubmit(title: String): Boolean = title.isNotBlank() && !_state.value.isSubmitting

    /** True on success: the UI then clears its fields if they're unchanged. My Tasks shows it at once. */
    suspend fun submit(title: String, dueDate: LocalDate?): Boolean {
        if (!canSubmit(title)) return false
        _state.update { it.copy(isSubmitting = true) }
        try {
            return attempt(onError = { e -> _state.update { it.copy(lastError = e) } }) {
                myTasks.didCreate(api.createTask(NewTask(title = title.trim(), assigneeId = me.id, dueDate = dueDate)))
                _state.update { it.copy(lastError = null) }
                true
            } ?: false
        } finally {
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }
}
