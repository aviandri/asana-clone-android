package com.mekari.project.core

import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import com.mekari.project.core.fake.FakeSeed
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AttachmentCacheTest {
    private lateinit var root: File
    private lateinit var directory: File
    private val fake = FakeApi(FakeSeed.demo(LocalDate.of(2026, 10, 20)))

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cache-test").toFile()
        directory = File(root, "attachments")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun cache(capacity: Long = 500L * 1024 * 1024) =
        AttachmentCache(directory, capacity, Dispatchers.Unconfined)
    private suspend fun seeded(id: String) = fake.attachment(id)
    private fun downloads() = fake.calls.count { it.endpoint == FakeEndpoint.DOWNLOAD }

    private suspend fun waitParked(endpoint: FakeEndpoint) {
        repeat(2000) {
            if (fake.parkedCount(endpoint) > 0) return
            yield()
            Thread.sleep(1)
        }
        fail("never parked")
    }

    @Test
    fun downloadsIntoItsOwnFolderAndRevalidates() = runBlocking {
        val cache = cache()
        val pdf = seeded(DemoIds.ATTACHMENT_PDF)
        val file = cache.file(pdf, fake)
        assertEquals(File(File(directory, pdf.id), "SSO flow.pdf"), file)
        assertTrue(file.isFile)
        assertEquals(file, cache.cachedFile(pdf.id))
        assertEquals(file, cache.file(pdf, fake))
        assertEquals("a cached copy is revalidated", 2, downloads())
    }

    @Test
    fun offlineServesTheCachedCopyButNotNothing() = runBlocking {
        val cache = cache()
        val pdf = seeded(DemoIds.ATTACHMENT_PDF)
        val png = seeded(DemoIds.ATTACHMENT_PNG)
        val file = cache.file(pdf, fake)
        fake.failNext(FakeEndpoint.DOWNLOAD, ApiError.transport(), times = 2)
        assertEquals(file, cache.file(pdf, fake))
        try {
            cache.file(png, fake)
            fail("nothing cached: the transport error surfaces")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.TRANSPORT, e.code)
        }
    }

    @Test
    fun concurrentCallsShareOneDownloadAndCancelDownloadStopsItForEveryone() = runBlocking {
        val cache = cache()
        val pdf = seeded(DemoIds.ATTACHMENT_PDF)
        fake.hold(FakeEndpoint.DOWNLOAD)
        val a = async(Dispatchers.Default) { cache.file(pdf, fake) }
        val b = async(Dispatchers.Default) { cache.file(pdf, fake) }
        waitParked(FakeEndpoint.DOWNLOAD)
        Thread.sleep(50)
        assertEquals(1, downloads())
        cache.cancelDownload(pdf.id)
        fake.release(FakeEndpoint.DOWNLOAD, all = true)
        for (waiter in listOf(a, b)) {
            try {
                waiter.await()
                fail("expected a cancellation")
            } catch (_: CancellationException) {
            }
        }
        assertNull(cache.cachedFile(pdf.id))
    }

    @Test
    fun wipeRemovesEverythingAndAWipedDownloadNeverLands() = runBlocking {
        val cache = cache()
        val pdf = seeded(DemoIds.ATTACHMENT_PDF)
        val png = seeded(DemoIds.ATTACHMENT_PNG)
        cache.file(pdf, fake)
        fake.hold(FakeEndpoint.DOWNLOAD)
        val late = async(Dispatchers.Default) { runCatching { cache.file(png, fake) } }
        waitParked(FakeEndpoint.DOWNLOAD)
        cache.wipe()
        fake.release(FakeEndpoint.DOWNLOAD, all = true)
        late.await()
        assertFalse(directory.exists() && directory.list()!!.any { it != "index.json" })
        assertEquals(0L, cache.diskUsage())
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() = runBlocking {
        // Fake content for non-images is the file name's bytes: "SSO flow.pdf" = 12, "console.log" = 11.
        val cache = cache(capacity = 20)
        cache.file(seeded(DemoIds.ATTACHMENT_PDF), fake)
        cache.file(seeded(DemoIds.ATTACHMENT_TEXT), fake)
        assertNull("the older file made room", cache.cachedFile(DemoIds.ATTACHMENT_PDF))
        assertNotNull(cache.cachedFile(DemoIds.ATTACHMENT_TEXT))
    }

    @Test
    fun idsThatArentUuidsNeverNameAFolder() = runBlocking {
        val outside = File(root, "outside").apply { mkdirs() }
        val evil = seeded(DemoIds.ATTACHMENT_PDF).copy(id = "../outside")
        try {
            cache().file(evil, fake)
            fail("expected a refusal")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.VALIDATION, e.code)
        }
        assertNull(cache().cachedFile("../outside"))
        assertEquals("refused before any request", 0, downloads())
        assertTrue(outside.isDirectory)
    }

    @Test
    fun aTamperedIndexCantDeleteOutsideAndPartFilesGo() = runBlocking {
        val pdf = seeded(DemoIds.ATTACHMENT_PDF)
        cache().file(pdf, fake)
        val part = File(File(directory, pdf.id), ".leftover.part").apply { writeText("half") }
        val victim = File(root, "victim").apply { mkdirs() }
        val index = File(directory, "index.json")
        index.writeText(index.readText().trimEnd('}') + ""","../victim":{"name":"x","size":1,"lastUsed":0}}""")
        val reopened = cache(capacity = 1) // tiny: eviction runs over every entry on the next download
        reopened.file(seeded(DemoIds.ATTACHMENT_TEXT), fake)
        reopened.wipe()
        assertTrue("nothing outside the cache is ever deleted", victim.isDirectory)
        assertFalse(part.exists())
    }

    @Test
    fun isInsideRejectsLookalikesAndParents() {
        val cache = cache()
        directory.mkdirs()
        assertTrue(cache.isInside(File(directory, "a/b")))
        assertFalse(cache.isInside(File(directory, "../x")))
        assertFalse(cache.isInside(File(directory.path + "-evil/x")))
        assertFalse(cache.isInside(directory))
    }

    @Test
    fun linksCantBeDownloaded() = runBlocking {
        try {
            cache().file(seeded(DemoIds.ATTACHMENT_LINK), fake)
            fail("expected an error")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.VALIDATION, e.code)
        }
    }
}
