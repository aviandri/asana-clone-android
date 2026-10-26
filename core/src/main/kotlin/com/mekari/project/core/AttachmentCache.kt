package com.mekari.project.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Downloaded attachments on disk (SPEC-ANDROID §8): `<directory>/<attachmentId>/<sanitised name>`, revalidated
 * by ETag, evicted least recently used beyond [capacity]. [wipe] on sign-out and on losing a workspace. The app
 * passes `cacheDir/attachments` (its FileProvider exposes only that path). Thumbnails are Coil's business.
 *
 * Only UUID ids name folders, and nothing outside [directory] is ever deleted (every removal is checked).
 * Main-safe: file work runs on [io].
 */
class AttachmentCache(
    val directory: File,
    private val capacity: Long = 500L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Serializable
    internal data class Entry(val name: String, val etag: String? = null, val size: Long, val lastUsed: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var loaded = false

    /** Downloads run here, so one caller's cancellation doesn't cancel it for the others. */
    private var downloads = CoroutineScope(SupervisorJob() + io)
    private val inFlight = mutableMapOf<String, Deferred<File>>()
    private val waiters = mutableMapOf<String, Int>()
    private val cancelledWaiters = mutableMapOf<String, Int>()

    /** Bumped by [wipe]: a download that started before it never lands in the cache. */
    private var generation = 0

    /**
     * The local copy of a FILE attachment: revalidated with its ETag (304 → the cached file), downloaded
     * otherwise. Any transport failure with a cached copy → the cached copy. Concurrent calls for one id share a
     * download; it is cancelled only when every caller waiting for it is (or by [cancelDownload]).
     */
    suspend fun file(attachment: Attachment, api: MekariApi, progress: (TransferProgress) -> Unit = {}): File {
        val path = attachment.contentPath
        if (attachment.kind != AttachmentKind.FILE || path == null) {
            throw ApiError(ApiError.Code.VALIDATION, null, "Only files can be downloaded")
        }
        // The id names a folder: anything but a UUID (e.g. "../../files") could point outside the cache.
        if (!isSafeId(attachment.id)) throw ApiError(ApiError.Code.VALIDATION, null, "Not an attachment")
        val id = attachment.id
        val task = mutex.withLock {
            loadIndex()
            val running = inFlight[id] ?: downloads.async {
                fetch(id, attachment.name ?: "file", path, api, progress)
            }.also { inFlight[id] = it }
            waiters[id] = (waiters[id] ?: 0) + 1
            running
        }
        try {
            return task.await()
        } catch (e: CancellationException) {
            // This caller went away (or the download was cancelled): stop it once nobody waits any more.
            mutex.withLock {
                if (inFlight[id] === task) {
                    cancelledWaiters[id] = (cancelledWaiters[id] ?: 0) + 1
                    if ((cancelledWaiters[id] ?: 0) >= (waiters[id] ?: 0)) task.cancel()
                }
            }
            throw e
        } finally {
            mutex.withLock {
                val left = (waiters[id] ?: 1) - 1
                if (left <= 0) {
                    waiters.remove(id)
                    cancelledWaiters.remove(id)
                    if (inFlight[id] === task) inFlight.remove(id)
                } else {
                    waiters[id] = left
                }
            }
        }
    }

    private suspend fun fetch(
        id: String,
        name: String,
        path: String,
        api: MekariApi,
        progress: (TransferProgress) -> Unit
    ): File {
        val (started, cached) = mutex.withLock {
            generation to entries[id]?.takeIf { File(folder(id), it.name).isFile }
        }
        val folder = folder(id)
        val destination = File(folder, AttachmentLinks.safeFileName(name))
        withContext(io) { folder.mkdirs() }
        val outcome = try {
            val etag = cached?.etag?.takeIf { cached.name == destination.name }
            api.download(path, destination, etag, progress)
        } catch (e: ApiError) {
            if (e.code != ApiError.Code.TRANSPORT || cached == null) throw e
            return mutex.withLock { if (started == generation) touch(id, cached) else throw e }
        }
        return mutex.withLock {
            if (started != generation) {
                // Wiped while downloading (sign-out, lost access): the bytes must not stay.
                remove(folder)
                throw CancellationException("attachment cache wiped")
            }
            when (outcome) {
                DownloadOutcome.NotModified -> touch(id, cached ?: throw ApiError.decoding("304 without a cached copy"))
                is DownloadOutcome.Downloaded -> {
                    if (cached != null && cached.name != destination.name) remove(File(folder, cached.name))
                    entries[id] = Entry(destination.name, outcome.etag, destination.length(), now())
                    evict(keep = id)
                    saveIndex()
                    destination
                }
            }
        }
    }

    /** The downloaded copy, if any (Open with / Share are offered only then). */
    suspend fun cachedFile(attachmentId: String): File? {
        if (!isSafeId(attachmentId)) return null
        return mutex.withLock {
            loadIndex()
            entries[attachmentId]?.let { File(folder(attachmentId), it.name) }?.takeIf { it.isFile }
        }
    }

    /** Cancels the download of [attachmentId] for every caller (they get a CancellationException). */
    suspend fun cancelDownload(attachmentId: String) = mutex.withLock {
        inFlight.remove(attachmentId)?.cancel()
        cancelledWaiters.remove(attachmentId)
        Unit
    }

    suspend fun diskUsage(): Long = mutex.withLock {
        loadIndex()
        entries.values.sumOf { it.size }
    }

    /** Removes every downloaded file. Downloads in flight finish into nothing. */
    suspend fun wipe() = mutex.withLock {
        generation += 1
        downloads.cancel()
        downloads = CoroutineScope(SupervisorJob() + io)
        inFlight.clear()
        cancelledWaiters.clear()
        entries.clear()
        loaded = true
        withContext(io) { directory.deleteRecursively() }
        Unit
    }

    private fun folder(id: String) = File(directory, id)

    private fun touch(id: String, entry: Entry): File {
        entries[id] = entry.copy(lastUsed = now())
        saveIndex()
        return File(folder(id), entry.name)
    }

    private fun evict(keep: String) {
        var total = entries.values.sumOf { it.size }
        for ((id, entry) in entries.entries.sortedBy { it.value.lastUsed }) {
            if (total <= capacity) break
            if (id == keep || id in inFlight) continue
            remove(folder(id))
            entries.remove(id)
            total -= entry.size
        }
    }

    private val indexFile get() = File(directory, "index.json")

    /** Folders the index doesn't know, entries that don't name a UUID folder and a plain file, and `.part` leftovers go. */
    private fun loadIndex() {
        if (loaded) return
        loaded = true
        runCatching {
            MekariJson.decodeFromString(INDEX, indexFile.readText())
        }.getOrNull()?.let {
            entries.putAll(
                it.filter { (id, e) ->
                    isSafeId(id) &&
                        e.name == AttachmentLinks.safeFileName(e.name)
                }
            )
        }
        directory.listFiles().orEmpty().forEach { if (it.name != "index.json" && it.name !in entries) remove(it) }
        for (id in entries.keys) {
            folder(id).listFiles().orEmpty().forEach { if (it.name.endsWith(".part")) remove(it) }
        }
    }

    private fun saveIndex() {
        directory.mkdirs()
        val temp = File(directory, "index.json.tmp")
        runCatching {
            temp.writeText(MekariJson.encodeToString(INDEX, entries.toMap()))
            if (!temp.renameTo(indexFile)) {
                indexFile.delete()
                temp.renameTo(indexFile)
            }
        }
    }

    /** Whether [file] (canonical) is strictly inside [directory]. */
    internal fun isInside(file: File): Boolean {
        val root = directory.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return file.canonicalPath.startsWith(root)
    }

    /** Removes [file] only if it sits inside the cache directory. */
    private fun remove(file: File) {
        if (isInside(file)) file.deleteRecursively()
    }

    companion object {
        /** A cache in a fresh temp folder (previews, UI tests, unit tests): never the app's real cache. */
        fun scratch(): AttachmentCache = AttachmentCache(
            File(System.getProperty("java.io.tmpdir"), "mekari-attachments-${java.util.UUID.randomUUID()}")
        )

        internal val INDEX = MapSerializer(String.serializer(), Entry.serializer())

        /** Attachment ids are UUIDs; only those name folders here. */
        internal fun isSafeId(id: String): Boolean =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(id)
    }
}
