package com.mekari.project

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mekari.project.core.fake.DemoIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC-ANDROID §8 on the emulator, against the fake API's seeded attachments on "Fix login redirect": the
 * image viewer opens on a real device stack (Coil, Dialog), and deleting asks first.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentFlowInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun openTask(): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        // On a phone the row can be below the fold of the lazy My Tasks list.
        waitForTag(TestTags.MY_TASKS_LIST)
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag(TestTags.MY_TASKS_LIST)
                    .performScrollToNode(hasTestTag(TestTags.taskRow(DemoIds.TASK_WITH_ATTACHMENTS)))
            }.isSuccess
        }
        compose.onNodeWithTag(TestTags.taskRow(DemoIds.TASK_WITH_ATTACHMENTS)).performClick()
        waitForTag(TestTags.TASK_DETAIL_LIST)
        return scenario
    }

    private fun scrollTo(tag: String) = compose.waitUntil(10_000) {
        runCatching { compose.onNodeWithTag(TestTags.TASK_DETAIL_LIST).performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    @Test
    fun tappingAnImageOpensTheViewer() {
        openTask().use {
            scrollTo(TestTags.attachmentTile(DemoIds.ATTACHMENT_PNG))
            compose.onNodeWithTag(TestTags.attachmentTile(DemoIds.ATTACHMENT_PNG)).performClick()
            waitForTag(TestTags.ATTACHMENT_VIEWER)
            waitForTag(TestTags.VIEWER_IMAGES)
        }
    }

    @Test
    fun deletingAnAttachmentAsksFirst() {
        openTask().use {
            val tile = TestTags.attachmentTile(DemoIds.ATTACHMENT_LINK)
            scrollTo(tile)
            compose.onNodeWithTag(
                TestTags.attachmentMenu(DemoIds.ATTACHMENT_LINK)
            ).performSemanticsAction(SemanticsActions.OnClick)
            waitForTag(TestTags.ATTACHMENT_DELETE)
            compose.onNodeWithTag(TestTags.ATTACHMENT_DELETE).performClick()
            waitForTag(TestTags.ATTACHMENT_DELETE_CONFIRM)
            compose.onNodeWithTag(TestTags.ATTACHMENT_DELETE_CONFIRM).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tile)).fetchSemanticsNodes().isEmpty() }
        }
    }
}
