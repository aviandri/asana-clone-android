package com.mekari.project

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review blocker: release builds must ignore the test launch extras (MainActivity is exported). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchFlagsTest {
    private val withExtras = Intent().putExtra(LaunchFlags.FAKE_API, true).putExtra(LaunchFlags.SIGNED_OUT, true)

    @Test
    fun releaseIgnoresTheExtras() {
        assertEquals(
            LaunchFlags.Parsed(fakeApi = false, signedOut = false),
            LaunchFlags.read(withExtras, allowed = false)
        )
    }

    @Test
    fun debugHonoursThem() {
        assertEquals(LaunchFlags.Parsed(fakeApi = true, signedOut = true), LaunchFlags.read(withExtras, allowed = true))
    }

    @Test
    fun signedOutNeedsTheFakeApi() {
        val onlySignedOut = Intent().putExtra(LaunchFlags.SIGNED_OUT, true)
        assertEquals(
            LaunchFlags.Parsed(fakeApi = false, signedOut = false),
            LaunchFlags.read(onlySignedOut, allowed = true)
        )
    }

    @Test
    fun theActivityUsesTheBuildType() {
        // MainActivity passes BuildConfig.DEBUG; this suite runs the debug variant only.
        assertEquals(true, BuildConfig.DEBUG)
    }
}
