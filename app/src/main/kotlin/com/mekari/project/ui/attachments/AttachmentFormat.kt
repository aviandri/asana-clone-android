package com.mekari.project.ui.attachments

import androidx.compose.ui.graphics.Color
import com.mekari.project.core.ApiError
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentKind
import com.mekari.project.core.AttachmentLimits
import java.util.Locale

/** Presentation helpers for attachments (BE SPEC §13 Clients): the same copy as the web and Mac apps. */
object AttachmentFormat {
    /** "2.3 MB": binary units, like the 100 MB limit (100 × 1024²). */
    fun bytes(count: Long?): String {
        if (count == null || count < 0) return ""
        if (count < 1024) return "$count B"
        val units = listOf("KB", "MB", "GB")
        var value = count / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val number = when {
            value < 10 -> String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
            else -> Math.round(value).toString()
        }
        return "$number ${units[unit]}"
    }

    /** The TalkBack name of a tile: "Attachment, report.pdf, 2.3 MB" / "Link, Design spec". */
    fun label(a: Attachment): String = if (a.kind == AttachmentKind.LINK) {
        "Link, ${a.displayName}"
    } else {
        listOf("Attachment", a.name ?: "file", bytes(a.size)).filter { it.isNotEmpty() }.joinToString(", ")
    }

    /** A failed upload or link (web v1.3 copy; 429 and 507 show the server's words). */
    fun errorMessage(error: ApiError, name: String): String = when (error.code) {
        ApiError.Code.PAYLOAD_TOO_LARGE -> "$name is larger than 100 MB."
        ApiError.Code.RATE_LIMITED ->
            error.message.takeIf { error.status != null && it.isNotEmpty() }
                ?: "Too many uploads. Try again in a few minutes."
        ApiError.Code.INSUFFICIENT_STORAGE ->
            error.message.takeIf { error.status != null && it.isNotEmpty() }
                ?: "The server is out of storage space. Try again later."
        ApiError.Code.FORBIDDEN -> "You can't add attachments here."
        else -> error.message.ifEmpty { "Upload failed." }
    }

    /** Retry can't help a file over the limit, or one we may not add. */
    fun isRetryable(error: ApiError): Boolean =
        error.code != ApiError.Code.PAYLOAD_TOO_LARGE && error.code != ApiError.Code.FORBIDDEN

    /** The problem with an Add link attempt: the `url` issue (local or the server's), else the message. */
    fun linkProblem(error: ApiError): String =
        error.issue("url")?.message ?: error.message.ifEmpty { "Couldn't add the link." }

    val commentFullMessage = "A comment can have up to ${AttachmentLimits.MAX_PER_COMMENT} attachments."

    /** What the in-app viewer shows: exactly the server's `inline` allowlist (§13 Serving); null = icon + actions. */
    fun previewKind(a: Attachment): PreviewKind? {
        if (a.kind != AttachmentKind.FILE || a.contentPath == null) return null
        val mime = a.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return INLINE[mime]
    }

    private val INLINE = mapOf(
        "image/png" to PreviewKind.IMAGE,
        "image/jpeg" to PreviewKind.IMAGE,
        "image/gif" to PreviewKind.IMAGE,
        "image/webp" to PreviewKind.IMAGE,
        "image/avif" to PreviewKind.IMAGE,
        "image/bmp" to PreviewKind.IMAGE,
        "application/pdf" to PreviewKind.PDF,
        "video/mp4" to PreviewKind.VIDEO,
        "video/webm" to PreviewKind.VIDEO,
        "video/quicktime" to PreviewKind.VIDEO,
        "audio/mpeg" to PreviewKind.AUDIO,
        "audio/mp4" to PreviewKind.AUDIO,
        "audio/wav" to PreviewKind.AUDIO,
        "audio/ogg" to PreviewKind.AUDIO,
        "audio/webm" to PreviewKind.AUDIO,
        "text/plain" to PreviewKind.TEXT
    )

    /**
     * The file-type badge (no icon library): the extension in a tinted box, its colour by family. The sniffed
     * MIME type wins over the extension for the family.
     */
    fun typeBadge(mimeType: String?, name: String?): TypeBadge {
        val mime = mimeType?.lowercase().orEmpty()
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..5 }.orEmpty()
        val family = when {
            mime == "application/pdf" || ext == "pdf" -> Family.PDF
            mime.startsWith("image/") && mime != "image/svg+xml" -> Family.IMAGE
            mime.startsWith("video/") -> Family.MEDIA
            mime.startsWith("audio/") -> Family.MEDIA
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "avif", "bmp", "tiff") -> Family.IMAGE
            ext in setOf("mp4", "mov", "webm", "mp3", "wav", "m4a", "ogg") -> Family.MEDIA
            ext in setOf("xls", "xlsx", "ods", "csv", "numbers") -> Family.SHEET
            ext in setOf("ppt", "pptx", "odp", "key") -> Family.SLIDES
            ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz") -> Family.ARCHIVE
            ext in setOf("doc", "docx", "odt", "rtf", "pages", "txt", "md", "log") || mime == "text/plain" -> Family.DOC
            else -> Family.OTHER
        }
        return TypeBadge(ext.uppercase().ifEmpty { "FILE" }, family.tone)
    }

    private enum class Family(val tone: Color) {
        PDF(Color(0xFFDC2626)),
        IMAGE(Color(0xFF7C3AED)),
        MEDIA(Color(0xFFDB2777)),
        SHEET(Color(0xFF059669)),
        SLIDES(Color(0xFFEA580C)),
        ARCHIVE(Color(0xFF92400E)),
        DOC(Color(0xFF2563EB)),
        OTHER(Color(0xFF6B7280))
    }
}

data class TypeBadge(val label: String, val tone: Color)

enum class PreviewKind { IMAGE, PDF, VIDEO, AUDIO, TEXT }

/**
 * The §13 link providers as neutral, app-drawn badges (A11: initials + name, no third-party logos; the web and
 * Mac apps use the same set). Unknown providers get the generic link glyph.
 */
data class ProviderBadge(val name: String, val initials: String, val tone: Color) {
    companion object {
        fun of(provider: String?): ProviderBadge? = provider?.let { ALL[it] }

        private val ALL = mapOf(
            "google_drive" to ProviderBadge("Google Drive", "Dr", Color(0xFF16A34A)),
            "google_docs" to ProviderBadge("Google Docs", "Do", Color(0xFF0284C7)),
            "google_sheets" to ProviderBadge("Google Sheets", "Sh", Color(0xFF059669)),
            "google_slides" to ProviderBadge("Google Slides", "Sl", Color(0xFFF59E0B)),
            "dropbox" to ProviderBadge("Dropbox", "Db", Color(0xFF0369A1)),
            "onedrive" to ProviderBadge("OneDrive", "Od", Color(0xFF0EA5E9)),
            "box" to ProviderBadge("Box", "Bx", Color(0xFF075985)),
            "figma" to ProviderBadge("Figma", "Fg", Color(0xFF7C3AED)),
            "youtube" to ProviderBadge("YouTube", "Yt", Color(0xFFDC2626)),
            "loom" to ProviderBadge("Loom", "Lm", Color(0xFF4F46E5)),
            "github" to ProviderBadge("GitHub", "Gh", Color(0xFF111827)),
            "gitlab" to ProviderBadge("GitLab", "Gl", Color(0xFFEA580C)),
            "notion" to ProviderBadge("Notion", "No", Color(0xFF1F2937)),
            "miro" to ProviderBadge("Miro", "Mi", Color(0xFFEAB308)),
            "jira" to ProviderBadge("Jira", "Ji", Color(0xFF1D4ED8)),
            "confluence" to ProviderBadge("Confluence", "Cf", Color(0xFF2563EB)),
            "slack" to ProviderBadge("Slack", "Sk", Color(0xFFA21CAF)),
            "zoom" to ProviderBadge("Zoom", "Zm", Color(0xFF3B82F6))
        )
    }
}
