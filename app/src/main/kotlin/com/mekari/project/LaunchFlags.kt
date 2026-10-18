package com.mekari.project

import android.content.Intent

/** Launch extras for tests and device QA (the Android form of the Mac's `-UITestFakeAPI`). */
object LaunchFlags {
    /** Boolean extra: run against the fake API with demo data instead of a server. */
    const val FAKE_API = "com.mekari.project.UI_TEST_FAKE_API"

    /** Boolean extra (with [FAKE_API]): start signed out, at the sign-in screen. */
    const val SIGNED_OUT = "com.mekari.project.UI_TEST_SIGNED_OUT"

    fun usesFakeApi(intent: Intent?): Boolean = intent?.getBooleanExtra(FAKE_API, false) ?: false

    fun startsSignedOut(intent: Intent?): Boolean = intent?.getBooleanExtra(SIGNED_OUT, false) ?: false
}
