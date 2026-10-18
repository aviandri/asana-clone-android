package com.mekari.project.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.mekari.project.ui.components.InlineAddField
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Mac crash lesson, Compose-side: typed text survives list updates and isn't cleared too early. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InlineAddFieldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var field: TextFieldState
    private val rows = mutableStateListOf("Fix login", "Dark mode", "Offline mode")

    private fun setUp(onSubmit: suspend (String) -> Boolean) {
        compose.setContent {
            MekariProjectTheme {
                // The screen owns the field state, outside the lazy items (one per section in the app).
                field = rememberTextFieldState()
                LazyColumn {
                    items(rows, key = { it }) { Text(it) }
                    item(key = "add.section") {
                        InlineAddField(state = field, placeholder = "Add task", onSubmit = onSubmit)
                    }
                }
            }
        }
    }

    @Test
    fun typingWhileRowsChangeKeepsTheText() {
        setUp { true }
        val add = compose.onNodeWithTag("addTask.field")
        add.performClick()
        add.performTextInput("Write")
        // Rows above the field change while the user types: removed, inserted, reordered.
        compose.runOnIdle { rows.removeAt(0) }
        add.performTextInput(" release")
        compose.runOnIdle {
            rows.add(0, "New from server")
            rows.reverse()
        }
        add.performTextInput(" notes")
        compose.runOnIdle { assertEquals("Write release notes", field.text.toString()) }
    }

    @Test
    fun clearsOnlyAfterTheInsertAndOnlyIfUnchanged() {
        val pending = CompletableDeferred<Boolean>()
        val submitted = mutableListOf<String>()
        setUp { title ->
            submitted += title
            pending.await()
        }
        val add = compose.onNodeWithTag("addTask.field")
        add.performClick()
        add.performTextInput("  First task  ")
        add.performImeAction()
        compose.runOnIdle {
            assertEquals(listOf("First task"), submitted)
            assertEquals("not cleared before the row is in", "  First task  ", field.text.toString())
        }
        // The user keeps typing while the create is in flight: their new text must survive.
        add.performTextInput(" and more")
        pending.complete(true)
        compose.runOnIdle { assertEquals("  First task   and more", field.text.toString()) }
    }

    @Test
    fun rapidAddsClearBetweenEachOne() {
        val submitted = mutableListOf<String>()
        setUp { title ->
            submitted += title
            rows.add(title)
            true
        }
        val add = compose.onNodeWithTag("addTask.field")
        add.performClick()
        for (n in 1..3) {
            add.performTextInput("Task $n")
            add.performImeAction()
            compose.runOnIdle { assertEquals("cleared after add $n", "", field.text.toString()) }
        }
        assertEquals(listOf("Task 1", "Task 2", "Task 3"), submitted)
    }
}
