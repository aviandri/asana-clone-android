package com.mekari.project.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.blackholeSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val USER = """{"id":"u1","name":"Alice","email":"alice@example.com","avatarColor":"#e8384f"}"""
private const val TASK_ID = "50000000-0000-4000-8000-000000000001"

internal const val FILE_JSON = """{"id":"a0000000-0000-4000-8000-000000000001","workspaceId":"w1","taskId":"$TASK_ID",
 "commentId":null,"kind":"FILE","draft":false,"uploader":$USER,"createdAt":"2026-10-20T09:00:00.000Z",
 "name":"login.png","mimeType":"image/png","size":48213,"width":1280,"height":800,"hasThumbnail":true,
 "url":null,"title":null,"description":null,"siteName":null,"provider":null,"previewStatus":null,"hasIcon":false,
 "hasImage":false,"contentPath":"/api/v1/attachments/a0000000-0000-4000-8000-000000000001/content",
 "thumbnailPath":"/api/v1/attachments/a0000000-0000-4000-8000-000000000001/thumbnail","iconPath":null,"imagePath":null}"""

class AttachmentModelTest {
    @Test
    fun decodesFilesLinksAndUnknownKinds() {
        val file = MekariJson.decodeFromString(Attachment.serializer(), FILE_JSON)
        assertEquals(AttachmentKind.FILE, file.kind)
        assertEquals(48_213L, file.size)
        assertTrue(file.isImage)
        assertEquals("/api/v1/attachments/${file.id}/thumbnail?size=small", file.thumbnailPath(ThumbnailSize.SMALL))
        assertNull(file.linkUrl)

        val link = MekariJson.decodeFromString(
            Attachment.serializer(),
            """{"id":"x","workspaceId":"w","taskId":"t","kind":"LINK","uploader":$USER,"createdAt":"2026-10-20T09:00:00Z",
               "url":"https://www.figma.com/file/x","previewStatus":"READY","hasIcon":true,"iconPath":"/api/v1/attachments/x/icon"}"""
        )
        assertEquals("figma.com", link.displayName)
        assertEquals("https://www.figma.com/file/x", link.linkUrl)
        assertEquals(LinkPreviewStatus.READY, link.previewStatus)

        val future = MekariJson.decodeFromString(
            Attachment.serializer(),
            """{"id":"y","workspaceId":"w","taskId":"t","kind":"EMBED","uploader":$USER,"createdAt":"2026-10-20T09:00:00Z",
               "previewStatus":"QUEUED","embedHtml":"<x>"}"""
        )
        assertEquals(AttachmentKind("EMBED"), future.kind)
        assertEquals(LinkPreviewStatus("QUEUED"), future.previewStatus)
        assertFalse(future.hasThumbnail)
    }

    @Test
    fun onlyHttpLinksAreOpenable() {
        for (bad in listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "mailto:a@b.c",
            "https://",
            "notaurl",
            "ftp://x.y/z"
        )) {
            assertNull(bad, AttachmentLinks.openable(bad))
        }
        assertFalse(AttachmentLinks.isValidLink("https://user:pass@example.com/"))
        assertTrue(AttachmentLinks.isValidLink("HTTP://Example.com/a"))
    }

    @Test
    fun tasksCommentsAndDetailsDefaultForOlderServers() {
        val task = MekariJson.decodeFromString(
            TaskItem.serializer(),
            """{"id":"$TASK_ID","workspaceId":"w1","title":"x","creator":$USER,"createdAt":"2026-10-01T02:00:00Z",
               "updatedAt":"2026-10-01T02:00:00Z"}"""
        )
        assertEquals(0, task.attachmentCount)
        val comment = MekariJson.decodeFromString(
            CommentSerializer,
            """{"kind":"comment","id":"c1","author":$USER,"body":"","createdAt":"2026-10-01T02:00:00Z","attachments":[$FILE_JSON]}"""
        )
        assertEquals("", comment.body)
        assertEquals(1, comment.attachments.size)
        val again = MekariJson.decodeFromString(
            CommentSerializer,
            MekariJson.encodeToString(CommentSerializer, comment)
        )
        assertEquals(comment, again)
    }

    @Test
    fun errorCodesIncludingProxyPages() {
        fun code(raw: String, status: Int) = ApiError.from(status, """{"error":{"code":"$raw","message":"m"}}""").code
        assertEquals(ApiError.Code.PAYLOAD_TOO_LARGE, code("PAYLOAD_TOO_LARGE", 413))
        assertEquals(ApiError.Code.INSUFFICIENT_STORAGE, code("INSUFFICIENT_STORAGE", 507))
        assertEquals(ApiError.Code("BRAND_NEW"), code("BRAND_NEW", 409))
        fun html(status: Int) = ApiError.from(status, "<html>nginx</html>").code
        assertEquals(ApiError.Code.PAYLOAD_TOO_LARGE, html(413))
        assertEquals(ApiError.Code.RATE_LIMITED, html(429))
        assertEquals(ApiError.Code.INSUFFICIENT_STORAGE, html(507))
        assertEquals(ApiError.Code("HTTP 502"), html(502))
        assertNull(AttachmentLimits.tooLarge().status)
    }

    @Test
    fun pathsAndFileNames() {
        assertTrue(AttachmentLinks.isAttachmentPath("/api/v1/attachments/abc/thumbnail?size=small"))
        for (bad in listOf(
            "/api/v1/tasks/x",
            "https://evil/api/v1/attachments/x",
            "/api/v1/attachments/../auth/me",
            "//evil/api/v1/attachments/x"
        )) {
            assertFalse(bad, AttachmentLinks.isAttachmentPath(bad))
        }
        assertEquals("etcpasswd", AttachmentLinks.safeFileName("../../etc/passwd"))
        assertEquals("file", AttachmentLinks.safeFileName("   "))
        assertEquals("agpj.exe", AttachmentLinks.safeFileName("a‮gpj.exe"))
        for (name in listOf(". a", " .. b.txt", ".‮ c", "x", "é".repeat(300) + ".pdf", " . . d ")) {
            val once = AttachmentLinks.safeFileName(name)
            assertEquals("idempotent for $name", once, AttachmentLinks.safeFileName(once))
        }
        val long = AttachmentLinks.safeFileName("é".repeat(300) + ".pdf")
        assertTrue(long.toByteArray().size <= 200 && long.endsWith(".pdf"))
    }
}

/** A stream of [length] zero bytes that declares [declared] (null: unknown). */
private class ZeroSource(
    private val length: Long,
    private val declared: Long? = length,
    override val name: String = "z.bin"
) : UploadSource {
    override val size: Long? get() = declared
    override fun open(): InputStream = object : InputStream() {
        var left = length
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            left -= n
            java.util.Arrays.fill(b, off, off + n, 0)
            return n
        }
    }
}

class AttachmentLiveApiTest {
    private lateinit var server: MockWebServer
    private val tokens = InMemoryTokenStore().apply { save("tok") }
    private lateinit var api: LiveApi
    private lateinit var scratch: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api =
            LiveApi(
                server.url("/").toString().trimEnd('/'),
                tokens,
                OkHttpClient(),
                decodeDispatcher = Dispatchers.Unconfined
            )
        runBlocking { api.setWorkspace("w1") }
        scratch = Files.createTempDirectory("att-live").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        scratch.deleteRecursively()
    }

    private fun source(name: String, text: String) = object : UploadSource {
        override val name = name
        override val size: Long? = text.toByteArray().size.toLong()
        override fun open(): InputStream = ByteArrayInputStream(text.toByteArray())
    }

    @Test
    fun uploadIsOneMultipartFilePartWithDraftQuery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(FILE_JSON))
        val seen = mutableListOf<TransferProgress>()
        val attachment = api.uploadAttachment(TASK_ID, source("Résumé \"final\".txt", "hello world"), draft = true) {
            seen +=
                it
        }
        assertEquals("login.png", attachment.name)
        val request = server.takeRequest()
        assertEquals("/api/v1/tasks/$TASK_ID/attachments?draft=true", request.path)
        assertEquals("sid=tok", request.getHeader("Cookie"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue(body, "Content-Disposition: form-data; name=\"file\"; filename=\"Résumé %22final%22.txt\"" in body)
        assertTrue(body, "\r\n\r\nhello world\r\n" in body)
        assertEquals(TransferProgress(11, 11), seen.last())
    }

    @Test
    fun taskLevelUploadSendsNoDraftQuery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(FILE_JSON))
        api.uploadAttachment(TASK_ID, source("a.png", "x"), draft = false)
        assertEquals("/api/v1/tasks/$TASK_ID/attachments", server.takeRequest().path)
    }

    @Test
    fun over100MBIsRefusedBeforeAnyRequest() = runBlocking {
        try {
            api.uploadAttachment(TASK_ID, ZeroSource(1, declared = AttachmentLimits.MAX_UPLOAD_BYTES + 1), draft = true)
            fail("expected PAYLOAD_TOO_LARGE")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.PAYLOAD_TOO_LARGE, e.code)
            assertNull(e.status)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aStreamLongerThanTheLimitIsStopped() {
        val body = SourceBody(ZeroSource(AttachmentLimits.MAX_UPLOAD_BYTES + 1, declared = null), null) {}
        try {
            body.writeTo(blackholeSink().buffer())
            fail("expected the body to stop")
        } catch (_: java.io.IOException) {
            assertTrue(body.tooLarge)
        }
        assertTrue("never replayed by OkHttp", body.isOneShot())
        val exact = SourceBody(ZeroSource(AttachmentLimits.MAX_UPLOAD_BYTES, declared = null), null) {}
        exact.writeTo(blackholeSink().buffer())
        assertFalse(exact.tooLarge)
    }

    @Test
    fun serverUploadErrorsKeepTheirCodeAndMessage() = runBlocking {
        for ((status, raw, expected) in listOf(
            Triple(413, "PAYLOAD_TOO_LARGE", ApiError.Code.PAYLOAD_TOO_LARGE),
            Triple(429, "RATE_LIMITED", ApiError.Code.RATE_LIMITED),
            Triple(507, "INSUFFICIENT_STORAGE", ApiError.Code.INSUFFICIENT_STORAGE),
            Triple(409, "BRAND_NEW", ApiError.Code("BRAND_NEW"))
        )) {
            server.enqueue(
                MockResponse().setResponseCode(
                    status
                ).setBody("""{"error":{"code":"$raw","message":"server says $status"}}""")
            )
            try {
                api.uploadAttachment(TASK_ID, source("a.txt", "x"), draft = true)
                fail("expected $raw")
            } catch (e: ApiError) {
                assertEquals(expected, e.code)
                assertEquals(status, e.status)
                assertEquals("server says $status", e.message)
            }
        }
    }

    @Test
    fun anUnreadableSourceIsAValidationErrorWithoutARequest() = runBlocking {
        val broken = object : UploadSource {
            override val name = "gone.txt"
            override val size: Long? = 3
            override fun open(): InputStream = throw java.io.FileNotFoundException("revoked")
        }
        try {
            api.uploadAttachment(TASK_ID, broken, draft = false)
            fail("expected VALIDATION")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.VALIDATION, e.code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun commentWithAndWithoutAttachmentIds() = runBlocking {
        val comment = """{"kind":"comment","id":"c9","author":$USER,"body":"",
            "createdAt":"2026-10-20T09:00:00Z","attachments":[]}"""
        server.enqueue(MockResponse().setResponseCode(201).setBody(comment))
        api.addComment(TASK_ID, "", listOf("a", "b"))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""attachmentIds":["a","b"]"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody(comment))
        api.addComment(TASK_ID, "hi")
        assertFalse(
            "the v1 body shape, for older servers",
            server.takeRequest().body.readUtf8().contains("attachmentIds")
        )
    }

    @Test
    fun downloadsAreIdAddressedRevalidatedAndWrittenOnlyOn200() = runBlocking {
        val destination = File(scratch, "login.png")
        val path = "/api/v1/attachments/a0000000-0000-4000-8000-000000000001/content"
        server.enqueue(MockResponse().setBody("PNGBYTES").setHeader("ETag", "\"abc\""))
        assertEquals(DownloadOutcome.Downloaded("\"abc\""), api.download(path, destination))
        assertEquals("PNGBYTES", destination.readText())
        val first = server.takeRequest()
        assertEquals(path, first.path)
        assertNull("byte GETs ignore the workspace", first.getHeader(LiveApi.WORKSPACE_HEADER))
        assertEquals("sid=tok", first.getHeader("Cookie"))

        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(DownloadOutcome.NotModified, api.download(path, destination, "\"abc\""))
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))

        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"gone"}}""")
        )
        try {
            api.download(path, destination)
            fail("expected NOT_FOUND")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.NOT_FOUND, e.code)
        }
        assertEquals("an error never replaces the file", "PNGBYTES", destination.readText())
        assertEquals("no .part file is left behind", listOf("login.png"), scratch.list()!!.toList())
    }

    @Test
    fun pathsOutsideAttachmentsAreRefusedWithoutARequest() = runBlocking {
        for (path in listOf(
            "/api/v1/auth/me",
            "https://evil.example/api/v1/attachments/x/content",
            "/api/v1/attachments/../users"
        )) {
            try {
                api.attachmentData(path)
                fail(path)
            } catch (e: ApiError) {
                assertEquals(ApiError.Code.VALIDATION, e.code)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aServerUnderAPathPrefixKeepsIt() = runBlocking {
        val prefixed =
            LiveApi(server.url("/mp").toString(), tokens, OkHttpClient(), decodeDispatcher = Dispatchers.Unconfined)
        server.enqueue(MockResponse().setBody("x"))
        prefixed.attachmentData("/api/v1/attachments/a/thumbnail?size=small")
        assertEquals("/mp/api/v1/attachments/a/thumbnail?size=small", server.takeRequest().path)
    }
}
