package com.mekari.project.ui.attachments

import com.mekari.project.core.ApiError
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentKind
import com.mekari.project.core.AttachmentLimits
import com.mekari.project.core.FieldIssue
import com.mekari.project.core.UserSummary
import com.mekari.project.core.fake.DemoIds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attachment copy and helpers match the web and Mac apps (BE §13). */
class AttachmentFormatTest {
    private val alice = UserSummary(DemoIds.ALICE, "Alice", "alice@example.com", "#336699")

    private fun file(name: String, mime: String? = "application/pdf", size: Long? = 2_411_724) = Attachment(
        id = DemoIds.ATTACHMENT_PDF,
        workspaceId = DemoIds.MEKARI,
        taskId = DemoIds.TASK_WITH_ATTACHMENTS,
        kind = AttachmentKind.FILE,
        uploader = alice,
        createdAt = Instant.EPOCH,
        name = name,
        mimeType = mime,
        size = size,
        contentPath = "/api/v1/attachments/${DemoIds.ATTACHMENT_PDF}/content"
    )

    @Test
    fun sizesUseBinaryUnitsLikeTheLimit() {
        assertEquals("", AttachmentFormat.bytes(null))
        assertEquals("0 B", AttachmentFormat.bytes(0))
        assertEquals("1023 B", AttachmentFormat.bytes(1023))
        assertEquals("1 KB", AttachmentFormat.bytes(1024))
        assertEquals("1.5 KB", AttachmentFormat.bytes(1536))
        assertEquals("2.3 MB", AttachmentFormat.bytes(2_411_724))
        assertEquals("100 MB", AttachmentFormat.bytes(AttachmentLimits.MAX_UPLOAD_BYTES))
    }

    @Test
    fun uploadErrorsUseTheWebCopyAndTheServersWordsFor429And507() {
        val tooLarge = AttachmentLimits.tooLarge()
        assertEquals("big.mov is larger than 100 MB.", AttachmentFormat.errorMessage(tooLarge, "big.mov"))
        assertFalse(AttachmentFormat.isRetryable(tooLarge))

        val busy = ApiError(ApiError.Code.RATE_LIMITED, 429, "You already have 3 uploads running.")
        assertEquals("You already have 3 uploads running.", AttachmentFormat.errorMessage(busy, "a.png"))
        assertTrue(AttachmentFormat.isRetryable(busy))
        assertEquals(
            "Too many uploads. Try again in a few minutes.",
            AttachmentFormat.errorMessage(ApiError(ApiError.Code.RATE_LIMITED, null, ""), "a.png")
        )
        val full = ApiError(ApiError.Code.INSUFFICIENT_STORAGE, 507, "The server is low on space.")
        assertEquals("The server is low on space.", AttachmentFormat.errorMessage(full, "a.png"))
        assertEquals(
            "You can't add attachments here.",
            AttachmentFormat.errorMessage(ApiError(ApiError.Code.FORBIDDEN, 403, "nope"), "a.png")
        )
    }

    @Test
    fun aBareStatusCountsLikeItsCode() {
        // A proxy's page: no JSON body, so no code, only the status.
        val forbidden = ApiError(ApiError.Code("HTTP_403"), 403, "Forbidden")
        assertEquals("You can't add attachments here.", AttachmentFormat.errorMessage(forbidden, "a.png"))
        assertFalse(AttachmentFormat.isRetryable(forbidden))
        val tooLarge = ApiError(ApiError.Code("HTTP_413"), 413, "Request Entity Too Large")
        assertEquals("a.png is larger than 100 MB.", AttachmentFormat.errorMessage(tooLarge, "a.png"))
        assertFalse(AttachmentFormat.isRetryable(tooLarge))
    }

    @Test
    fun linkProblemsPreferTheUrlIssue() {
        val invalid = ApiError(
            ApiError.Code.VALIDATION,
            400,
            "Invalid input",
            listOf(FieldIssue(listOf("url"), "Only http and https links can be added"))
        )
        assertEquals("Only http and https links can be added", AttachmentFormat.linkProblem(invalid))
        assertEquals("Can't reach the server", AttachmentFormat.linkProblem(ApiError.transport()))
    }

    @Test
    fun tileLabelsForTalkBack() {
        assertEquals("Attachment, report.pdf, 2.3 MB", AttachmentFormat.label(file("report.pdf")))
        val link = file(
            "x"
        ).copy(kind = AttachmentKind.LINK, name = null, url = "https://www.figma.com/file/abc", title = "Design spec")
        assertEquals("Link, Design spec", AttachmentFormat.label(link))
    }

    @Test
    fun onlyTheServersInlineTypesPreviewInApp() {
        assertEquals(PreviewKind.PDF, AttachmentFormat.previewKind(file("a.pdf")))
        assertEquals(PreviewKind.TEXT, AttachmentFormat.previewKind(file("a.log", "text/plain; charset=utf-8")))
        assertEquals(PreviewKind.IMAGE, AttachmentFormat.previewKind(file("a.bmp", "image/bmp")))
        assertNull(AttachmentFormat.previewKind(file("a.svg", "image/svg+xml")))
        assertNull(AttachmentFormat.previewKind(file("a.html", "text/html")))
        assertNull(AttachmentFormat.previewKind(file("a.pdf").copy(contentPath = null)))
    }

    @Test
    fun typeBadgesShowTheExtension() {
        assertEquals("PDF", AttachmentFormat.typeBadge("application/pdf", "SSO flow.pdf").label)
        assertEquals("XLSX", AttachmentFormat.typeBadge("application/octet-stream", "budget.xlsx").label)
        assertEquals("FILE", AttachmentFormat.typeBadge(null, "README").label)
        assertEquals(
            AttachmentFormat.typeBadge("image/png", "x.png").tone,
            AttachmentFormat.typeBadge("image/jpeg", "y.bin").tone
        )
    }

    @Test
    fun providerBadgesAreNeutralInitials() {
        assertEquals("Fg", ProviderBadge.of("figma")?.initials)
        assertEquals("Google Sheets", ProviderBadge.of("google_sheets")?.name)
        assertNull(ProviderBadge.of("myspace"))
        assertNull(ProviderBadge.of(null))
    }
}
