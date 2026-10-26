package com.mekari.project.ui.attachments

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.mekari.project.core.FileUploadSource
import com.mekari.project.core.UploadSource
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A picked file (Photo Picker, OpenMultipleDocuments): streamed from its content:// Uri, never read into memory
 * (SPEC-ANDROID §8). Name and size come from OpenableColumns; an unknown size is checked by the upload as it
 * streams. The Uri isn't ours, so [delete] does nothing.
 */
class ContentUriSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val name: String,
    override val size: Long?
) : UploadSource {
    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())

    companion object {
        fun from(resolver: ContentResolver, uri: Uri): ContentUriSource {
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                        if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                    }
                }
            }
            return ContentUriSource(
                resolver,
                uri,
                name?.takeIf {
                    it.isNotBlank()
                } ?: uri.lastPathSegment ?: "file",
                size
            )
        }
    }
}

/**
 * Files shared with other apps go through [FileProvider] (`<package>.attachments`): only the attachments cache
 * (downloads) and the camera's temp folder are exposed (res/xml/attachment_paths.xml).
 */
object AttachmentShare {
    fun authority(context: Context) = "${context.packageName}.attachments"

    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Where the camera writes a photo before it's uploaded (TakePicture needs a Uri it can write): a random
     * name in one flat folder, uploaded as "Photo <date>.jpg".
     */
    fun newCameraFile(context: Context): File {
        val dir = File(context.cacheDir, CAMERA_DIR).also { it.mkdirs() }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    /** The upload of a camera capture: ours, so it's deleted once the upload leaves its list. */
    fun cameraSource(file: File, now: Date = Date()): UploadSource {
        val stamp = SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.ROOT).format(now)
        return FileUploadSource(file, "Photo $stamp.jpg", owned = true)
    }

    /** Removes a capture the camera didn't finish (cancelled). */
    fun discardCameraFile(file: File) {
        file.delete()
    }

    /**
     * Camera captures left behind (a crash, process death mid-upload, or another user's before a sign-out). Every
     * wipe of the attachment cache removes them, except those in [keep] (a live upload still reads them); startup
     * removes those older than [olderThanMs], so a photo the camera just wrote for a recreated activity survives.
     */
    fun clearCameraCaptures(
        context: Context,
        olderThanMs: Long = 0,
        keep: Set<File> = emptySet(),
        now: Long = System.currentTimeMillis()
    ) {
        File(context.cacheDir, CAMERA_DIR).listFiles()?.forEach { file ->
            if (file.absoluteFile in keep) return@forEach
            if (olderThanMs == 0L || now - file.lastModified() > olderThanMs) file.deleteRecursively()
        }
    }

    const val CAMERA_DIR = "camera-captures"
}

/** Open with another app, Share, Copy link: the intents of SPEC-ANDROID §8. */
object AttachmentIntents {
    /** ACTION_VIEW on a FileProvider Uri with a chooser; false when no app can open it (the caller says so). */
    fun openWith(context: Context, file: File, mimeType: String?, title: String): Boolean {
        val uri = AttachmentShare.contentUri(context, file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (view.resolveActivity(context.packageManager) == null && !hasHandler(context, view)) return false
        return start(context, Intent.createChooser(view, "Open “$title” with"))
    }

    fun share(context: Context, file: File, mimeType: String?, title: String): Boolean {
        val uri = AttachmentShare.contentUri(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        return start(context, Intent.createChooser(send, "Share “$title”"))
    }

    /** A LINK: ACTION_VIEW, so App Links route Figma, YouTube, Drive… to their apps. http/https only. */
    fun openLink(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        return start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun copyLink(context: Context, url: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
    }

    private fun hasHandler(context: Context, intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(
            intent.addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
