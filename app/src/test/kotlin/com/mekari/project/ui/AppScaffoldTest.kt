package com.mekari.project.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mekari.project.TestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppScaffoldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setUp() {
        compose.setContent {
            MekariProjectTheme {
                var selected by remember { mutableStateOf(TopDestination.MY_TASKS) }
                AppScaffold(
                    selected = selected,
                    onSelect = { selected = it },
                    workspace = WorkspaceBadge("Acme Pilot", "#4186e0", isGuest = true),
                    inboxUnread = 3,
                    onSwitchWorkspace = {},
                    onOpenSettings = {}
                ) { modifier -> Text(selected.label, modifier.testTag("content")) }
            }
        }
    }

    @Test
    fun bottomBarSwitchesDestinationsAndShowsTheInboxBadge() {
        setUp()
        compose.onNodeWithTag(TestTags.NAV_MY_TASKS).assertIsSelected()
        compose.onNodeWithTag(TestTags.NAV_INBOX).performClick()
        compose.onNodeWithTag(TestTags.NAV_INBOX).assertIsSelected()
        compose.onNodeWithTag(TestTags.INBOX_BADGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Acme Pilot").assertIsDisplayed()
        compose.onNodeWithText("Guest").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun largeScreensUseANavigationRail() {
        setUp()
        compose.onNodeWithTag(TestTags.NAV_RAIL).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.NAV_SEARCH).performClick()
        compose.onNodeWithTag(TestTags.NAV_SEARCH).assertIsSelected()
    }

    @Test
    fun badgeCapsAt99() {
        assertEquals("7", badgeText(7))
        assertEquals("99+", badgeText(140))
    }
}
