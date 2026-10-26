@file:UseSerializers(InstantSerializer::class)

package com.mekari.project.core

import java.io.File
import java.io.InputStream
import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Attachments & links (BE docs/SPEC.md §13, SPEC-ANDROID §8).

@JvmInline
@Serializable(with = AttachmentKind.Serializer::class)
value class AttachmentKind(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<AttachmentKind>("AttachmentKind", ::AttachmentKind)

    companion object {
        val FILE = AttachmentKind("FILE")
        val LINK = AttachmentKind("LINK")
        val known = listOf(FILE, LINK)
    }
}

@JvmInline
@Serializable(with = LinkPreviewStatus.Serializer::class)
value class LinkPreviewStatus(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<LinkPreviewStatus>("LinkPreviewStatus", ::LinkPreviewStatus)

    companion object {
        val PENDING = LinkPreviewStatus("PENDING")
        val READY = LinkPreviewStatus("READY")
        val FAILED = LinkPreviewStatus("FAILED")
        val known = listOf(PENDING, READY, FAILED)
    }
}

/**
 * A file or link on a task or a comment. The `…Path` fields are server-relative: pass them to the API as
 * they are and never build attachment URLs by hand (A11). Booleans default to false, so an attachment of a
 * kind added later (with its own fields) still decodes.
 */
@Serializable
data class Attachment(
    val id: String,
    val workspaceId: String,
    val taskId: String,
    /** null = task-level. */
    val commentId: String? = null,
    val kind: AttachmentKind,
    val draft: Boolean = false,
    val uploader: UserSummary,
    val createdAt: Instant,
    // FILE (null for LINK)
    val name: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hasThumbnail: Boolean = false,
    // LINK (null for FILE)
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    /** Open string (`google_docs`, `figma`, …); unknown values get the generic badge. */
    val provider: String? = null,
    val previewStatus: LinkPreviewStatus? = null,
    val hasIcon: Boolean = false,
    val hasImage: Boolean = false,
    val contentPath: String? = null,
    val thumbnailPath: String? = null,
    val iconPath: String? = null,
    val imagePath: String? = null
) {
    /** The LINK's url, only when it is an absolute http(s) URL with a host. Anything else is never opened. */
    val linkUrl: String? get() = if (kind == AttachmentKind.LINK) url?.let(AttachmentLinks::openable) else null

    /** The LINK's host (lowercased, without "www."), for the card's site line. */
    val host: String?
        get() = linkUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase()?.removePrefix("www.")

    /** FILE: its name. LINK: its title, else its host, else its url. */
    val displayName: String
        get() = when (kind) {
            AttachmentKind.LINK -> title?.takeIf { it.isNotEmpty() } ?: host ?: url ?: "Link"
            else -> name ?: "file"
        }

    /** A FILE the server identified as an image (`image/…`). */
    val isImage: Boolean get() = kind == AttachmentKind.FILE && mimeType?.lowercase()?.startsWith("image/") == true

    /** `thumbnailPath` with `?size=`, or null when the server has no thumbnail. */
    fun thumbnailPath(size: ThumbnailSize): String? {
        val path = thumbnailPath?.takeIf { hasThumbnail } ?: return null
        return path + (if ('?' in path) "&" else "?") + "size=${size.raw}"
    }
}

enum class ThumbnailSize(val raw: String) {
    /** Fits 240 px. */
    SMALL("small"),

    /** Fits 1600 px. */
    LARGE("large")
}

object AttachmentLimits {
    /** A1: 100 MB. Checked before any upload request is made. */
    const val MAX_UPLOAD_BYTES: Long = 104_857_600

    /** Draft ids on one comment. */
    const val MAX_PER_COMMENT = 20

    /** A12, per user. The client queues the rest instead of collecting 429s. */
    const val MAX_CONCURRENT_UPLOADS = 3

    /** The local pre-check's error: PAYLOAD_TOO_LARGE with no HTTP status. Its message is a fallback; the UI writes its own. */
    fun tooLarge() = ApiError(ApiError.Code.PAYLOAD_TOO_LARGE, null, "Files can be up to 100 MB")
}

/** Bytes moved so far; [total] is null when unknown. */
data class TransferProgress(val completed: Long, val total: Long?) {
    /** 0…1, null when the total is unknown. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (completed.toFloat() / it).coerceIn(0f, 1f) }
}

sealed interface DownloadOutcome {
    /** 304: the copy the ETag named is still current; the destination was not touched. */
    data object NotModified : DownloadOutcome

    data class Downloaded(val etag: String?) : DownloadOutcome
}

/**
 * What an upload streams from. :core has no Android types: the app wraps a `content://` Uri (ContentResolver,
 * `OpenableColumns` for the name and size). [open] is called once per attempt (a retry opens it again).
 */
interface UploadSource {
    /** The file name sent to the server (it sanitises it again). */
    val name: String

    /** Bytes, when known; checked against the 100 MB limit before any request. */
    val size: Long?

    fun open(): InputStream

    /** Deletes the underlying file if it is ours (a camera capture's temp file); called once the upload is done with it. */
    fun delete() {}
}

/** An [UploadSource] over a file (tests, camera captures written to the cache). */
class FileUploadSource(val file: File, override val name: String = file.name, private val owned: Boolean = false) : UploadSource {
    override val size: Long? get() = if (file.isFile) file.length() else null
    override fun open(): InputStream = file.inputStream()
    override fun delete() {
        if (owned) file.delete()
    }

    override fun equals(other: Any?) = other is FileUploadSource && other.file == file && other.name == name
    override fun hashCode() = file.hashCode() * 31 + name.hashCode()
    override fun toString() = "FileUploadSource($file, $name)"
}

/** Rules for link URLs, attachment paths and file names on disk. */
object AttachmentLinks {
    /** Every attachment path the API may fetch starts with this (A11: clients never build them). */
    const val PATH_PREFIX = "/api/v1/attachments/"

    /** The trimmed text when it is an absolute http(s) URL with a host, else null. Only such URLs are ever opened. */
    fun openable(text: String): String? {
        val trimmed = text.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return trimmed
    }

    /** An absolute http(s) URL without credentials, ≤ 2048 chars (the BE's rule for links). */
    fun isValidLink(text: String): Boolean {
        val url = openable(text) ?: return false
        return url.length <= 2048 && URI(url).rawUserInfo == null
    }

    /** A server path the client may GET: under `/api/v1/attachments/`, no `..`, no scheme or host. */
    fun isAttachmentPath(path: String): Boolean = path.startsWith(PATH_PREFIX) &&
        ".." !in path &&
        "//" !in path &&
        '\\' !in path &&
        path.none { it.isWhitespace() }

    /**
     * A file name safe as one path component on disk: no separators, controls or format characters, no leading
     * dots, at most 200 UTF-8 bytes (keeping the extension); empty → "file".
     */
    fun safeFileName(name: String): String {
        var cleaned = buildString {
            name.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                val bad = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                    cp == '/'.code || cp == '\\'.code || cp == ':'.code
                if (!bad) appendCodePoint(cp)
            }
        }
        // Trim and drop leading dots until stable, so the result is a fixed point (". a" → "a", not " a").
        while (true) {
            val next = cleaned.trim().trimStart('.')
            if (next == cleaned) break
            cleaned = next
        }
        if (cleaned.isEmpty()) return "file"
        if (cleaned.toByteArray().size <= 200) return cleaned
        val ext = cleaned.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty() || ext.toByteArray().size > 20) "" else ".$ext"
        var stem = if (suffix.isEmpty()) cleaned else cleaned.dropLast(suffix.length)
        while ((stem + suffix).toByteArray().size > 200) stem = stem.dropLast(1)
        cleaned = stem + suffix
        return cleaned
    }
}
