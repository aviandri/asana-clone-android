package com.mekari.project.feature

import com.mekari.project.core.Attachment
import com.mekari.project.core.MekariApi
import com.mekari.project.core.Patch
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.TaskDetail
import com.mekari.project.core.TaskItem
import com.mekari.project.core.UserSummary
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The single source of truth for tasks in the current workspace. Holders keep task ids; rows and the
 * detail screen read tasks from here, so an edit anywhere shows up everywhere.
 *
 * What views see is the last server-confirmed task with the edits still in flight replayed on top, in
 * order. So:
 * - a failed edit drops out on its own; later edits (in flight or already saved) stay;
 * - server data older than what we have (`updatedAt`) is ignored, so a slow list load can't snap a
 *   just-moved task back;
 * - deleted tasks are tombstoned: later copies never bring them back.
 *
 * A just-completed task lingers ([lingerDuration]) so lists keep it in place briefly and a second tap
 * can un-complete it. An un-complete or a failed completion ends the linger at once.
 */
class TaskCache internal constructor(
    private val api: MekariApi,
    private val scope: CoroutineScope,
    lingerDuration: Duration = 300.milliseconds
) {
    private val _tasks = MutableStateFlow<Map<String, TaskItem>>(emptyMap())
    private val _details = MutableStateFlow<Map<String, TaskDetail>>(emptyMap())
    private val _lingering = MutableStateFlow<Set<String>>(emptySet())

    /** What views see: confirmed + edits in flight. An edit replaces only that task's entry. */
    val tasks: StateFlow<Map<String, TaskItem>> = _tasks.asStateFlow()
    val details: StateFlow<Map<String, TaskDetail>> = _details.asStateFlow()

    /** Completed less than [lingerDuration] ago; lists still show them in place. */
    val lingering: StateFlow<Set<String>> = _lingering.asStateFlow()

    /** How long a completed task lingers (ZERO: not at all). Read when each linger starts. */
    var lingerDuration: Duration = lingerDuration

    private val confirmed = mutableMapOf<String, TaskItem>()
    private val confirmedDetails = mutableMapOf<String, TaskDetail>()
    private val pending = mutableMapOf<String, MutableList<PendingEdit>>()
    private val deleted = mutableSetOf<String>()
    private var nextEdit = 0
    private val lingerTokens = mutableMapOf<String, Int>()
    private var nextLinger = 0

    /** Fired after every change, synchronously (holders recompute their state). */
    internal val changes = Listeners()

    /** Told about completions (wired by WorkspaceScope). */
    internal var feedback: CompletionFeedback? = null

    private class PendingEdit(val id: Int, val changes: TaskChanges, val assignee: Patch<UserSummary>?)

    operator fun get(id: String): TaskItem? = _tasks.value[id]

    fun detail(id: String): TaskDetail? = _details.value[id]

    /** One row: emits only when THIS task changes. */
    fun observe(id: String): Flow<TaskItem?> = tasks.map { it[id] }.distinctUntilChanged()

    fun isLingering(id: String): Boolean = id in _lingering.value

    /** Whether an edit to this task is still waiting for the server. */
    fun hasPendingEdits(id: String): Boolean = !pending[id].isNullOrEmpty()

    fun upsert(items: List<TaskItem>) {
        val touched = mutableSetOf<String>()
        for (item in items) {
            if (accept(item)) {
                touched += item.id
                item.parentId?.let { touched += it }
            }
        }
        touched.forEach(::recompute)
        publish()
    }

    fun upsert(detail: TaskDetail) {
        if (detail.id in deleted) return
        val current = confirmedDetails[detail.id]
        if (current != null && current.task.updatedAt > detail.task.updatedAt) return
        accept(detail.task)
        val touched = mutableSetOf(detail.id)
        for (subtask in detail.subtasks) if (accept(subtask)) touched += subtask.id
        confirmedDetails[detail.id] = detail.copy(subtasks = detail.subtasks.filter { it.id !in deleted })
        detail.task.parentId?.let { touched += it }
        touched.forEach(::recompute)
        publish()
    }

    /**
     * Changes a loaded task's attachments locally (after an upload, a posted comment or a delete) and keeps
     * `attachmentCount` in step. [bumpedAt] (a task-level add's time) moves `updatedAt` forward like the server
     * does, so a load that started before the change can't take it back.
     */
    fun editAttachments(id: String, bumpedAt: Instant? = null, change: (List<Attachment>) -> List<Attachment>) {
        val detail = confirmedDetails[id]
        if (detail == null) {
            val item = confirmed[id] ?: return
            val added = change(emptyList()).size
            confirmed[id] = item.copy(attachmentCount = maxOf(0, item.attachmentCount + added))
            recompute(id)
            publish()
            return
        }
        val list = change(detail.attachments).sortedByDescending { it.createdAt }
        var task = detail.task.copy(attachmentCount = maxOf(0, detail.task.attachmentCount + list.size - detail.attachments.size))
        if (bumpedAt != null && bumpedAt > task.updatedAt) task = task.copy(updatedAt = bumpedAt)
        confirmedDetails[id] = detail.copy(task = task, attachments = list)
        confirmed[id]?.let { confirmed[id] = it.copy(attachmentCount = task.attachmentCount, updatedAt = maxOf(it.updatedAt, task.updatedAt)) }
        recompute(id)
        publish()
    }

    fun remove(id: String) {
        deleted += id
        val parentId = (_tasks.value[id] ?: confirmed[id])?.parentId
        confirmed.remove(id)
        confirmedDetails.remove(id)
        pending.remove(id)
        shownTasks.remove(id)
        shownDetails.remove(id)
        endLinger(id)
        if (parentId != null) {
            confirmedDetails[parentId]?.let { parent ->
                confirmedDetails[parentId] = parent.copy(subtasks = parent.subtasks.filter { it.id != id })
            }
            recompute(parentId)
        }
        publish()
    }

    /**
     * Shows [changes] at once, PATCHes, then stores the server's TaskDetail. On failure only this edit
     * drops out and the error is rethrown: an ApiError, or CancellationException. Pass [assignee] when
     * `changes.assigneeId` is set, so the row shows the person immediately.
     */
    suspend fun update(id: String, changes: TaskChanges, assignee: Patch<UserSummary>? = null) {
        val completing = changes.completed == true && this[id]?.completed == false
        nextEdit += 1
        val edit = PendingEdit(nextEdit, changes, assignee)
        pending.getOrPut(id) { mutableListOf() } += edit
        recompute(id)
        if (changes.completed == false) endLinger(id)
        var linger: Int? = null
        var celebrates = false
        if (completing) {
            linger = startLinger(id)
            publish()
            celebrates = feedback?.celebration(forCompleting = id) != null
        }
        publish()
        try {
            val saved = api.updateTask(id, changes)
            finish(edit, id)
            upsert(saved)
            if (celebrates) feedback?.confirm(id)
        } catch (e: CancellationException) {
            finish(edit, id)
            linger?.let { endLinger(id, it) }
            publish()
            throw e
        } catch (e: Exception) {
            finish(edit, id)
            linger?.let { endLinger(id, it) }
            publish()
            if (completing) feedback?.rolledBack(id)
            throw e.asApiError()
        }
    }

    // Internals

    /** What views see, kept between publishes. */
    private val shownTasks = mutableMapOf<String, TaskItem>()
    private val shownDetails = mutableMapOf<String, TaskDetail>()

    /** Returns the linger's token (null: lingering is off). */
    private fun startLinger(id: String): Int? {
        val duration = lingerDuration
        if (duration <= Duration.ZERO) return null
        nextLinger += 1
        val token = nextLinger
        lingerTokens[id] = token
        _lingering.value = _lingering.value + id
        scope.launch {
            delay(duration)
            endLinger(id, token)
            publish()
        }
        return token
    }

    /** Ends [id]'s linger; with [token], only if it's still that linger. */
    private fun endLinger(id: String, token: Int? = null) {
        if (token != null && lingerTokens[id] != token) return
        lingerTokens.remove(id)
        if (id in _lingering.value) _lingering.value = _lingering.value - id
    }

    private fun finish(edit: PendingEdit, id: String) {
        pending[id]?.removeAll { it.id == edit.id }
        if (pending[id]?.isEmpty() == true) pending.remove(id)
        recompute(id)
    }

    /** Takes a server copy unless we already hold a newer one. */
    private fun accept(item: TaskItem): Boolean {
        if (item.id in deleted) return false
        val current = confirmed[item.id]
        if (current != null && current.updatedAt > item.updatedAt) return false
        confirmed[item.id] = item
        return true
    }

    /** Rebuilds what views see for one task (and its detail, and its parent's subtask list). */
    private fun recompute(id: String) {
        confirmed[id]?.let { base ->
            val shown = pending[id].orEmpty().fold(base) { task, edit -> edit.changes.applied(task, edit.assignee) }
            if (shownTasks[id] != shown) shownTasks[id] = shown
        }
        confirmedDetails[id]?.let { detail ->
            val shown = detail.copy(
                task = shownTasks[id] ?: detail.task,
                subtasks = detail.subtasks.map {
                    shownTasks[it.id]
                        ?: it
                }
            )
            if (shownDetails[id] != shown) shownDetails[id] = shown
        }
        val parentId = shownTasks[id]?.parentId ?: return
        val parent = confirmedDetails[parentId] ?: return
        if (parent.subtasks.none { it.id == id }) return
        val shownParent = parent.copy(
            task = shownTasks[parentId] ?: parent.task,
            subtasks = parent.subtasks.map {
                shownTasks[it.id]
                    ?: it
            }
        )
        if (shownDetails[parentId] != shownParent) shownDetails[parentId] = shownParent
    }

    /** Publishes the shown maps (unchanged entries keep their instances) and tells the holders. */
    private fun publish() {
        if (_tasks.value != shownTasks) _tasks.value = shownTasks.toMap()
        if (_details.value != shownDetails) _details.value = shownDetails.toMap()
        changes.fire()
    }
}
