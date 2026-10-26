package com.mekari.project

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mekari.project.core.fake.FakeApi

/**
 * Instrumented tests run in one process, so they'd share the container's session and each other's edits.
 * Call this in @Before: a fresh fake session (demo data) that MainActivity then picks up.
 */
internal fun freshFakeSession(fake: FakeApi = FakeApi()) {
    val app = ApplicationProvider.getApplicationContext<MekariApplication>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        app.container.resetSessionForTests()
        app.container.session(fakeApi = true, fake = fake)
    }
}
