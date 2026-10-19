package com.mekari.project

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Keystore classification restoreSession relies on (#11 review): needs a real AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class SessionCipherTest {
    @After
    fun dropKey() = SessionCipher.deleteKey()

    private fun opened(sealed: String) = SessionCipher.open(sealed)

    @Test
    fun aSealedTokenOpens() {
        val sealed = SessionCipher.encrypt("token-1")!!
        val opened = opened(sealed)
        assertTrue(opened is Opened.Token)
        assertEquals("token-1", (opened as Opened.Token).value)
    }

    @Test
    fun aTamperedBlobIsCorruptButTheKeyStays() {
        val bytes = Base64.decode(SessionCipher.encrypt("token-1")!!, Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertEquals(Opened.Lost("corrupt"), opened(Base64.encodeToString(bytes, Base64.NO_WRAP)))
        // The key wasn't touched: a fresh seal still opens.
        val again = opened(SessionCipher.encrypt("token-2")!!)
        assertEquals("token-2", (again as Opened.Token).value)
    }

    @Test
    fun shortOrMalformedBlobsAreCorrupt() {
        assertEquals(Opened.Lost("corrupt"), opened("%%% not base64 %%%"))
        assertEquals(Opened.Lost("corrupt"), opened(Base64.encodeToString(ByteArray(20), Base64.NO_WRAP)))
    }

    @Test
    fun withoutTheKeyItIsMissingAndOpeningDoesNotCreateOne() {
        val sealed = SessionCipher.encrypt("token-1")!!
        SessionCipher.deleteKey()
        assertEquals(Opened.Lost("key-missing"), opened(sealed))
        assertEquals("still no key", Opened.Lost("key-missing"), opened(sealed))
        // Sealing makes a new key; the old blob stays unreadable.
        val fresh = SessionCipher.encrypt("token-2")!!
        assertEquals("token-2", (opened(fresh) as Opened.Token).value)
        assertEquals(Opened.Lost("corrupt"), opened(sealed))
    }
}
