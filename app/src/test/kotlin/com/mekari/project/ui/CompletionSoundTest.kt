package com.mekari.project.ui

import android.app.NotificationManager
import android.media.AudioManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mekari.project.R
import com.mekari.project.ui.components.CompletionEffects
import com.mekari.project.ui.components.CompletionSound
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** QA A4: the chime is the app's own (not "Touch sounds"), follows the in-app toggle, silent mode and DND. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompletionSoundTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val view = View(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun played() = shadowOf(CompletionSound.prepare(context)).wasResourcePlayed(R.raw.task_complete)

    @Before
    fun ringerOn() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(CompletionSound.prepare(context)).clearPlayed()
    }

    @Test
    fun playsWhenTheSettingIsOn() {
        CompletionEffects.play(view, completing = true, title = "Ship it", sound = true)
        assertTrue(played())
    }

    @Test
    fun silentWhenTheSettingIsOffOrReopening() {
        CompletionEffects.play(view, completing = true, title = "Ship it", sound = false)
        CompletionEffects.play(view, completing = false, title = "Ship it", sound = true)
        assertFalse(played())
    }

    @Test
    fun silentInSilentOrVibrateMode() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            audio.ringerMode = mode
            CompletionEffects.play(view, completing = true, title = "Ship it", sound = true)
        }
        assertFalse(played())
    }

    @Test
    fun silentUnderDoNotDisturb() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        CompletionEffects.play(view, completing = true, title = "Ship it", sound = true)
        assertFalse(played())
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }
}
