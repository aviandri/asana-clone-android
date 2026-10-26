package com.mekari.project

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attachments on "Fix login redirect" (SPEC-ANDROID §8) against FakeApi: the seeded PNG and text file by
 * others, alice's PDF and LINK, and bob's comment image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MekariApplication::class)
class AttachmentFlowTest : AppFlowTestBase() {
    private val fixLogin = DemoIds.TASK_WITH_ATTACHMENTS

    private fun openTask(fake: FakeApi = FakeApi()) {
        launch(fake)
        waitForTag(TestTags.taskRow(fixLogin))
        compose.onNodeWithTag(TestTags.taskRow(fixLogin)).performClick()
        waitForTag(TestTags.TASK_DETAIL_LIST)
        scrollToTag(TestTags.TASK_DETAIL_LIST, TestTags.ATTACHMENTS)
    }

    private fun scrollToTile(id: String) = scrollToTag(TestTags.TASK_DETAIL_LIST, TestTags.attachmentTile(id))

    @Test
    fun theSeededAttachmentsShowInTheSectionAndUnderTheComment() {
        openTask()
        for (id in listOf(
            DemoIds.ATTACHMENT_PNG,
            DemoIds.ATTACHMENT_PDF,
            DemoIds.ATTACHMENT_TEXT,
            DemoIds.ATTACHMENT_LINK
        )) {
            scrollToTile(id)
        }
        // bob's comment image is in the grid…
        scrollToTile(DemoIds.ATTACHMENT_COMMENT_IMAGE)
        // …and under the comment.
        scrollToTag(TestTags.TASK_DETAIL_LIST, TestTags.COMMENT_ATTACHMENTS)
        waitFor("the image under the comment") {
            compose.onAllNodes(
                hasTestTag(TestTags.attachmentTile(DemoIds.ATTACHMENT_COMMENT_IMAGE)) and
                    hasAnyAncestor(hasTestTag(TestTags.COMMENT_ATTACHMENTS)),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingATextFileOpensTheViewerWithItsText() {
        openTask()
        scrollToTile(DemoIds.ATTACHMENT_TEXT)
        compose.onNodeWithTag(TestTags.attachmentTile(DemoIds.ATTACHMENT_TEXT)).performClick()
        waitForTag(TestTags.ATTACHMENT_VIEWER)
        // The fake serves the file's name as its bytes.
        waitFor("the text preview") {
            compose.onAllNodes(hasText("console.log", substring = true)).fetchSemanticsNodes().size >=
                2
        }
        waitForTag(TestTags.VIEWER_TEXT)
    }

    @Test
    fun deletingAnAttachmentAsksFirstThenRemovesIt() {
        openTask()
        scrollToTile(DemoIds.ATTACHMENT_PDF)
        compose.onNodeWithTag(
            TestTags.attachmentMenu(DemoIds.ATTACHMENT_PDF)
        ).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.ATTACHMENT_DELETE)
        compose.onNodeWithTag(TestTags.ATTACHMENT_DELETE).performClick()
        waitForTag(TestTags.ATTACHMENT_DELETE_CONFIRM)
        compose.onNodeWithTag(TestTags.ATTACHMENT_DELETE_CONFIRM).performClick()
        waitFor("the PDF tile goes") { isGone(TestTags.attachmentTile(DemoIds.ATTACHMENT_PDF)) }
    }

    @Test
    fun othersFilesHaveNoDeleteForAnEditor() {
        openTask()
        scrollToTile(DemoIds.ATTACHMENT_PNG)
        compose.onNodeWithTag(
            TestTags.attachmentMenu(DemoIds.ATTACHMENT_PNG)
        ).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.ATTACHMENT_OPEN_WITH)
        assertTrue("admin's PNG isn't alice's to delete", isGone(TestTags.ATTACHMENT_DELETE))
    }

    @Test
    fun openWithSaysSoWhenNoAppCanOpenTheFile() {
        openTask()
        scrollToTile(DemoIds.ATTACHMENT_PDF)
        compose.onNodeWithTag(
            TestTags.attachmentMenu(DemoIds.ATTACHMENT_PDF)
        ).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.ATTACHMENT_OPEN_WITH)
        compose.onNodeWithTag(TestTags.ATTACHMENT_OPEN_WITH).performClick()
        // Robolectric has no PDF viewer installed.
        waitForText("No app can open this file")
    }

    @Test
    fun aLinkAddedFromTheComposerBecomesAChipAndPostsWithTheComment() {
        val fake = FakeApi()
        openTask(fake)
        compose.onNodeWithTag(TestTags.COMPOSER_ATTACH).performClick()
        waitForTag(TestTags.ATTACH_LINK)
        // The sheet may still be animating: click through semantics.
        compose.onNodeWithTag(TestTags.ATTACH_LINK).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.LINK_URL)
        compose.onNodeWithTag(TestTags.LINK_URL).performTextInput("https://example.com/spec")
        compose.onNodeWithTag(TestTags.LINK_SUBMIT).performClick()
        waitForTag(TestTags.COMPOSER_CHIPS)
        waitFor("Send enabled") {
            compose.onAllNodes(hasTestTag(TestTags.COMMENT_SEND)).fetchSemanticsNodes().firstOrNull()?.config
                ?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithTag(TestTags.COMMENT_SEND).performClick()
        waitFor("the chip went with the comment") { isGone(TestTags.COMPOSER_CHIPS) }
        assertTrue(
            "the posted comment carries the link",
            fake.currentAttachments().any { it.url == "https://example.com/spec" && it.commentId != null && !it.draft }
        )
    }

    @Test
    fun aBadLinkShowsTheProblemInTheDialog() {
        openTask()
        scrollToTag(TestTags.TASK_DETAIL_LIST, TestTags.ATTACHMENTS_ADD)
        compose.onNodeWithTag(TestTags.ATTACHMENTS_ADD).performClick()
        waitForTag(TestTags.ATTACH_LINK)
        compose.onNodeWithTag(TestTags.ATTACH_LINK).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.LINK_URL)
        compose.onNodeWithTag(TestTags.LINK_URL).performTextInput("ftp://example.com/x")
        compose.onNodeWithTag(TestTags.LINK_SUBMIT).performClick()
        // The problem is the field's supporting text, merged into the field.
        waitFor("the link problem") {
            compose.onAllNodes(
                hasTestTag(TestTags.LINK_PROBLEM),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun draftsSurviveOpeningASubtaskAndComingBack() {
        val fake = FakeApi()
        openTask(fake)
        compose.onNodeWithTag(TestTags.COMPOSER_ATTACH).performClick()
        waitForTag(TestTags.ATTACH_LINK)
        compose.onNodeWithTag(TestTags.ATTACH_LINK).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.LINK_URL)
        compose.onNodeWithTag(TestTags.LINK_URL).performTextInput("https://example.com/draft")
        compose.onNodeWithTag(TestTags.LINK_SUBMIT).performClick()
        waitForTag(TestTags.COMPOSER_CHIPS)

        // Forward to a subtask: the task's screen leaves composition, but the task wasn't left.
        val subtask = SemanticsMatcher("a subtask row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag).orEmpty().startsWith("subtask.row.")
        }
        waitFor("a subtask row") {
            runCatching { compose.onNodeWithTag(TestTags.TASK_DETAIL_LIST).performScrollToNode(subtask) }.isSuccess
        }
        compose.onAllNodes(subtask)[0].performClick()
        waitFor("the subtask's screen") { isGone(TestTags.COMPOSER_CHIPS) }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag(TestTags.COMPOSER_CHIPS)
        assertTrue(
            "the draft is still on the server",
            fake.currentAttachments().any { it.url == "https://example.com/draft" && it.draft }
        )
    }
}
