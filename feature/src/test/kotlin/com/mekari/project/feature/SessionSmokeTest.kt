package com.mekari.project.feature

import com.mekari.project.core.InMemoryServerSettings
import com.mekari.project.core.InMemoryTokenStore
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeSeed
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSmokeTest {
    @Test
    fun signedInSessionLoadsMyTasksCompletesWithLingerAndSwitches() = runTest {
        val fake = FakeApi(FakeSeed.demo(TestTime.today), signedInAs = "alice@example.com")
        val session = AppSession(
            InMemoryServerSettings(AppSession.DEMO_SERVER),
            InMemoryTokenStore(),
            { _, _ ->
                fake
            },
            InMemoryFeaturePrefs(),
            backgroundScope,
            TestTime.clock
        )
        session.start()
        assertTrue(session.state.value.auth is Auth.SignedIn)
        val scope = session.scope.value!!
        assertEquals(DemoIds.MEKARI, scope.id)

        val myTasks = scope.myTasks
        myTasks.refresh()
        val today = myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds
        assertEquals(2, today.size)

        myTasks.toggleComplete(today[0])
        assertTrue(scope.tasks.isLingering(today[0]))
        assertTrue(today[0] in myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds)
        advanceTimeBy(301)
        runCurrent()
        assertFalse(scope.tasks.isLingering(today[0]))
        assertFalse(myTasks.state.value.groups.flatMap { it.taskIds }.contains(today[0]))

        session.switchWorkspace(DemoIds.ACME_PILOT)
        val next = session.scope.value!!
        assertNotSame(scope, next)
        assertEquals(DemoIds.ACME_PILOT, session.state.value.current?.id)
        assertFalse(session.state.value.isSwitching)
    }
}
