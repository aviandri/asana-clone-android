package com.mekari.project

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The token's synchronous write vs the settings queue, on a real DataStore (#11 review, finding 1). */
class PreferenceWritesTest {
    private val dir: File = Files.createTempDirectory("prefs").toFile()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "test.preferences_pb") }

    /** A stand-in for the main thread: one thread, so blocking it blocks everything dispatched to it. */
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-main").apply { isDaemon = true } }
    private val main = mainExecutor.asCoroutineDispatcher()

    private val queued = stringPreferencesKey("queued")
    private val token = stringPreferencesKey("token")
    private val counter = intPreferencesKey("counter")

    @After
    fun tearDown() {
        storeScope.cancel()
        mainExecutor.shutdownNow()
        dir.deleteRecursively()
    }

    @Test
    fun aTokenWriteOnMainFinishesWhileAQueuedWriteIsInFlight() {
        val writes = PreferenceWrites(store, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val done = CompletableFuture<Unit>()
        CoroutineScope(main).launch {
            writes.enqueue { it[queued] = "baseline" } // e.g. an inbox baseline, queued from main
            yield() // a consumer on main would now be inside its edit, its transform waiting for main
            writes.writeNow { it[token] = "sealed" } // sign-in: blocks main until the token is on disk
            done.complete(Unit)
        }
        // Deadlocked, this never completes (the consumer used to run on Main.immediate).
        done.get(10, TimeUnit.SECONDS)
        val stored = runBlocking { store.data.first() }
        assertEquals("sealed", stored[token])
        assertEquals("baseline", runBlocking { waitFor { store.data.first()[queued] } })
    }

    @Test
    fun queuedChangesApplyInCallOrder() {
        val writes = PreferenceWrites(store, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        for (i in 1..200) writes.enqueue { it[counter] = i }
        assertEquals(200, runBlocking { waitFor { store.data.first()[counter]?.takeIf { it == 200 } } })
    }

    private suspend fun <T : Any> waitFor(read: suspend () -> T?): T {
        repeat(500) {
            read()?.let { return it }
            kotlinx.coroutines.delay(10)
        }
        error("timed out")
    }
}
