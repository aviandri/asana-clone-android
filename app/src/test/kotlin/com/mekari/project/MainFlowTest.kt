package com.mekari.project

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mekari.project.core.ApiError
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app through MainActivity on the fake API (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MekariApplication::class)
class MainFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val fixLogin = DemoIds.TASK_WITH_STORIES

    private fun launch(fake: FakeApi = FakeApi(), signedIn: Boolean = true) {
        val app = ApplicationProvider.getApplicationContext<MekariApplication>()
        app.container.session(fakeApi = true, signedIn = signedIn, fake = fake)
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
            .putExtra(LaunchFlags.SIGNED_OUT, !signedIn)
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 5_000) =
        waitUntil(timeoutMs) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 5_000) =
        waitUntil(timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Lets coroutine delays (the 300 ms completion linger) run on Robolectric's paused main looper. */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun scrollsTo(text: String): Boolean = runCatching {
        compose.onNodeWithTag(TestTags.MY_TASKS_LIST).performScrollToNode(hasText(text, substring = true))
    }.isSuccess

    private fun isGone(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun launchesSignedInToMyTasks() {
        launch()
        compose.waitForTag(TestTags.taskRow(fixLogin))
        compose.onNodeWithTag(TestTags.MY_TASKS).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.WORKSPACE_SWITCHER).assertIsDisplayed()
    }

    /** SPEC-ANDROID §6: typing in the add field while rows change (a completion collapses a row). */
    @Test
    fun typingInTheAddFieldWhileRowsChangeKeepsTheText() {
        launch()
        compose.waitForTag(TestTags.taskRow(fixLogin))
        val field = compose.onNodeWithTag(TestTags.ADD_TASK_FIELD)
        field.performClick()
        field.performTextInput("Write release")
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).performClick()
        field.performTextInput(" notes")
        advance(500) // the linger ends and the completed row collapses out
        field.performTextInput(" today")
        assertEquals("Write release notes today", field.text())
    }

    /** SPEC-ANDROID §6: rapid adds each clear the field and land as rows. */
    @Test
    fun rapidAddsInMyTasks() {
        launch()
        compose.waitForTag(TestTags.taskRow(fixLogin))
        val field = compose.onNodeWithTag(TestTags.ADD_TASK_FIELD)
        field.performClick()
        for (n in 1..3) {
            field.performTextInput("Quick task $n")
            field.performImeAction()
            compose.waitUntil(5_000) { field.text().isEmpty() }
        }
        // New personal tasks land in "No date", below the fold: scroll the list to each one.
        for (n in 1..3) compose.waitUntil(5_000) { scrollsTo("Quick task $n") }
    }

    @Test
    fun openingATaskShowsItsTitleAtOnce() {
        launch()
        compose.waitForTag(TestTags.taskRow(fixLogin))
        compose.onNodeWithTag(TestTags.taskRow(fixLogin)).performClick()
        compose.waitForTag(TestTags.TASK_DETAIL)
        compose.waitUntil(5_000) { compose.onNodeWithTag(TestTags.TASK_TITLE).text().isNotEmpty() }
    }

    @Test
    fun signsInFromTheSignInScreen() {
        launch(fake = FakeApi(signedInAs = null), signedIn = false)
        compose.waitForTag(TestTags.SIGN_IN)
        compose.onNodeWithTag(TestTags.SIGN_IN_SERVER).performTextInput("https://demo.invalid")
        compose.onNodeWithTag(TestTags.SIGN_IN_EMAIL).performTextInput("alice@example.com")
        compose.onNodeWithTag(TestTags.SIGN_IN_PASSWORD).performTextInput(DemoIds.PASSWORD)
        compose.onNodeWithTag(TestTags.SIGN_IN_BUTTON).performClick()
        compose.waitForTag(TestTags.taskRow(fixLogin))
    }

    /** A completion the server rejects animates back and says so (SPEC-ANDROID §5). */
    @Test
    fun aRejectedCompletionRollsBackAndSaysSo() {
        val fake = FakeApi()
        launch(fake)
        compose.waitForTag(TestTags.taskRow(fixLogin))
        fake.failNext(FakeEndpoint.UPDATE_TASK, ApiError(ApiError.Code.FORBIDDEN, 403, "Not allowed"))
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).performClick()
        advance(500)
        compose.waitForText("Couldn't complete", timeoutMs = 5_000)
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).assertIsOff()
    }

    @Test
    fun completingKeepsTheRowBrieflyThenItCollapses() {
        launch()
        compose.waitForTag(TestTags.taskRow(fixLogin))
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).performClick()
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).assertIsOn()
        advance(1_000)
        compose.waitUntil(5_000) { isGone(TestTags.taskRow(fixLogin)) }
    }
}
