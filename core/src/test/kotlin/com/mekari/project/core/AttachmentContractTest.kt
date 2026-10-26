package com.mekari.project.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Attachments (BE §13, A1–A12) through the real [LiveApi] (streamed multipart upload, streamed downloads); a port of
 * the Mac AttachmentContractTests. Skipped unless MP_BASE_URL is set. The 429 and 507 cases need their own BEs:
 * MP_BASE_URL_RATELIMIT (UPLOAD_RATE_LIMIT_MAX=3) and MP_BASE_URL_NOSPACE (a huge STORAGE_MIN_FREE_BYTES).
 */
class AttachmentContractTest {
    private lateinit var scratch: File

    @Before
    fun requireBackend() {
        assumeTrue("MP_BASE_URL not set", System.getenv("MP_BASE_URL").orEmpty().isNotBlank())
        scratch = Files.createTempDirectory("att-contract").toFile()
    }

    @After
    fun cleanUp() {
        if (::scratch.isInitialized) scratch.deleteRecursively()
    }

    private fun server(variable: String = "MP_BASE_URL"): String {
        val raw = System.getenv(variable).orEmpty()
        assumeTrue("$variable not set", raw.isNotBlank())
        return ServerUrl.normalize(raw)!!
    }

    private suspend fun signIn(
        email: String,
        variable: String = "MP_BASE_URL"
    ): Triple<LiveApi, Me, InMemoryTokenStore> {
        val tokens = InMemoryTokenStore()
        val api = LiveApi(server(variable), tokens)
        val me = api.login(email, PASSWORD)
        api.setWorkspace(me.currentWorkspace?.id)
        return Triple(api, me, tokens)
    }

    /** A fresh task in a project the user can edit, so tests don't share state. */
    private suspend fun newTask(api: LiveApi): TaskDetail {
        val projects = api.projects()
        val project =
            projects.firstOrNull { it.name == "Mobile App v2" } ?: projects.first { it.myRole != ProjectRole.COMMENTER }
        return api.createTask(NewTask(title = "Attachments ${System.nanoTime()}", projectId = project.id))
    }

    private fun bytes(name: String, data: ByteArray, owned: Boolean = false) =
        FileUploadSource(File(scratch, "src-${System.nanoTime()}").apply { writeBytes(data) }, name, owned)

    private suspend fun expectError(code: ApiError.Code, status: Int? = null, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ApiError) {
            assertEquals(e.message, code, e.code)
            if (status != null) assertEquals(status, e.status)
            assertTrue(e.message.isNotEmpty())
        }
    }

    private val png: ByteArray = java.util.Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII=")

    @Test
    fun draftUploadThenPostAsACommentAttachment() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com")
        val task = newTask(alice)
        val seen = mutableListOf<TransferProgress>()
        val draft = alice.uploadAttachment(
            task.id,
            bytes("Résumé v2 ✓.txt", "hello attachments".toByteArray()),
            draft = true
        ) {
            seen += it
        }
        assertTrue(draft.draft)
        assertEquals("Résumé v2 ✓.txt", draft.name)
        assertEquals("text/plain", draft.mimeType)
        assertEquals(17L, draft.size)
        assertEquals(17L, seen.last().completed)
        assertEquals("drafts aren't on the task", emptyList<Attachment>(), alice.task(task.id).attachments)

        val comment = alice.addComment(task.id, "", listOf(draft.id))
        assertEquals("", comment.body)
        assertEquals(listOf(draft.id), comment.attachments.map { it.id })
        assertEquals(comment.id, comment.attachments.single().commentId)
        val after = alice.task(task.id)
        assertEquals(listOf(draft.id), after.attachments.map { it.id })
        assertEquals(1, after.task.attachmentCount)

        val (bob, _, _) = signIn("bob@example.com")
        val bobsDraft = bob.uploadAttachment(task.id, bytes("b.txt", "b".toByteArray()), draft = true)
        expectError(ApiError.Code.VALIDATION, 400) { alice.addComment(task.id, "x", listOf(bobsDraft.id)) }

        alice.deleteAttachment(draft.id)
        assertFalse(alice.stories(task.id).any { it.key == comment.key })
    }

    @Test
    fun aNonAsciiFileNameRoundTrips() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com")
        val task = newTask(alice)
        val name = "Ünïcødé 文件.pdf"
        val pdf = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()
        val uploaded = alice.uploadAttachment(task.id, bytes(name, pdf), draft = false)
        assertEquals(name, uploaded.name)
        val fetched = alice.attachment(uploaded.id)
        assertEquals(name, fetched.name)
        assertEquals("application/pdf", fetched.mimeType)
        assertEquals(name, alice.task(task.id).attachments.first().name)
        val local = AttachmentCache(File(scratch, "cache")).file(fetched, alice)
        assertEquals(name, local.name)
        assertTrue(pdf.contentEquals(local.readBytes()))
    }

    @Test
    fun taskLevelUploadDownloadRevalidateAndThumbnails() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com")
        val task = newTask(alice)
        val image = alice.uploadAttachment(task.id, bytes("dot.png", png), draft = false)
        assertFalse(image.draft)
        assertEquals("image/png", image.mimeType)
        assertEquals(2, image.width)
        assertEquals(listOf(image.id), alice.task(task.id).attachments.map { it.id })
        assertTrue(alice.stories(task.id).any { it is Activity && it.type == ActivityType.ATTACHMENT_ADDED })

        val cache = AttachmentCache(File(scratch, "cache"))
        assertTrue(png.contentEquals(cache.file(image, alice).readBytes()))
        val destination = File(scratch, "again.png")
        val first = alice.download(image.contentPath!!, destination) as DownloadOutcome.Downloaded
        assertNotNull(first.etag)
        assertEquals(DownloadOutcome.NotModified, alice.download(image.contentPath!!, destination, first.etag))

        image.thumbnailPath(ThumbnailSize.SMALL)?.let { small ->
            assertEquals("RIFF", String(alice.attachmentData(small).copyOfRange(0, 4)))
        }
        // Byte GETs ignore the workspace header: still served under the other workspace's.
        val other = alice.me().workspaces.first { it.id != task.task.workspaceId }
        alice.setWorkspace(other.id)
        alice.attachmentData(image.contentPath!!)
        alice.setWorkspace(task.task.workspaceId)

        alice.deleteAttachment(image.id)
        expectError(ApiError.Code.NOT_FOUND, 404) { alice.attachment(image.id) }
    }

    @Test
    fun linksAndTheirValidation() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com")
        val task = newTask(alice)
        expectError(ApiError.Code.VALIDATION, 400) {
            alice.addLink(task.id, "javascript:alert(1)", null, draft = false)
        }
        expectError(ApiError.Code.VALIDATION, 400) {
            alice.addLink(task.id, "https://user:pass@example.com/", null, draft = false)
        }
        // Private addresses are never fetched (SSRF rules): the link is still created, its preview FAILED.
        val link = alice.addLink(task.id, "http://127.0.0.1:9/x", "Local thing", draft = false)
        assertEquals(AttachmentKind.LINK, link.kind)
        assertEquals("Local thing", link.title)
        assertEquals(LinkPreviewStatus.FAILED, link.previewStatus)
        assertEquals(link.id, alice.refreshLinkPreview(link.id).id)
    }

    @Test
    fun commentersCanAddDraftsButNotTaskAttachments() = runBlocking {
        val (bob, _, _) = signIn("bob@example.com") // bob is a COMMENTER on one seeded project
        val readOnly = bob.projects().first { it.myRole == ProjectRole.COMMENTER }
        val task = bob.projectTasks(readOnly.id).first()
        expectError(ApiError.Code.FORBIDDEN, 403) {
            bob.uploadAttachment(task.id, bytes("c.txt", "c".toByteArray()), draft = false)
        }
        val draft = bob.uploadAttachment(task.id, bytes("c.txt", "c".toByteArray()), draft = true)
        bob.deleteAttachment(draft.id)
    }

    @Test
    fun theHundredMegabytePreCheckSendsNothing() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com")
        val task = newTask(alice)
        val over = object : UploadSource {
            override val name = "big.bin"
            override val size: Long? = AttachmentLimits.MAX_UPLOAD_BYTES + 1
            override fun open(): InputStream = fail("never opened") as Nothing
        }
        try {
            alice.uploadAttachment(task.id, over, draft = true)
            fail("expected PAYLOAD_TOO_LARGE")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.PAYLOAD_TOO_LARGE, e.code)
            assertNull("refused locally, no request", e.status)
        }
    }

    /** The raw 413: a body past the limit, sent by hand (as an older client would). */
    @Test
    fun theServerRefusesOverOneHundredMegabytesWith413() = runBlocking {
        val (alice, _, tokens) = signIn("alice@example.com")
        val task = newTask(alice)
        val boundary = "raw-${System.nanoTime()}"
        val size = AttachmentLimits.MAX_UPLOAD_BYTES + 1024
        val body = object : RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n\r\n"
                )
                val zeros = ByteArray(64 * 1024)
                var left = size
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    sink.write(zeros, 0, n)
                    left -= n
                }
                sink.writeUtf8("\r\n--$boundary--\r\n")
            }
        }
        val request = Request.Builder()
            .url("${server()}/api/v1/tasks/${task.id}/attachments")
            .header("Cookie", "sid=${tokens.load()}")
            .post(body)
            .build()
        val client = OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            client.newCall(request).execute().use { response ->
                assertEquals(413, response.code)
                assertTrue(response.body!!.string().contains("PAYLOAD_TOO_LARGE"))
            }
        } catch (e: java.io.IOException) {
            // The server may reset the connection once it refused the rest; then nothing was stored either.
            assertEquals(e.toString(), emptyList<Attachment>(), alice.task(task.id).attachments)
        }
    }

    @Test
    fun rateLimitIs429() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com", "MP_BASE_URL_RATELIMIT")
        val task = newTask(alice)
        var refused: ApiError? = null
        for (i in 0 until 5) {
            try {
                alice.uploadAttachment(task.id, bytes("r.txt", "r".toByteArray()), draft = true)
            } catch (e: ApiError) {
                refused = e
                break
            }
        }
        assertEquals(ApiError.Code.RATE_LIMITED, refused?.code)
        assertEquals(429, refused?.status)
    }

    @Test
    fun noSpaceIs507() = runBlocking {
        val (alice, _, _) = signIn("alice@example.com", "MP_BASE_URL_NOSPACE")
        val task = newTask(alice)
        expectError(ApiError.Code.INSUFFICIENT_STORAGE, 507) {
            alice.uploadAttachment(task.id, bytes("n.txt", "n".toByteArray()), draft = true)
        }
    }

    private companion object {
        const val PASSWORD = "password123"
    }
}
