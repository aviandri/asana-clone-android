package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentCache
import com.mekari.project.core.AttachmentLimits
import com.mekari.project.core.AttachmentLinks
import com.mekari.project.core.FieldIssue
import com.mekari.project.core.MekariApi
import com.mekari.project.core.TransferProgress
import com.mekari.project.core.UploadSource
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One upload: a file or a link on its way to a task (task-level) or into the comment composer (a draft). */
data class AttachmentUpload(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val source: Source,
    val state: State = State.Queued,
    /** FILE size when known. */
    val size: Long? = null
) {
    sealed interface Source {
        data class File(val source: UploadSource) : Source
        data class Link(val url: String, val title: String?) : Source
    }

    sealed interface State {
        /** Waiting for one of the [AttachmentLimits.MAX_CONCURRENT_UPLOADS] slots. */
        data object Queued : State

        /** Links: total null while the server fetches the preview. */
        data class Uploading(val progress: TransferProgress) : State

        /** The whole body is sent and the server is storing it: it lands even if cancelled, so cancel/remove refuse. */
        data object Finishing : State

        /** Composer chips only; finished task-level uploads leave the list. */
        data class Finished(val attachment: Attachment) : State

        /** Retry or remove. The server's message is kept (429, 507); the local 100 MB check is PAYLOAD_TOO_LARGE, status null. */
        data class Failed(val error: ApiError) : State
    }

    /** The file name, or the link's title, else its URL. */
    val name: String
        get() = when (source) {
            is Source.File -> source.source.name
            is Source.Link -> source.title?.takeIf { it.isNotEmpty() } ?: source.url
        }

    val isFinished: Boolean get() = state is State.Finished
    val isFailed: Boolean get() = state is State.Failed
    val isFinishing: Boolean get() = state == State.Finishing

    /** Queued, uploading or finishing. */
    val isActive: Boolean get() = state == State.Queued || state is State.Uploading || state == State.Finishing

    val attachment: Attachment? get() = (state as? State.Finished)?.attachment

    /**
     * Progress reports arrive on their own (an I/O thread, then a hop), so one can land after the outcome: it never
     * overwrites a finished or failed state, and "uploading" never takes "finishing" back.
     */
    internal fun accepts(next: State): Boolean = when (next) {
        is State.Uploading -> isActive && !isFinishing
        State.Finishing -> isActive
        else -> true
    }

    /** The upload leaves its list: [UploadSource.delete] (once; the source decides whether a file goes). */
    internal fun cleanUp() {
        (source as? Source.File)?.source?.let { runCatching { it.delete() } }
    }
}

/** What adding a link gave: added, no room (20 chips), or an error for the popover (local http(s) check first). */
sealed interface LinkResult {
    data object Added : LinkResult
    data object NoRoom : LinkResult
    data class Failed(val error: ApiError) : LinkResult
}

/**
 * At most [AttachmentLimits.MAX_CONCURRENT_UPLOADS] uploads, links and preview refreshes at once across the app
 * (the BE counts them all, A12), first come first served.
 */
class UploadSlots internal constructor(limit: Int = AttachmentLimits.MAX_CONCURRENT_UPLOADS) {
    private val semaphore = Semaphore(limit)

    /** How long a 429 waits before its one automatic retry (the server frees a cancelled upload's slot a little later). */
    internal var rateLimitRetryDelay: Duration = 1500.milliseconds

    /**
     * Runs [body] holding a slot, retrying a 429 once after [rateLimitRetryDelay], but only while [mayRetry] (an
     * upload whose body was fully sent isn't re-sent: that 429 is the server's draft quota).
     */
    internal suspend fun <T> run(mayRetry: () -> Boolean = { true }, body: suspend () -> T): T = semaphore.withPermit {
        try {
            body()
        } catch (e: ApiError) {
            if (e.code != ApiError.Code.RATE_LIMITED || !mayRetry()) throw e
            delay(rateLimitRetryDelay)
            body()
        }
    }

    internal val available: Int get() = semaphore.availablePermits
}

/** Runs uploads for a list its owner keeps: slots, progress, cancel, retry. Main-confined. */
internal class UploadRunner(val slots: UploadSlots, private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()

    /** Starts [upload], reporting each state change through [update] (on the caller's dispatcher). */
    fun run(
        upload: AttachmentUpload,
        draft: Boolean,
        api: MekariApi,
        update: (String, AttachmentUpload.State) -> Unit
    ) {
        val id = upload.id
        jobs.remove(id)?.cancel()
        val job = scope.launch {
            try {
                val bodySent = AtomicBoolean(false)
                val attachment = slots.run(mayRetry = { !bodySent.get() }) {
                    when (val source = upload.source) {
                        is AttachmentUpload.Source.File -> {
                            update(id, AttachmentUpload.State.Uploading(TransferProgress(0, upload.size)))
                            api.uploadAttachment(upload.taskId, source.source, draft) { progress ->
                                val sentAll = progress.total?.let { progress.completed >= it } ?: false
                                if (sentAll) bodySent.set(true)
                                val state = if (sentAll) {
                                    AttachmentUpload.State.Finishing
                                } else {
                                    AttachmentUpload.State.Uploading(
                                        progress
                                    )
                                }
                                scope.launch { update(id, state) }
                            }
                        }
                        is AttachmentUpload.Source.Link -> {
                            update(id, AttachmentUpload.State.Uploading(TransferProgress(0, null)))
                            api.addLink(upload.taskId, source.url, source.title, draft)
                        }
                    }
                }
                update(id, AttachmentUpload.State.Finished(attachment))
            } catch (e: CancellationException) {
                throw e // cancelled by the owner (remove, cancel, sign-out): it already dropped the upload
            } catch (e: Exception) {
                update(id, AttachmentUpload.State.Failed(e.asApiError()))
            } finally {
                if (jobs[id] === coroutineContext[Job]) jobs.remove(id)
            }
        }
        jobs[id] = job
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    /** Runs a clean-up (deleting a draft that landed after its chip went) in the runner's scope. */
    fun launchDelete(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Cancels every upload but [kept] (finishing ones, left to land). */
    fun cancelAll(kept: Set<String> = emptySet()) {
        jobs.keys.filter { it !in kept }.forEach { cancel(it) }
    }
}

/** Checks a file before any request: at most 100 MB when its size is known (the stream is checked again while sent). */
internal fun preflight(source: UploadSource): ApiError? {
    val size = source.size ?: return null
    return if (size > AttachmentLimits.MAX_UPLOAD_BYTES) AttachmentLimits.tooLarge() else null
}

/** A link's URL checked like the BE does (absolute http(s), ≤ 2048 chars, no credentials), or the error. */
internal fun linkError(text: String): ApiError? {
    if (AttachmentLinks.isValidLink(text)) return null
    val message = "Enter a link that starts with http:// or https://"
    return ApiError(ApiError.Code.VALIDATION, null, message, listOf(FieldIssue(listOf("url"), message)))
}

/**
 * Task-level uploads for the whole session, keyed by task, so they survive leaving the task (navigating back,
 * rotation) and show again when it reopens. Cleared on sign-out; a lost workspace's uploads are cancelled.
 * Finished uploads leave the list and go to [onFinished] (the current scope inserts them). Main-confined; jobs run
 * in the app scope.
 */
class AttachmentUploads internal constructor(
    slots: UploadSlots,
    appScope: CoroutineScope,
    /** The session's current client (null when signed out). */
    internal var api: () -> MekariApi?
) {
    private val runner = UploadRunner(slots, appScope)
    private val _state = MutableStateFlow<List<AttachmentUpload>>(emptyList())

    /** Every queued, uploading, finishing and failed task-level upload, oldest first. */
    val state: StateFlow<List<AttachmentUpload>> = _state.asStateFlow()

    /** Which workspace each upload's task is in (for lost access). */
    private val workspaces = mutableMapOf<String, String>()
    internal var onFinished: (Attachment) -> Unit = {}
    internal val changes = Listeners()

    fun uploads(taskId: String): List<AttachmentUpload> = _state.value.filter { it.taskId == taskId }

    /** Starts a task-level (non-draft) upload. A file over 100 MB shows as failed at once, without a request. */
    internal fun start(taskId: String, workspaceId: String, source: UploadSource) {
        val error = preflight(source)
        val upload = AttachmentUpload(
            taskId = taskId,
            source = AttachmentUpload.Source.File(source),
            state = error?.let { AttachmentUpload.State.Failed(it) } ?: AttachmentUpload.State.Queued,
            size = source.size
        )
        workspaces[upload.id] = workspaceId
        publish(_state.value + upload)
        if (error == null) run(upload)
    }

    fun retry(id: String) {
        val upload = _state.value.firstOrNull { it.id == id }?.takeIf { it.isFailed } ?: return
        val source = (upload.source as? AttachmentUpload.Source.File)?.source
        val error = source?.let(::preflight)
        if (error != null) return set(id, AttachmentUpload.State.Failed(error))
        set(id, AttachmentUpload.State.Queued)
        run(upload.copy(state = AttachmentUpload.State.Queued))
    }

    /** Cancels a queued or uploading upload, or dismisses a failed one. False while it is finishing (it lands anyway). */
    fun cancel(id: String): Boolean {
        val upload = _state.value.firstOrNull { it.id == id } ?: return false
        if (upload.isFinishing) return false
        drop(upload)
        return true
    }

    /** Sign-out: everything stops, finishing ones too (their result has nowhere to go). */
    internal fun cancelAll() = _state.value.forEach(::drop)

    internal fun cancelWorkspace(workspaceId: String) =
        _state.value.filter { workspaces[it.id] == workspaceId }.forEach(::drop)

    private fun drop(upload: AttachmentUpload) {
        runner.cancel(upload.id)
        workspaces.remove(upload.id)
        upload.cleanUp()
        publish(_state.value.filterNot { it.id == upload.id })
    }

    private fun run(upload: AttachmentUpload) {
        val api = api() ?: return set(
            upload.id,
            AttachmentUpload.State.Failed(ApiError(ApiError.Code.UNAUTHORIZED, null, "Sign in to upload"))
        )
        runner.run(upload, draft = false, api = api, update = ::set)
    }

    private fun set(id: String, state: AttachmentUpload.State) {
        val upload = _state.value.firstOrNull { it.id == id } ?: return
        if (!upload.accepts(state)) return
        if (state is AttachmentUpload.State.Finished) {
            workspaces.remove(id)
            upload.cleanUp()
            publish(_state.value.filterNot { it.id == id })
            onFinished(state.attachment)
            return
        }
        publish(_state.value.map { if (it.id == id) it.copy(state = state) else it })
    }

    private fun publish(list: List<AttachmentUpload>) {
        _state.value = list
        changes.fire()
    }
}

/** Downloads for the current workspace through the session's [AttachmentCache]. Main-confined. */
class AttachmentFiles internal constructor(
    private val api: MekariApi,
    private val cache: AttachmentCache,
    /** The workspace scope's (main) coroutine scope: progress reports hop back onto it. */
    private val scope: CoroutineScope
) {
    data class State(
        /** Attachment id → progress while it downloads. */
        val downloads: Map<String, TransferProgress> = emptyMap(),
        val lastError: ApiError? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The local file (Open with, Share, Save, in-app preview), or null on failure (`lastError`) or cancellation.
     * With [revalidate] (the default) a cached copy costs one round trip (`If-None-Match`, 304); without it a cached
     * copy comes back at once (swiping through images) and only a missing one is downloaded.
     */
    suspend fun file(attachment: Attachment, revalidate: Boolean = true): File? {
        if (!revalidate) cache.cachedFile(attachment.id)?.let { return it }
        val id = attachment.id
        _state.update { it.copy(downloads = it.downloads + (id to TransferProgress(0, attachment.size))) }
        return try {
            val file = cache.file(attachment, api) { progress ->
                // Hop to main; a report after the download ended is dropped.
                scope.launch {
                    _state.update { s ->
                        if (id in
                            s.downloads
                        ) {
                            s.copy(downloads = s.downloads + (id to progress))
                        } else {
                            s
                        }
                    }
                }
            }
            _state.update { it.copy(lastError = null) }
            file
        } catch (_: CancellationException) {
            null
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.asApiError()) }
            null
        } finally {
            _state.update { it.copy(downloads = it.downloads - id) }
        }
    }

    /** Stops a download for every caller waiting on it (the viewer's Cancel); they get null, without an error. */
    suspend fun cancelDownload(attachmentId: String) {
        _state.update { it.copy(downloads = it.downloads - attachmentId) }
        cache.cancelDownload(attachmentId)
    }

    /**
     * Small image bytes for a server-given path (a thumbnail, a link icon or image) through this scope's API:
     * authenticated, path-checked, refused once the scope closes. Null on any failure, without `lastError` (a missing
     * thumbnail just shows the type icon). A closed scope's request is a CancellationException, as everywhere. For the
     * app's Coil fetcher.
     */
    suspend fun imageData(path: String): ByteArray? = try {
        api.attachmentData(path)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The downloaded copy if there is one. */
    suspend fun cachedFile(attachment: Attachment): File? = cache.cachedFile(attachment.id)

    fun clearError() = _state.update { it.copy(lastError = null) }
}
