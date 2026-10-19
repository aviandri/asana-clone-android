package com.mekari.project

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Every DataStore change goes through here: [enqueue] for settings and baselines, applied strictly in call
 * order by ONE consumer, and [writeNow] for the session token, which returns once the change is on disk.
 *
 * The consumer must run OFF main ([consumer] is an IO scope in the app). DataStore runs an edit's transform
 * in the caller's context, so a queued edit started from main needs main to finish. [writeNow] blocks its
 * thread, and that is main for a sign-in or a sign-out: an edit queued on main in flight at that moment
 * would never finish, and the token's edit would wait behind it forever (review of #11, finding 1).
 */
internal class PreferenceWrites(
    private val store: DataStore<Preferences>,
    consumer: CoroutineScope,
    private val onError: (IOException) -> Unit = {}
) {
    private val queue = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    init {
        consumer.launch {
            for (change in queue) {
                try {
                    store.edit { change(it) }
                } catch (e: IOException) {
                    // One failed write mustn't stop the ones after it.
                    onError(e)
                }
            }
        }
    }

    /** Queues a change; from any thread, applied in call order. */
    fun enqueue(change: (MutablePreferences) -> Unit) {
        queue.trySend(change)
    }

    /** Applies [change] now, blocking until it's on disk. Throws the IOException if it can't be written. */
    fun writeNow(change: (MutablePreferences) -> Unit) {
        runBlocking { store.edit { change(it) } }
    }
}
