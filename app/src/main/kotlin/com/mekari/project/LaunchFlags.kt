package com.mekari.project

import android.content.Intent

/** Launch extras for tests and device QA (the Android form of the Mac's `-UITestFakeAPI`). */
object LaunchFlags {
    /** Boolean extra: run against the fake API with demo data instead of a server. */
    const val FAKE_API = "com.mekari.project.UI_TEST_FAKE_API"

    /** Boolean extra (with [FAKE_API]): start signed out, at the sign-in screen. */
    const val SIGNED_OUT = "com.mekari.project.UI_TEST_SIGNED_OUT"

    data class Parsed(val fakeApi: Boolean, val signedOut: Boolean)

    /**
     * The test flags, honoured only when [allowed] (debug builds: `BuildConfig.DEBUG`). MainActivity is the
     * exported launcher, so in a release build any app could otherwise start us on demo data, where edits
     * vanish and a "sign-in" would go nowhere.
     */
    fun read(intent: Intent?, allowed: Boolean): Parsed {
        if (!allowed || intent == null) return Parsed(fakeApi = false, signedOut = false)
        val fake = intent.getBooleanExtra(FAKE_API, false)
        return Parsed(fakeApi = fake, signedOut = fake && intent.getBooleanExtra(SIGNED_OUT, false))
    }
}
