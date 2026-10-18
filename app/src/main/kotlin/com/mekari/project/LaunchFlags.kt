package com.mekari.project

import android.content.Intent

/** Launch extras for tests and device QA (the Android form of the Mac's `-UITestFakeAPI`). */
object LaunchFlags {
    /** Boolean extra: run against the fake API with demo data instead of a server. */
    const val FAKE_API = "com.mekari.project.UI_TEST_FAKE_API"

    fun usesFakeApi(intent: Intent?): Boolean = intent?.getBooleanExtra(FAKE_API, false) ?: false
}
