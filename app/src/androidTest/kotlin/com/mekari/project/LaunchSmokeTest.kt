package com.mekari.project

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator smoke test (CI `instrumented`): launches on the fake API, signed in, to My Tasks. */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun launchWithFakeApiShowsMyTasks() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(LaunchFlags.FAKE_API, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag(TestTags.MY_TASKS)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(TestTags.MY_TASKS).assertIsDisplayed()
            compose.onNodeWithTag(TestTags.ADD_TASK_FIELD).assertIsDisplayed()
        }
    }
}
