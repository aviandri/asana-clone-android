package com.mekari.project.ui.attachments

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.Options
import com.mekari.project.AppContainer
import com.mekari.project.MekariApplication
import com.mekari.project.core.ApiEvent
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.feature.AppSession
import com.mekari.project.feature.Auth
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app's attachment plumbing: the container's real download cache, Coil's caches cleared on every
 * attachment wipe (sign-out, lost workspace, start while signed out), thumbnails keyed by their path, and the
 * picked-file upload source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MekariApplication::class)
class AttachmentWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Runs the paused main looper (holder actions, Main.immediate) until [condition] holds. */
    private fun idleUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $what")
    }

    /** Puts one entry in Coil's memory and disk caches, so a wipe shows. */
    private fun seed(loader: ImageLoader) {
        val editor =
            requireNotNull(loader.diskCache?.openEditor("/api/v1/attachments/${DemoIds.ATTACHMENT_PNG}/thumbnail"))
        FileSystem.SYSTEM.write(editor.data) { writeUtf8("thumb") }
        editor.commit()
        loader.memoryCache!![MemoryCache.Key("k")] =
            MemoryCache.Value(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage())
        assertTrue(loader.diskCache!!.size > 0)
        assertTrue(loader.memoryCache!!.size > 0)
    }

    private fun isWiped(loader: ImageLoader) = loader.diskCache!!.size == 0L && loader.memoryCache!!.size == 0L

    private fun signedIn(container: AppContainer, fake: FakeApi = FakeApi()): AppSession {
        val session = container.session(fakeApi = true, signedIn = true, fake = fake)
        idleUntil("signed in") { session.state.value.auth is Auth.SignedIn && session.scope.value != null }
        return session
    }

    @Test
    fun theContainerUsesTheRealDownloadCacheInCacheDirAttachments() {
        val container = AppContainer(context)
        val session = container.session(fakeApi = true)
        assertEquals(File(context.cacheDir, "attachments"), session.attachmentCache.directory)
        assertEquals(File(context.cacheDir, "attachments"), container.attachmentCacheDir)
        // Coil's disk cache is its own folder, capped, never inside the download cache.
        assertEquals(File(context.cacheDir, "attachment-thumbs"), Thumbnails.directory(context))
        assertEquals(50L * 1024 * 1024, container.thumbnailLoader.diskCache?.maxSize)
    }

    @Test
    fun signingOutClearsCoilsCaches() {
        val container = AppContainer(context)
        val session = signedIn(container)
        seed(container.thumbnailLoader)
        // Another user's capture mustn't outlive the sign-out.
        val capture = AttachmentShare.newCameraFile(context).apply { writeText("jpeg") }
        container.appScope.launch { session.signOut() }
        idleUntil("Coil wiped after sign-out") { isWiped(container.thumbnailLoader) }
        idleUntil("camera captures removed after sign-out") { !capture.exists() }
    }

    @Test
    fun startupRemovesOnlyStaleCameraCaptures() {
        val stale = AttachmentShare.newCameraFile(context).apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        }
        // A photo the camera just wrote for a recreated activity survives.
        val fresh = AttachmentShare.newCameraFile(context).apply { writeText("new") }
        val container = AppContainer(context)
        container.session(fakeApi = true)
        idleUntil("the stale capture removed at startup") { !stale.exists() }
        assertTrue("a fresh capture is kept", fresh.exists())
    }

    @Test
    fun losingTheWorkspaceClearsCoilsCaches() {
        val container = AppContainer(context)
        val fake = FakeApi()
        val session = signedIn(container, fake)
        seed(container.thumbnailLoader)
        fake.removeMembership(DemoIds.ALICE, DemoIds.MEKARI)
        fake.emit(ApiEvent.WorkspaceLost(DemoIds.MEKARI))
        idleUntil("Coil wiped after losing the workspace") { isWiped(container.thumbnailLoader) }
        idleUntil("moved to another workspace") { session.scope.value?.id == DemoIds.ACME_PILOT }
    }

    @Test
    fun startingSignedOutClearsCoilsCaches() {
        val container = AppContainer(context)
        seed(container.thumbnailLoader)
        val session = container.session(fakeApi = true, signedIn = false)
        idleUntil("signed out") { session.state.value.auth is Auth.SignedOut }
        idleUntil("Coil wiped at a signed-out start") { isWiped(container.thumbnailLoader) }
    }

    @Test
    fun thumbnailsAreFetchedThroughTheAppsApiAndCachedUnderTheirPath() {
        val container = AppContainer(context)
        val session = signedIn(container)
        val files = session.scope.value!!.attachmentFiles
        val loader = container.thumbnailLoader
        val path = "/api/v1/attachments/${DemoIds.ATTACHMENT_PNG}/thumbnail?size=small"
        val image = AttachmentImage(path, files)
        assertEquals(path, AttachmentImageKeyer.key(image, Options(context)))

        val options = Options(context)
        val first = runBlocking {
            AttachmentImageFetcher(image, options, lazy { loader.diskCache }).fetch()
        } as SourceFetchResult
        assertEquals(DataSource.NETWORK, first.dataSource)
        first.source.close()
        assertNotNull(
            "written to the disk cache under its path",
            loader.diskCache?.openSnapshot(path)?.also {
                it.close()
            }
        )
        val second = runBlocking {
            AttachmentImageFetcher(image, options, lazy { loader.diskCache }).fetch()
        } as SourceFetchResult
        assertEquals(DataSource.DISK, second.dataSource)
        second.source.close()
        // Another attachment's path is another key: never served from this entry.
        assertNull(loader.diskCache?.openSnapshot("/api/v1/attachments/${DemoIds.ATTACHMENT_PDF}/thumbnail?size=small"))
    }

    @Test
    fun onlyAttachmentPathsAreFetched() {
        val container = AppContainer(context)
        val session = signedIn(container)
        val files = session.scope.value!!.attachmentFiles
        val result = runCatching {
            runBlocking {
                AttachmentImageFetcher(AttachmentImage("/api/v1/tasks", files), Options(context), lazy { null }).fetch()
            }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun aPickedFileStreamsFromItsUriWithItsName() {
        val file = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        val source = ContentUriSource.from(context.contentResolver, Uri.fromFile(file))
        assertEquals("notes.txt", source.name)
        assertEquals("hello", source.open().use { it.readBytes().decodeToString() })
        source.delete()
        assertTrue("a picked file isn't ours to delete", file.exists())
    }

    @Test
    fun aCameraCaptureIsOursAndGoesWithItsUpload() {
        val file = AttachmentShare.newCameraFile(context).apply { writeText("jpeg") }
        // A random file name in one flat folder; uploaded as "Photo <date>.jpg".
        assertEquals(File(context.cacheDir, AttachmentShare.CAMERA_DIR), file.parentFile)
        val source = AttachmentShare.cameraSource(file)
        assertTrue(source.name, source.name.startsWith("Photo ") && source.name.endsWith(".jpg"))
        source.delete()
        assertTrue("the capture is deleted once its upload is done", !file.exists())
        assertTrue("nothing left in the camera folder", file.parentFile!!.listFiles().isNullOrEmpty())
        // (FileProvider's Uri isn't checked here: it caches its roots statically, and every Robolectric test has
        // a new data dir.)
    }
}
