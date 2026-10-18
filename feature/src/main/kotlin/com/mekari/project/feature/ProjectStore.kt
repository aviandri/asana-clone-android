package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.MekariApi
import com.mekari.project.core.NewTask
import com.mekari.project.core.Patch
import com.mekari.project.core.ProjectDetail
import com.mekari.project.core.ProjectRole
import com.mekari.project.core.ProjectView
import com.mekari.project.core.Reorder
import com.mekari.project.core.Section
import com.mekari.project.core.SectionChanges
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskItem
import com.mekari.project.core.WorkspaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One project: List and Board. */
class ProjectStore internal constructor(
    private val api: MekariApi,
    private val tasks: TaskCache,
    val projectId: String,
    private val workspaceRole: WorkspaceRole
) {
    enum class ViewMode { LIST, BOARD }

    data class State(
        val detail: ProjectDetail? = null,
        /** By position. */
        val sections: List<Section> = emptyList(),
        /** Starts on the project's defaultView (CALENDAR and unknown → LIST). */
        val viewMode: ViewMode = ViewMode.LIST,
        val showCompleted: Boolean = false,
        /** Workspace admins, and every member but COMMENTERs (BE §4; W7 limits managing, not editing). */
        val canEdit: Boolean = false,
        val phase: LoadState = LoadState.Idle,
        val lastError: ApiError? = null,
        internal val tasksBySection: Map<String?, List<String>> = emptyMap()
    ) {
        /** Top-level tasks in a section (null = "No section"), by position. Just-completed tasks linger. */
        fun taskIds(sectionId: String?): List<String> = tasksBySection[sectionId].orEmpty()

        /** Whether to show the "No section" group (only when it has tasks). */
        val hasUnsectionedTasks: Boolean get() = taskIds(null).isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var ids: List<String> = emptyList()
    private var didChooseInitialMode = false
    private var loadVersion = 0
    private var appliedVersion = 0

    init {
        tasks.changes.add(::recompute)
    }

    /** An explicit choice always wins, even over a first load still in flight. */
    fun setViewMode(mode: ViewMode) {
        didChooseInitialMode = true
        _state.update { it.copy(viewMode = mode) }
    }

    fun setShowCompleted(show: Boolean) {
        _state.update { it.copy(showCompleted = show) }
        recompute()
    }

    suspend fun load() {
        if (_state.value.detail == null) _state.update { it.copy(phase = LoadState.Loading) }
        loadVersion += 1
        val mine = loadVersion
        val isCurrent = { mine > appliedVersion }
        attempt(
            onError = { e -> if (isCurrent()) _state.update { it.copy(phase = LoadState.Failed(e), lastError = e) } },
            onCancel = { _state.update { if (it.phase == LoadState.Loading) it.copy(phase = LoadState.Idle) else it } }
        ) {
            val (loaded, list) = coroutineScope {
                val detail = async { api.project(projectId) }
                val all = async { api.projectTasks(projectId, completed = null) }
                detail.await() to all.await()
            }
            if (mine <= appliedVersion) return // a later load already landed
            appliedVersion = mine
            ids = list.map { it.id }
            val mode = if (didChooseInitialMode) {
                _state.value.viewMode
            } else {
                didChooseInitialMode = true
                if (loaded.project.defaultView == ProjectView.BOARD) ViewMode.BOARD else ViewMode.LIST
            }
            _state.update {
                it.copy(
                    detail = loaded,
                    sections = loaded.sections.sortedBy { s -> s.position },
                    viewMode = mode,
                    canEdit = workspaceRole == WorkspaceRole.ADMIN || loaded.project.myRole != ProjectRole.COMMENTER,
                    phase = LoadState.Loaded
                )
            }
            // Tasks edited meanwhile keep their newer state (TaskCache ignores older copies).
            tasks.upsert(list)
            recompute()
        }
    }

    /** True once the created task is in the list (the UI clears its field then). */
    suspend fun addTask(title: String, sectionId: String?): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        val last = orderedTasks(sectionId, includeCompleted = true).lastOrNull()?.position
        return attempt(onError = ::fail) {
            val created = api.createTask(
                NewTask(
                    title = trimmed,
                    projectId = projectId,
                    sectionId = sectionId,
                    position = Reorder.position(last, null)
                )
            )
            if (created.id !in ids) ids = ids + created.id
            tasks.upsert(created)
            recompute()
            true
        } ?: false
    }

    suspend fun addSection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        attempt(onError = ::fail) {
            val section = api.createSection(
                projectId,
                trimmed,
                Reorder.position(_state.value.sections.lastOrNull()?.position, null)
            )
            _state.update { it.copy(sections = it.sections + section) }
            recompute()
        }
    }

    suspend fun renameSection(id: String, name: String) {
        val trimmed = name.trim()
        val previous = _state.value.sections.firstOrNull { it.id == id } ?: return
        if (trimmed.isEmpty() || previous.name == trimmed) return
        replaceSection(id) { it.copy(name = trimmed) }
        attempt(onError = { e ->
            replaceSection(id) { previous }
            fail(e)
        }) {
            val saved = api.updateSection(id, SectionChanges(name = trimmed))
            replaceSection(id) { saved }
        }
    }

    /**
     * Moves a task into [toSection] (null = "No section") before [before], or to the end when null.
     * List reorder and Board column moves both land here. A move that changes nothing sends nothing.
     * When the gap is too small, the whole section is renumbered first, hidden completed tasks included.
     */
    suspend fun move(taskId: String, toSection: String?, before: String?) {
        if (before == taskId) return
        val task = tasks[taskId] ?: return
        val showCompleted = _state.value.showCompleted
        // The destination as the user sees it (completed tasks only when shown), without the moved task.
        val destination = orderedTasks(toSection, showCompleted).filter { it.id != taskId }
        val index = before?.let { b -> destination.indexOfFirst { it.id == b }.takeIf { it >= 0 } } ?: destination.size
        if (task.sectionId == toSection) {
            val currentIndex = orderedTasks(toSection, showCompleted).indexOfFirst { it.id == taskId }
            if (currentIndex == index) return
        }
        val prev = destination.getOrNull(index - 1)?.position
        val next = destination.getOrNull(index)?.position
        val sectionPatch: Patch<String>? = when {
            task.sectionId == toSection -> null
            toSection == null -> Patch.Clear
            else -> Patch.Set(toSection)
        }
        if (Reorder.needsRenumber(prev, next)) {
            renumber(taskId, toSection, destination.getOrNull(index)?.id, sectionPatch)
            return
        }
        attempt(onError = ::fail) {
            tasks.update(taskId, TaskChanges(sectionId = sectionPatch, position = Reorder.position(prev, next)))
        }
    }

    /** Moves a section before another one, or to the end when null. */
    suspend fun moveSection(id: String, before: String?) {
        if (before == id) return
        val sections = _state.value.sections
        val moving = sections.firstOrNull { it.id == id } ?: return
        val others = sections.filter { it.id != id }
        val index = before?.let { b -> others.indexOfFirst { it.id == b }.takeIf { it >= 0 } } ?: others.size
        if (sections.indexOfFirst { it.id == id } == index) return
        val moved = moving.copy(
            position = Reorder.position(others.getOrNull(index - 1)?.position, others.getOrNull(index)?.position)
        )
        _state.update { it.copy(sections = others.toMutableList().apply { add(index, moved) }) }
        attempt(onError = { e ->
            _state.update { it.copy(sections = sections) }
            fail(e)
        }) {
            val saved = api.updateSection(id, SectionChanges(position = moved.position))
            replaceSection(id) { saved }
        }
    }

    suspend fun toggleComplete(id: String) {
        val task = tasks[id] ?: return
        attempt(onError = ::fail) { tasks.update(id, TaskChanges(completed = !task.completed)) }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    /** Open top-level tasks in every section (for CompletionFeedback). */
    internal fun openTopLevelTasks(): List<TaskItem> =
        ids.mapNotNull { tasks[it] }.filter { it.projectId == projectId && it.parentId == null && !it.completed }

    // Internals

    /**
     * Too little room between neighbours: renumber the WHOLE destination section from the server's list
     * (completed and hidden tasks included, so none of them jumps to the top), with the moved task placed
     * before [anchorId] (or last). N PATCHes aren't atomic: every one is tried, failed ones are retried
     * once, and if any still fail, the project reloads (a half-applied order is never left on screen).
     */
    private suspend fun renumber(taskId: String, toSection: String?, anchorId: String?, sectionPatch: Patch<String>?) {
        try {
            val all = api.projectTasks(projectId, completed = null)
            tasks.upsert(all)
            ids = ids + all.map { it.id }.filter { it !in ids }
            val order = all.filter { it.sectionId == toSection && it.parentId == null && it.id != taskId }
                .sortedWith(compareBy<TaskItem> { it.position }.thenBy { it.createdAt })
                .map { it.id }
                .toMutableList()
            val at = anchorId?.let { order.indexOf(it).takeIf { i -> i >= 0 } } ?: order.size
            order.add(at, taskId)
            val failed = mutableListOf<Pair<String, TaskChanges>>()
            var lastFailure: Exception? = null
            for ((id, position) in order.zip(Reorder.renumbered(order.size))) {
                val changes = TaskChanges(sectionId = if (id == taskId) sectionPatch else null, position = position)
                if (tasks[id]?.position == position && changes.sectionId == null) continue
                try {
                    tasks.update(id, changes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += id to changes
                    lastFailure = e
                }
            }
            if (failed.isEmpty()) return
            var stillFailed = false
            for ((id, changes) in failed) {
                try {
                    tasks.update(id, changes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stillFailed = true
                    lastFailure = e
                }
            }
            if (stillFailed) throw lastFailure!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            load()
            val error = e.asApiError()
            fail(ApiError(error.code, error.status, "Couldn't reorder; try again", error.issues))
        }
    }

    private fun replaceSection(id: String, change: (Section) -> Section) {
        _state.update { s -> s.copy(sections = s.sections.map { if (it.id == id) change(it) else it }) }
    }

    private fun fail(error: ApiError) = _state.update { it.copy(lastError = error) }

    private fun orderedTasks(sectionId: String?, includeCompleted: Boolean): List<TaskItem> = ids.mapNotNull {
        tasks[it]
    }
        .filter { it.projectId == projectId && it.parentId == null && it.sectionId == sectionId }
        .filter { includeCompleted || !it.completed || tasks.isLingering(it.id) }
        .sortedWith(compareBy<TaskItem> { it.position }.thenBy { it.createdAt })

    private fun recompute() {
        val state = _state.value
        val sectionIds = state.sections.map { it.id } + listOf(null)
        val bySection = sectionIds.associateWith { id -> orderedTasks(id, state.showCompleted).map { it.id } }
        if (bySection != state.tasksBySection) _state.update { it.copy(tasksBySection = bySection) }
    }
}
