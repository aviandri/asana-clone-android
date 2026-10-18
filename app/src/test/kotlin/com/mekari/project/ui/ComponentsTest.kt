package com.mekari.project.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mekari.project.ui.components.CompletionCheckbox
import com.mekari.project.ui.components.initials
import com.mekari.project.ui.components.parseHexColor
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Mac lesson: the whole box is the target, at least 48 dp, and a tap in its middle toggles. */
    @Test
    fun checkboxTargetIs48dpAndTogglesFromItsMiddle() {
        compose.setContent {
            MekariProjectTheme {
                var done by remember { mutableStateOf(false) }
                CompletionCheckbox(
                    checked = done,
                    title = "Fix login",
                    enabled = true,
                    onToggle = { done = !done },
                    testTag = "box"
                )
            }
        }
        compose.onNodeWithTag("box").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsOff()
        compose.onNodeWithTag("box").performClick()
        compose.onNodeWithTag("box").assertIsOn()
        compose.onNodeWithTag("box").performClick()
        compose.onNodeWithTag("box").assertIsOff()
    }

    /** Read-only checkboxes swallow taps: neither the box nor the row behind reacts. */
    @Test
    fun readOnlyCheckboxSwallowsTheTap() {
        var toggled = false
        var rowClicked = false
        compose.setContent {
            MekariProjectTheme {
                Row(Modifier.testTag("row").clickable { rowClicked = true }) {
                    CompletionCheckbox(
                        checked = false,
                        title = "Fix login",
                        enabled = false,
                        onToggle = { toggled = true },
                        testTag = "box"
                    )
                }
            }
        }
        compose.onNodeWithTag("box").performClick()
        compose.waitForIdle()
        assertFalse("read-only box toggled", toggled)
        assertFalse("the tap fell through to the row", rowClicked)
    }

    @Test
    fun dueLabels() {
        val today = LocalDate.of(2026, 10, 19) // a Monday
        val locale = Locale.US
        assertEquals("Today", Formatting.due(today, today, locale))
        assertEquals("Tomorrow", Formatting.due(today.plusDays(1), today, locale))
        assertEquals("Yesterday", Formatting.due(today.minusDays(1), today, locale))
        assertEquals("Wednesday", Formatting.due(today.plusDays(2), today, locale))
        assertEquals("Nov 4", Formatting.due(LocalDate.of(2026, 11, 4), today, locale))
        assertEquals("Mar 4, 2027", Formatting.due(LocalDate.of(2027, 3, 4), today, locale))
        assertTrue(Formatting.isOverdue(today.minusDays(1), today))
        assertFalse(Formatting.isOverdue(today, today))
    }

    @Test
    fun initialsAndColours() {
        assertEquals("AJ", initials("Alice Johnson"))
        assertEquals("B", initials("bob"))
        assertEquals("?", initials("  "))
        assertEquals(Color(0xFFE0257F), parseHexColor("#E0257F"))
        assertEquals(Color(0xFF9E9E9E), parseHexColor("nope"))
    }
}
