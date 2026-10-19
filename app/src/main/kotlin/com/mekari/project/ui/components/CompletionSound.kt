package com.mekari.project.ui.components

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.mekari.project.R

/**
 * The optional completion chime (Settings, off by default; SPEC-ANDROID §5).
 *
 * The app's own short sound through [SoundPool], not View.playSoundEffect: that one follows the system
 * "Touch sounds" setting, which is off on many phones, so the in-app toggle did nothing (QA A4). It plays as
 * assistance sonification, the stream system UI sounds use, and only with the ringer on normal and Do Not
 * Disturb off, so silent, vibrate and DND all keep it quiet.
 */
object CompletionSound {
    private const val VOLUME = 0.5f

    private var pool: SoundPool? = null
    private var sampleId = 0

    /** Loads the chime ahead of the first tap (decoding takes a moment); cheap after the first call. */
    @Synchronized
    fun prepare(context: Context): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
        .also {
            sampleId = it.load(context.applicationContext, R.raw.task_complete, 1)
            pool = it
        }

    fun play(context: Context) {
        if (!allowed(context)) return
        // A sample still loading just doesn't play; prepare() normally ran when the setting was read.
        prepare(context).play(sampleId, VOLUME, VOLUME, 1, 0, 1f)
    }

    /** Ringer on normal (not silent / vibrate) and no Do Not Disturb filter. */
    fun allowed(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return true
        val filter = notifications.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}
