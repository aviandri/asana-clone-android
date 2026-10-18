package com.mekari.project

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mekari.project.core.fake.FakeApi
import java.time.Duration
import org.junit.After
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/** Launches MainActivity on the fake API (Robolectric) and offers the waits the flow tests share. */
abstract class AppFlowTestBase {
    @get:Rule
    val compose = createEmptyComposeRule()

    protected var scenario: ActivityScenario<MainActivity>? = null

    protected fun launch(fake: FakeApi = FakeApi(), signedIn: Boolean = true, intent: Intent? = null) {
        val app = ApplicationProvider.getApplicationContext<MekariApplication>()
        app.container.session(fakeApi = true, signedIn = signedIn, fake = fake)
        val start = (intent ?: Intent()).setClass(app, MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
            .putExtra(LaunchFlags.SIGNED_OUT, !signedIn)
        scenario = ActivityScenario.launch(start)
    }

    @After
    fun closeScenario() {
        scenario?.close()
    }

    protected fun waitForTag(tag: String, timeoutMs: Long = 5_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitForText(text: String, timeoutMs: Long = 5_000) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** waitUntil that says which step stalled. */
    protected fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    /** Scrolls a lazy list (by its tag) until the node with [tag] is composed. */
    protected fun scrollToTag(listTag: String, tag: String) = waitFor("scroll to $tag") {
        runCatching { compose.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    protected fun isGone(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    /** Scrolls a lazy list (by its tag) until a node with [text] is composed. */
    protected fun scrollsTo(listTag: String, text: String): Boolean = runCatching {
        compose.onNodeWithTag(listTag).performScrollToNode(hasText(text, substring = true))
    }.isSuccess

    /** Lets coroutine delays (the completion linger) run on Robolectric's paused main looper. */
    protected fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    protected fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
}
