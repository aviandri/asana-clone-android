package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentLimits
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
import com.mekari.project.core.TransferProgress
import com.mekari.project.core.UploadSource
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
    scope: CoroutineScope,
    /** Task-level uploads live at session scope, so they outlive this store (navigating back, rotation). */
    private val uploadStore: AttachmentUploads,
    private val slots: UploadSlots,
    private val workspaceId: String
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
        val lastError: ApiError? = null,
        /** Task-level and comment attachments, newest first. */
        val attachments: List<Attachment> = emptyList(),
        /** This task's queued, uploading, finishing and failed task-level uploads (they survive leaving the task). */
        val uploads: List<AttachmentUpload> = emptyList(),
        /** Task-level attachments need edit rights; COMMENTERs attach through the composer only. */
        val canAttach: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val composer: CommentComposer =
        CommentComposer(
            api,
            taskId,
            UserSearch(api, scope),
            UploadRunner(slots, scope),
            onReload = { reloadAfterUncertainSend() }
        ) { comment ->
            _state.update { it.copy(stories = it.stories + comment) }
            if (comment.attachments.isNotEmpty()) {
                tasks.editAttachments(taskId) { list ->
                    list.filterNot { a -> comment.attachments.any { it.id == a.id } } +
                        comment.attachments
                }
            }
        }

    /** My role in a project, as fetched on load(). */
    private var fetchedRole: Pair<String, ProjectRole?>? = null

    private val uploadsChanged: () -> Unit = { recompute() }

    init {
        tasks.changes.add(::recompute)
        uploadStore.changes.add(uploadsChanged)
        recompute()
    }

    /** The scope closed: stop listening to the session's upload store. */
    internal fun close() {
        uploadStore.changes.remove(uploadsChanged)
        composer.cancelUploads()
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

    /** The body may be empty only when the comment has attachments (A9). */
    suspend fun editComment(id: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && comment(id)?.attachments.isNullOrEmpty()) return
        attempt(onError = ::fail) {
            val edited = api.editComment(id, trimmed)
            _state.update { s -> s.copy(stories = s.stories.map { if (it is Comment && it.id == id) edited else it }) }
        }
    }

    suspend fun deleteComment(id: String) {
        attempt(onError = ::fail) {
            api.deleteComment(id)
            _state.update { s -> s.copy(stories = s.stories.filterNot { it is Comment && it.id == id }) }
            // The server deletes the comment's attachments with it.
            tasks.editAttachments(taskId) { list -> list.filterNot { it.commentId == id } }
        }
    }

    // Attachments

    /** The uploader, the project OWNER or a workspace ADMIN; personal tasks: the uploader or the task creator (A7). */
    fun canDelete(attachment: Attachment): Boolean {
        if (attachment.uploader.id == me.id || workspaceRole == WorkspaceRole.ADMIN) return true
        val task = tasks[taskId] ?: return false
        val projectId = task.projectId ?: return task.creator.id == me.id
        val fetched = fetchedRole
        if (fetched != null && fetched.first == projectId) return fetched.second == ProjectRole.OWNER
        return knownProject(projectId)?.myRole == ProjectRole.OWNER
    }

    /**
     * Starts a task-level upload (it carries on after leaving the task). Over 100 MB fails at once, without a
     * request. [UploadSource.delete] runs once the upload leaves the list.
     */
    fun attach(source: UploadSource) {
        uploadStore.start(taskId, tasks[taskId]?.workspaceId ?: workspaceId, source)
    }

    /** Adds a task-level link. A failure comes back for the popover (local http(s) check first, then the server's). */
    suspend fun addLink(url: String, title: String?): LinkResult {
        linkError(url)?.let { return LinkResult.Failed(it) }
        return try {
            attachmentAdded(slots.run { api.addLink(taskId, url.trim(), title, draft = false) })
            LinkResult.Added
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LinkResult.Failed(e.asApiError())
        }
    }

    fun retryUpload(id: String) = uploadStore.retry(id)

    /** Cancels a queued or uploading upload, or dismisses a failed one. False while it is finishing. */
    fun cancelUpload(id: String): Boolean = uploadStore.cancel(id)

    /**
     * Deletes an attachment, optimistically: it leaves the list (and its comment, and an empty-body comment whose
     * last attachment it was, as the server does) at once, and comes back with `lastError` if the server refuses.
     */
    suspend fun deleteAttachment(id: String) {
        val inList = tasks.detail(taskId)?.attachments?.firstOrNull { it.id == id }
        val attachment = inList ?: commentAttachment(id)
        val commentBefore = attachment?.commentId?.let(::comment)
        tasks.editAttachments(taskId) { list -> list.filterNot { it.id == id } }
        if (commentBefore != null) {
            val left = commentBefore.attachments.filterNot { it.id == id }
            _state.update { s ->
                s.copy(
                    stories = if (left.isEmpty() && commentBefore.body.isBlank()) {
                        s.stories.filterNot { it.key == commentBefore.key }
                    } else {
                        s.stories.map {
                            if (it.key ==
                                commentBefore.key
                            ) {
                                commentBefore.copy(attachments = left)
                            } else {
                                it
                            }
                        }
                    }
                )
            }
        }
        try {
            api.deleteAttachment(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.asApiError()
            if (error.code == ApiError.Code.NOT_FOUND) return // already gone: stays gone
            // Put it back where it was.
            if (inList != null) tasks.editAttachments(taskId) { list -> list + inList }
            if (commentBefore != null) restore(commentBefore)
            fail(error)
        }
    }

    /** Updates the task's list and the copy in its comment. */
    suspend fun refreshLinkPreview(id: String) {
        attempt(onError = ::fail) {
            val refreshed = slots.run { api.refreshLinkPreview(id) }
            tasks.editAttachments(taskId) { list -> list.map { if (it.id == id) refreshed else it } }
            _state.update { s ->
                s.copy(
                    stories = s.stories.map { story ->
                        if (story is Comment && story.attachments.any { it.id == id }) {
                            story.copy(attachments = story.attachments.map { if (it.id == id) refreshed else it })
                        } else {
                            story
                        }
                    }
                )
            }
        }
    }

    /** A send's outcome was unknown and it turned out to have landed: show the comment and its attachments. */
    private suspend fun reloadAfterUncertainSend() {
        attempt(onError = ::fail) {
            val stories = api.stories(taskId)
            tasks.upsert(api.task(taskId))
            // Merged by key: a comment a concurrent send appended meanwhile stays until the next load.
            _state.update { s ->
                val loaded = stories.map { it.key }.toSet()
                s.copy(stories = (stories + s.stories.filterNot { it.key in loaded }).sortedBy { it.createdAt })
            }
        }
    }

    /** A finished task-level upload or link (also called by the scope when an upload lands after leaving the task). */
    internal fun attachmentAdded(attachment: Attachment) {
        if (attachment.taskId != taskId) return
        tasks.editAttachments(taskId, bumpedAt = attachment.createdAt) { list ->
            list.filterNot { it.id == attachment.id } + attachment
        }
    }

    private fun comment(id: String): Comment? = _state.value.stories.firstOrNull {
        it is Comment && it.id == id
    } as? Comment

    private fun commentAttachment(id: String): Attachment? =
        _state.value.stories.filterIsInstance<Comment>().flatMap { it.attachments }.firstOrNull { it.id == id }

    /** Puts a comment back as it was (replacing the edited copy, or reinserting it by time). */
    private fun restore(comment: Comment) = _state.update { s ->
        if (s.stories.any { it.key == comment.key }) {
            s.copy(stories = s.stories.map { if (it.key == comment.key) comment else it })
        } else {
            s.copy(stories = (s.stories + comment).sortedBy { it.createdAt })
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
        _state.update {
            it.copy(
                task = task,
                detail = detail,
                canEdit = canEdit,
                isFollowing = isFollowing,
                attachments = detail?.attachments.orEmpty(),
                uploads = uploadStore.uploads(taskId),
                canAttach = canEdit
            )
        }
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
    private val runner: UploadRunner,
    /** Reloads the stories and the task (after an uncertain send turned out to have landed). */
    private val onReload: suspend () -> Unit = {},
    private val onSent: (Comment) -> Unit
) {
    data class State(
        val mentionQuery: String? = null,
        val isSending: Boolean = false,
        val lastError: ApiError? = null,
        /** Draft attachment chips, in the order added. Finished ones are drafts on the server until sent. */
        val attachments: List<AttachmentUpload> = emptyList(),
        /** The chips being posted right now: their remove button is disabled and [remove] refuses them. */
        val sendingIds: Set<String> = emptySet()
    ) {
        /** A chip is queued, uploading or finishing (Send stays disabled). */
        val isUploading: Boolean get() = attachments.any { it.isActive }

        /** Fewer than 20 chips, not counting failed ones (links in flight count). */
        val hasRoomForAnother: Boolean get() = attachments.count { !it.isFailed } < AttachmentLimits.MAX_PER_COMMENT
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The "@word" ending at the cursor → mentionQuery → user search. */
    fun draftChanged(text: String, cursor: Int) {
        val query = mentionQuery(text, cursor)
        if (query == _state.value.mentionQuery) return // typing elsewhere in the text: no new search
        _state.update { it.copy(mentionQuery = query) }
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

    /**
     * Text or at least one finished chip, nothing queued/uploading/finishing, not sending. Failed chips don't block
     * (a successful send drops them, as on the web).
     */
    fun canSend(text: String): Boolean {
        val s = _state.value
        return (text.isNotBlank() || s.attachments.any { it.isFinished }) && !s.isUploading && !s.isSending
    }

    /**
     * True when sent (the comment is appended to the stories); the UI then clears its text if unchanged. Posts the
     * finished chips' ids as attachmentIds; on success drops those and the chips that had failed; chips added
     * during the send stay for the next comment.
     */
    suspend fun send(body: String): Boolean {
        if (!canSend(body)) return false
        // A previous send failed without a definite answer: its chips may already be on a comment.
        if (uncertain.isNotEmpty()) {
            _state.update { it.copy(isSending = true) }
            val landed = try {
                settleUncertain()
            } catch (e: ApiError) {
                _state.update { it.copy(lastError = e) }
                return false
            } finally {
                _state.update { it.copy(isSending = false) }
            }
            if (landed && body.trim() == uncertainBody) {
                // That send did land (the answer was lost): don't post it again.
                uncertainBody = null
                _state.update { it.copy(lastError = null, mentionQuery = null) }
                return true
            }
            if (!canSend(body)) return false
        }
        val chips = _state.value.attachments
        val posted = chips.mapNotNull { chip -> chip.attachment?.let { chip.id to it.id } }
        val failedAtSend = chips.filter { it.isFailed }.map { it.id }.toSet()
        _state.update { it.copy(isSending = true, sendingIds = posted.map { p -> p.first }.toSet()) }
        try {
            val comment = try {
                api.addComment(taskId, body.trim(), posted.map { it.second })
            } catch (e: CancellationException) {
                // The caller went away mid-send: the BE may have committed, so these chips are uncertain too (chips
                // discarded meanwhile are left alone; the server sweeps them if they never landed).
                uncertain += posted.map { it.first }.filter { id -> _state.value.attachments.any { it.id == id } }
                uncertainBody = body.trim()
                discardedWhileSending -= posted.map { it.first }.toSet()
                throw e
            } catch (e: Exception) {
                val error = e.asApiError()
                _state.update { it.copy(lastError = error) }
                deleteOrphans(posted, error)
                if (error.status?.let { it in 400..499 } != true) {
                    // A transport error or a 5xx: the BE may have committed. Ask before any delete or re-send.
                    uncertain += posted.map { it.first }.filter { id -> _state.value.attachments.any { it.id == id } }
                    uncertainBody = body.trim()
                }
                return false
            }
            val gone = posted.map { it.first }.toSet() + failedAtSend
            discardedWhileSending -= posted.map { it.first }.toSet()
            chips.filter { it.id in gone }.forEach { it.cleanUp() }
            _state.update { s ->
                s.copy(lastError = null, mentionQuery = null, attachments = s.attachments.filterNot { it.id in gone })
            }
            onSent(comment)
            return true
        } finally {
            _state.update { it.copy(isSending = false, sendingIds = emptySet()) }
        }
    }

    /**
     * Chips discarded while they were being posted: if the comment didn't take them, their drafts go now. Only a
     * 4xx says so for sure; after a transport failure or a 5xx (a proxy's 502/504 once the BE committed) the
     * comment may have landed, so ask first.
     */
    private suspend fun deleteOrphans(posted: List<Pair<String, String>>, error: ApiError) {
        val orphaned = posted.filter { it.first in discardedWhileSending }
        discardedWhileSending -= orphaned.map { it.first }.toSet()
        val rejected = error.status?.let { it in 400..499 } ?: false
        for ((_, attachmentId) in orphaned) {
            if (!rejected) {
                val current = try {
                    api.attachment(attachmentId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (current.commentId != null || !current.draft) continue
            }
            bestEffortDelete(attachmentId)
        }
    }

    /** Chips a failed send may have posted (transport error or 5xx): checked before any delete or re-send. */
    private val uncertain = mutableSetOf<String>()
    private var uncertainBody: String? = null

    /**
     * Asks the server about every uncertain chip still here. One already on a comment (or no longer a draft, or gone)
     * leaves without a delete; one still a draft becomes certain again. True when any had landed (the stories are
     * then reloaded). Throws the ApiError when the server can't say (the chips stay uncertain).
     */
    private suspend fun settleUncertain(): Boolean {
        var landed = false
        for (chip in _state.value.attachments.filter { it.id in uncertain }) {
            val attachmentId = chip.attachment?.id ?: continue
            when (draftStatus(attachmentId)) {
                DraftStatus.DRAFT -> uncertain -= chip.id
                DraftStatus.POSTED -> {
                    drop(chip)
                    landed = true
                }
                // Swept or deleted: it leaves, but it proves nothing landed (the text is posted normally).
                DraftStatus.GONE -> drop(chip)
            }
        }
        if (landed) onReload()
        return landed
    }

    private enum class DraftStatus { DRAFT, POSTED, GONE }

    /** Still a draft, posted (on a comment, or no longer a draft), or gone (404). Throws when the server can't say. */
    private suspend fun draftStatus(attachmentId: String): DraftStatus = try {
        val current = api.attachment(attachmentId)
        if (current.draft && current.commentId == null) DraftStatus.DRAFT else DraftStatus.POSTED
    } catch (e: ApiError) {
        if (e.code == ApiError.Code.NOT_FOUND) DraftStatus.GONE else throw e
    }

    private fun drop(chip: AttachmentUpload) {
        uncertain -= chip.id
        chip.cleanUp()
        _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == chip.id }) }
    }

    /**
     * Deletes a chip's draft, unless it may already be on a comment (an uncertain send): then only if the server
     * says it is still a draft. When the server can't say, nothing is deleted (it sweeps unposted drafts after 24 h).
     */
    private suspend fun deleteDraft(chip: AttachmentUpload) {
        val attachmentId = chip.attachment?.id ?: return
        if (chip.id in uncertain) {
            uncertain -= chip.id
            val status = try {
                draftStatus(attachmentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            when (status) {
                DraftStatus.DRAFT -> Unit
                DraftStatus.POSTED -> return onReload()
                DraftStatus.GONE -> return // nothing left to delete
            }
        }
        bestEffortDelete(attachmentId)
    }

    /** Chips discarded while being posted: deleted only if that send fails. */
    private val discardedWhileSending = mutableSetOf<String>()

    /** Finishing chips that were discarded: their draft is deleted when it lands. */
    private val discardedWhileFinishing = mutableSetOf<String>()

    /**
     * Uploads a file as a draft (`?draft=true`). False when the comment already has 20 attachments (the UI says so).
     * Over 100 MB → a failed chip at once, without a request. [UploadSource.delete] runs once the chip goes.
     */
    fun attach(source: UploadSource): Boolean {
        if (!_state.value.hasRoomForAnother) return false
        val error = preflight(source)
        val chip = AttachmentUpload(
            taskId = taskId,
            source = AttachmentUpload.Source.File(source),
            state = error?.let { AttachmentUpload.State.Failed(it) } ?: AttachmentUpload.State.Queued,
            size = source.size
        )
        _state.update { it.copy(attachments = it.attachments + chip) }
        if (error == null) run(chip)
        return true
    }

    /**
     * Adds a draft link: the chip shows Uploading (no total) while it waits for a slot and the server fetches the
     * preview. A failure comes back for the popover and leaves no chip.
     */
    suspend fun attachLink(url: String, title: String?): LinkResult {
        if (!_state.value.hasRoomForAnother) return LinkResult.NoRoom
        linkError(url)?.let { return LinkResult.Failed(it) }
        val chip = AttachmentUpload(
            taskId = taskId,
            source = AttachmentUpload.Source.Link(url.trim(), title),
            state = AttachmentUpload.State.Uploading(TransferProgress(0, null))
        )
        _state.update { it.copy(attachments = it.attachments + chip) }
        return try {
            val attachment = runner.slots.run { api.addLink(taskId, url.trim(), title, draft = true) }
            if (_state.value.attachments.none { it.id == chip.id }) {
                bestEffortDelete(attachment.id) // removed (or discarded) while it was being added
            } else {
                set(chip.id, AttachmentUpload.State.Finished(attachment))
            }
            LinkResult.Added
        } catch (e: CancellationException) {
            _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == chip.id }) }
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == chip.id }) }
            LinkResult.Failed(e.asApiError())
        }
    }

    /** Retries a failed file chip. */
    fun retry(id: String) {
        val chip = _state.value.attachments.firstOrNull { it.id == id }?.takeIf { it.isFailed } ?: return
        val source = (chip.source as? AttachmentUpload.Source.File)?.source ?: return
        preflight(source)?.let { return set(id, AttachmentUpload.State.Failed(it)) }
        set(id, AttachmentUpload.State.Queued)
        run(chip.copy(state = AttachmentUpload.State.Queued))
    }

    /**
     * Removes a chip: cancels its upload, or deletes the uploaded draft (best effort; the server sweeps the rest).
     * False while it is finishing or being posted.
     */
    suspend fun remove(id: String): Boolean {
        val s = _state.value
        val chip = s.attachments.firstOrNull { it.id == id } ?: return false
        if (chip.isFinishing || id in s.sendingIds) return false
        _state.update { st -> st.copy(attachments = st.attachments.filterNot { it.id == id }) }
        runner.cancel(id)
        chip.cleanUp()
        deleteDraft(chip)
        return true
    }

    /**
     * Removes every chip (the UI calls this when the user leaves the task); finishing ones are deleted once they
     * land, and chips being posted are left to the send (deleted only if it fails). The text stays the UI's.
     */
    suspend fun discard() {
        val s = _state.value
        val chips = s.attachments
        _state.update { it.copy(attachments = emptyList()) }
        val finishing = chips.filter { it.isFinishing }.map { it.id }.toSet()
        runner.cancelAll(kept = finishing)
        discardedWhileFinishing += finishing
        discardedWhileSending += chips.map { it.id }.filter { it in s.sendingIds }
        chips.forEach { it.cleanUp() }
        for (chip in chips) {
            if (chip.id !in s.sendingIds) deleteDraft(chip)
        }
    }

    /** Stops uploads without deleting anything (the scope closed; the server sweeps the drafts). */
    internal fun cancelUploads() {
        runner.cancelAll()
        _state.value.attachments.forEach { it.cleanUp() }
        _state.update { it.copy(attachments = emptyList()) }
    }

    private fun run(chip: AttachmentUpload) {
        runner.run(chip, draft = true, api = api, update = ::set)
    }

    private fun set(id: String, state: AttachmentUpload.State) {
        val chip = _state.value.attachments.firstOrNull { it.id == id }
        if (chip == null) {
            if (discardedWhileFinishing.remove(id) && state is AttachmentUpload.State.Finished) {
                runner.launchDelete { bestEffortDelete(state.attachment.id) }
            }
            return
        }
        if (!chip.accepts(state)) return
        _state.update { s ->
            s.copy(
                attachments = s.attachments.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(state = state)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private suspend fun bestEffortDelete(attachmentId: String) {
        try {
            api.deleteAttachment(attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the server sweeps unposted drafts after 24 h.
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

    fun clearError() = _state.update { it.copy(lastError = null) }

    /** The UI only sends the query (never binds a TextField to state.query). */
    private fun searchFailed(error: ApiError) = _state.update { it.copy(lastError = error, isLoading = false) }

    fun setQuery(query: String) {
        generation += 1
        val current = generation
        val q = query.trim()
        _state.update { it.copy(query = query, isLoading = true) }
        debouncer.schedule {
            // A newer query is on its way when the generation moved on: drop this answer.
            val stale = { current != generation }
            val found = attempt(onError = { e -> if (!stale()) searchFailed(e) }) { api.users(q.ifEmpty { null }) }
            if (found != null && !stale()) {
                _state.update { it.copy(results = found, lastError = null, isLoading = false) }
            }
        }
    }
}
