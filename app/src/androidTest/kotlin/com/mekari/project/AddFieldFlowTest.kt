package com.mekari.project

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC-ANDROID §6 on the emulator: the inline add field keeps its text while rows change around it,
 * and rapid adds clear it each time (the Mac crash lesson, Android-side).
 */
@RunWith(AndroidJUnit4::class)
class AddFieldFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launchMyTasks(): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        compose.waitUntil(10_000) { compose.onAllNodes(openCheckbox).fetchSemanticsNodes().isNotEmpty() }
        return scenario
    }

    /** Any task checkbox that's still open (the fake session is shared by the tests in this process). */
    private val openCheckbox = SemanticsMatcher("open task checkbox") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        tag.startsWith("task.checkbox.") &&
            node.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.Off
    }

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun typingWhileRowsChangeKeepsTheText() {
        launchMyTasks().use {
            val field = compose.onNodeWithTag(TestTags.ADD_TASK_FIELD)
            field.performClick()
            field.performTextInput("Write release")
            // A completion: the row lingers 300 ms, then collapses out while the user keeps typing.
            compose.onAllNodes(openCheckbox).onFirst().performClick()
            field.performTextInput(" notes")
            Thread.sleep(800)
            field.performTextInput(" today")
            assertEquals("Write release notes today", field.text())
        }
    }

    @Test
    fun rapidAddsClearTheFieldAndLandAsRows() {
        launchMyTasks().use {
            val field = compose.onNodeWithTag(TestTags.ADD_TASK_FIELD)
            field.performClick()
            val stamp = System.currentTimeMillis() % 100_000
            for (n in 1..3) {
                field.performTextInput("Rapid $stamp-$n")
                field.performImeAction()
                compose.waitUntil(10_000) { field.text().isEmpty() }
            }
            for (n in 1..3) {
                compose.waitUntil(10_000) {
                    runCatching {
                        compose.onNodeWithTag(
                            TestTags.MY_TASKS_LIST
                        ).performScrollToNode(hasText("Rapid $stamp-$n", substring = true))
                    }.isSuccess
                }
            }
        }
    }
}
