package com.mekari.project

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI on the JVM via Robolectric (SPEC-ANDROID §6 `unit`); no emulator needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceholderRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToThePlaceholder() {
        compose.onNodeWithTag(TestTags.PLACEHOLDER).assertIsDisplayed()
        compose.onNodeWithText("Mekari Project").assertIsDisplayed()
    }
}
