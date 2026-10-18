package com.mekari.project

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mekari.project.core.DeepLink
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.feature.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR 5 review: incoming intents are untrusted; only the action and a UUID-only link are ever used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MekariApplication::class)
class IntentHandlingTest : AppFlowTestBase() {
    private val container get() = ApplicationProvider.getApplicationContext<MekariApplication>().container

    private fun deliver(intent: Intent) {
        scenario!!.onActivity { it.handleIntent(intent) }
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test
    fun aNewIntentWithTheTestExtrasIsIgnored() {
        launch()
        waitForTag(TestTags.MY_TASKS)
        val session = container.session(fakeApi = true)
        deliver(Intent().putExtra(LaunchFlags.FAKE_API, true).putExtra(LaunchFlags.SIGNED_OUT, true))
        compose.waitForIdle()
        assertSame("same session", session, container.session(fakeApi = false))
        assertTrue("still signed in", session.state.value.auth is Auth.SignedIn)
        assertNull(container.pendingLink.value)
        assertFalse(container.pendingQuickAdd.value)
    }

    @Test
    fun onlyUuidTaskAndProjectLinksAreAccepted() {
        launch()
        waitForTag(TestTags.MY_TASKS)
        val rejected = listOf(
            "mekariproject://task/../users",
            "mekariproject://task/not-a-uuid",
            "mekariproject://task/${DemoIds.TASK_WITH_STORIES}/comments",
            "mekariproject://evil/${DemoIds.TASK_WITH_STORIES}",
            "mekariproject://task",
            "https://example.com/task/${DemoIds.TASK_WITH_STORIES}",
            "mekariproject://task/%2E%2E%2Fusers"
        )
        for (uri in rejected) {
            deliver(view(uri))
            assertNull("accepted $uri", container.pendingLink.value)
        }
        deliver(Intent(Intent.ACTION_VIEW)) // no data at all
        assertNull(container.pendingLink.value)
    }

    @Test
    fun aValidLinkOnlyNavigates() {
        launch()
        waitForTag(TestTags.MY_TASKS)
        val before = container.session(fakeApi = true).scope.value?.tasks?.get(DemoIds.TASK_WITH_STORIES)
        deliver(view("mekariproject://task/${DemoIds.TASK_WITH_STORIES}"))
        waitForTag(TestTags.TASK_DETAIL)
        val after = container.session(fakeApi = true).scope.value?.tasks?.get(DemoIds.TASK_WITH_STORIES)
        assertEquals("opening a link never changes the task", before?.completed, after?.completed)
        assertNull("consumed", container.pendingLink.value)
    }

    @Test
    fun theQuickAddShortcutTrustsOnlyItsAction() {
        launch()
        waitForTag(TestTags.MY_TASKS)
        deliver(
            Intent(MainActivity.ACTION_QUICK_ADD).putExtra(LaunchFlags.FAKE_API, true).putExtra("title", "Injected")
        )
        waitForTag(TestTags.QUICK_ADD_FIELD)
        assertEquals("", compose.onNodeWithTagText(TestTags.QUICK_ADD_FIELD))
    }

    @Test
    fun deepLinkParsingIsUuidOnly() {
        assertEquals(
            DeepLink.Task(DemoIds.TASK_WITH_STORIES),
            DeepLink.parse("mekariproject://task/${DemoIds.TASK_WITH_STORIES}")
        )
        assertNull(DeepLink.parse("mekariproject://project/42"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagText(tag: String): String =
        onNode(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { null }?.text.orEmpty()
}
