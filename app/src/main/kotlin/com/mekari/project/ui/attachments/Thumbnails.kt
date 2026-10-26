package com.mekari.project.ui.attachments

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import com.mekari.project.core.AttachmentLinks
import com.mekari.project.feature.AttachmentFiles
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Buffer
import okio.Path.Companion.toOkioPath

/**
 * A thumbnail, link icon or og:image for Coil (SPEC-ANDROID §8): a server-given attachment path, fetched
 * through the workspace's [AttachmentFiles] (the app's authenticated API client, refused once the scope
 * closes; no second HTTP client). [path] includes the attachment id (and `?size=`), so it is the cache key in
 * memory and on disk: one attachment's image can never be served for another.
 */
data class AttachmentImage(val path: String, val files: AttachmentFiles)

/** Coil for attachments: memory + a 50 MB disk cache in cacheDir/attachment-thumbs, both wiped with the attachment cache. */
object Thumbnails {
    const val DISK_CACHE_BYTES = 50L * 1024 * 1024

    fun directory(context: Context): File = File(context.cacheDir, "attachment-thumbs")

    fun imageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(AttachmentImageKeyer)
            add(AttachmentImageFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder().directory(directory(context).toOkioPath()).maxSizeBytes(DISK_CACHE_BYTES).build()
        }
        .build()

    /**
     * After every attachment-cache wipe (sign-out, lost workspace, start while signed out): memory at once
     * (the caller's thread), the disk cache on IO (it deletes files; the hook runs on main).
     */
    fun clear(loader: ImageLoader, scope: CoroutineScope): Job {
        loader.memoryCache?.clear()
        return scope.launch(Dispatchers.IO) { loader.diskCache?.clear() }
    }
}

/** The app's attachment image loader, provided at the root (tests may provide their own). */
val LocalThumbnailLoader = staticCompositionLocalOf<ImageLoader?> { null }

internal object AttachmentImageKeyer : Keyer<AttachmentImage> {
    override fun key(data: AttachmentImage, options: Options): String = data.path
}

/**
 * Disk cache first, else the bytes from [AttachmentFiles.imageData] (written to the disk cache under the
 * path). Only `/api/v1/attachments/…` paths are fetched; anything else fails the load (the tile shows its
 * type badge). A closed scope's CancellationException propagates: Coil treats it as a cancelled load.
 */
internal class AttachmentImageFetcher(
    private val data: AttachmentImage,
    private val options: Options,
    private val diskCache: Lazy<DiskCache?>
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        check(AttachmentLinks.isAttachmentPath(data.path)) { "not an attachment path" }
        val key = data.path
        val fileSystem = options.fileSystem
        diskCache.value?.openSnapshot(key)?.let { snapshot ->
            return SourceFetchResult(
                source = ImageSource(snapshot.data, fileSystem, diskCacheKey = key, closeable = snapshot),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }
        val bytes = data.files.imageData(data.path) ?: error("no image")
        diskCache.value?.openEditor(key)?.let { editor ->
            try {
                fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
            } catch (e: Exception) {
                editor.abort()
            }
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), fileSystem),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory : Fetcher.Factory<AttachmentImage> {
        override fun create(data: AttachmentImage, options: Options, imageLoader: ImageLoader): Fetcher =
            AttachmentImageFetcher(data, options, lazy { imageLoader.diskCache })
    }
}
