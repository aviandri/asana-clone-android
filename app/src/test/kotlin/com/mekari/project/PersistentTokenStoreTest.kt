package com.mekari.project

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** clearIf must be atomic with save: a late 401 for an old token never wipes a fresh sign-in. */
class PersistentTokenStoreTest {
    @Test
    fun clearIfOnlyClearsTheExpectedToken() {
        val persisted = mutableListOf<String?>()
        val store = PersistentTokenStore("old") { persisted += it }
        assertFalse(store.clearIf("other"))
        assertEquals("old", store.load())
        assertTrue(store.clearIf("old"))
        assertNull(store.load())
        assertEquals(listOf<String?>(null), persisted)
    }

    @Test
    fun aLate401RacingAReLoginNeverLeavesItSignedOut() {
        repeat(2_000) { round ->
            val persisted = Collections.synchronizedList(mutableListOf<String?>())
            val store = PersistentTokenStore(null) { persisted += it }
            store.save("old")
            val go = CountDownLatch(1)
            var cleared = false
            val late401 = thread {
                go.await()
                cleared = store.clearIf("old")
            }
            val relogin = thread {
                go.await()
                store.save("new")
            }
            go.countDown()
            late401.join()
            relogin.join()
            // Either the clear came first (then "new"), or "new" was already in and the clear was refused.
            assertEquals("round $round: memory", "new", store.load())
            assertEquals("round $round: storage ends on the same value", "new", persisted.last())
            if (cleared) {
                assertEquals("round $round", listOf("old", null, "new"), persisted.toList())
            } else {
                assertEquals("round $round", listOf("old", "new"), persisted.toList())
            }
        }
    }
}
