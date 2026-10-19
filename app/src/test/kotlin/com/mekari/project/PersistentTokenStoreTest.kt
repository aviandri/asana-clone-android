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

    @Test
    fun everyEndOfASessionIsLoggedWithItsReasonAndNeverTheToken() {
        val log = mutableListOf<String>()
        val store = PersistentTokenStore("secret-1", log::add) { }
        val worker = store.forSource("worker")
        val app = store.forSource("app")

        assertFalse(worker.clearIf("secret-0"))
        assertEquals("secret-1", store.load())
        assertTrue(worker.clearIf("secret-1"))
        assertFalse(app.clearIf("secret-1"))
        store.save("secret-2")
        app.clear()
        store.clear() // already signed out: nothing to report

        assertEquals(
            listOf(
                "401 ignored (worker): sent with an older session",
                "session cleared: 401-clear(worker)",
                "401 ignored (app): already signed out",
                "session cleared: user-sign-out"
            ),
            log
        )
        assertTrue(log.none { "secret" in it })
    }

    @Test
    fun aViewForwardsEverythingToTheOneStore() {
        val persisted = mutableListOf<String?>()
        val store = PersistentTokenStore(null) { persisted += it }
        val view = store.forSource("app")
        view.save("t")
        assertEquals("t", store.load())
        assertEquals("t", view.load())
        assertTrue(view.clearIf("t"))
        assertNull(store.load())
        assertEquals(listOf("t", null), persisted)
    }

    @Test
    fun launchRestoreKeepsWhatAKeystoreHiccupCouldNotOpenAndDropsOnlyWhatIsLost() {
        fun restore(vararg results: Opened): Pair<Restored, Int> {
            var calls = 0
            val restored = restoreSession("blob", { results[calls++] }, pause = {})
            return restored to calls
        }

        val absent = restoreSession(null, { error("not opened") }, pause = {})
        assertEquals(null to "absent", absent.token to absent.reason)
        assertFalse(absent.dropBlob)

        val (ok, _) = restore(Opened.Token("t"))
        assertEquals("t" to "restored", ok.token to ok.reason)
        assertFalse(ok.dropBlob)

        val (missing, _) = restore(Opened.Lost("key-missing"))
        assertEquals(null to "decrypt-failed(key-missing)", missing.token to missing.reason)
        assertTrue("never readable again: drop the blob", missing.dropBlob)

        val (corrupt, _) = restore(Opened.Lost("corrupt"))
        assertTrue(corrupt.dropBlob)

        val (recovered, tries) = restore(Opened.Unavailable("KeyStoreException"), Opened.Token("t"))
        assertEquals("a retry opens it", "t", recovered.token)
        assertEquals(2, tries)

        val unavailable = Opened.Unavailable("KeyStoreException")
        val (kept, attempts) = restore(unavailable, unavailable, unavailable, Opened.Token("never asked"))
        assertNull(kept.token)
        assertEquals(3, attempts)
        assertFalse("a transient failure keeps the blob for the next launch", kept.dropBlob)
        assertEquals("decrypt-failed(keystore:KeyStoreException, 3 tries; kept for next launch)", kept.reason)
    }

    @Test
    fun anOpenedTokenNeverPrints() {
        assertFalse("secret" in Opened.Token("secret").toString())
    }

    @Test
    fun anEarlierFailedWriteDropsWhateverIsStored() {
        val restored = restoreSession("blob", { Opened.Token("signed-out token") }, mustClear = true, pause = {})
        assertNull("a sign-out that didn't reach disk isn't undone", restored.token)
        assertTrue(restored.dropBlob)
        assertEquals("cleared(an earlier session write failed)", restored.reason)
        assertTrue(restoreSession(null, { error("not opened") }, mustClear = true).dropBlob)
    }

    @Test
    fun aDelayedRestoreNeverOverwritesASignInAndIsNotPersisted() {
        val persisted = mutableListOf<String?>()
        val store = PersistentTokenStore(null) { persisted += it }
        assertTrue(store.adopt("kept"))
        assertEquals("kept", store.load())
        assertFalse("already has one", store.adopt("other"))
        store.clear()
        store.save("fresh")
        assertFalse(store.adopt("kept"))
        assertEquals("fresh", store.load())
        assertEquals(listOf(null, "fresh"), persisted)
    }
}
