package com.mekari.project.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * The upload's file part, streamed from an [UploadSource] in 64 KiB pieces (never fully in memory). One-shot:
 * OkHttp never replays it. Stops with an IOException (and sets [tooLarge]) once more than the limit was read.
 */
internal class SourceBody(
    private val source: UploadSource,
    private val size: Long?,
    private val progress: (TransferProgress) -> Unit
) : RequestBody() {
    @Volatile var tooLarge = false
        private set

    override fun contentType(): MediaType = OCTET_STREAM
    override fun contentLength(): Long = size ?: -1
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        source.open().use { input ->
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            progress(TransferProgress(0, size))
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sent += read
                if (sent > AttachmentLimits.MAX_UPLOAD_BYTES) {
                    tooLarge = true
                    throw IOException("upload larger than ${AttachmentLimits.MAX_UPLOAD_BYTES} bytes")
                }
                sink.write(buffer, 0, read)
                progress(TransferProgress(sent, size))
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

/**
 * Awaits a GET whose 200 body streams into a temp file next to [destination], moved into place only once
 * complete. Any other status comes back with its (small) body in memory and leaves [destination] alone.
 * Cancellation cancels the call; the temp file is always removed on failure.
 */
internal suspend fun Call.awaitDownload(destination: File, progress: (TransferProgress) -> Unit): LiveApi.Raw =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { streamed(it, destination, progress) }
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (continuation.isActive) continuation.resume(raw)
                }
            }
        )
    }

private fun streamed(response: Response, destination: File, progress: (TransferProgress) -> Unit): LiveApi.Raw {
    val body = response.body
    if (response.code != 200 || body == null) {
        return LiveApi.Raw(response.code, response.headers, body?.string().orEmpty().take(1 shl 20))
    }
    val folder = destination.absoluteFile.parentFile ?: throw IOException("no folder for $destination")
    folder.mkdirs()
    val temp = File(folder, ".${UUID.randomUUID()}.part")
    try {
        val total = body.contentLength().takeIf { it >= 0 }
        temp.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    received += read
                    progress(TransferProgress(received, total))
                }
            }
        }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return LiveApi.Raw(response.code, response.headers, "")
    } finally {
        temp.delete()
    }
}

/** Awaits a GET and returns its body as bytes (thumbnails, link icons and images). */
internal suspend fun Call.awaitBytes(): Pair<LiveApi.Raw, ByteArray> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        LiveApi.Raw(it.code, it.headers, if (it.code in 200..299) "" else String(bytes)) to bytes
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }
    )
}
