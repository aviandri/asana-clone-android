package com.mekari.project

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke test (CI `instrumented`): launches with the fake-API flag and finds the
 * placeholder. The App shell PR replaces the placeholder check with My Tasks.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun launchWithFakeApiShowsThePlaceholder() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithTag(TestTags.PLACEHOLDER).assertIsDisplayed()
            compose.onNodeWithText("Fake API").assertIsDisplayed()
        }
    }
}
