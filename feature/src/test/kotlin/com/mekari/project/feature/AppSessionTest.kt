package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.ApiEvent
import com.mekari.project.core.DeepLink
import com.mekari.project.core.InMemoryServerSettings
import com.mekari.project.core.InMemoryTokenStore
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSessionTest {
    private fun TestScope.session(fake: FakeApi, server: String? = AppSession.DEMO_SERVER) = AppSession(
        InMemoryServerSettings(server),
        InMemoryTokenStore(),
        { _, _ -> fake },
        InMemoryFeaturePrefs(),
        backgroundScope,
        TestTime.clock
    )

    /** The workspace header the last scoped request carried. */
    private fun FakeApi.lastHeader() = calls.last {
        it.endpoint == FakeEndpoint.PROJECTS ||
            it.endpoint == FakeEndpoint.MY_TASKS
    }.workspaceId

    @Test
    fun startRestoresASessionOrEndsSignedOut() = runTest {
        val signedIn = startedSession()
        assertTrue(signedIn.state.value.auth is Auth.SignedIn)
        assertEquals(DemoIds.MEKARI, signedIn.scope.value?.id)
        assertEquals(DemoIds.MEKARI, signedIn.state.value.current?.id)
        assertEquals(listOf("Acme Pilot", "Mekari"), signedIn.state.value.all.map { it.name })
        assertEquals(1, signedIn.state.value.generation)
        assertTrue(signedIn.scope.value!!.poller.isRunning)

        val noServer = session(demoFake(), server = null)
        noServer.start()
        assertEquals(Auth.SignedOut, noServer.state.value.auth)

        val signedOut = session(demoFake(email = null))
        signedOut.start()
        assertEquals(Auth.SignedOut, signedOut.state.value.auth)
        assertNull(signedOut.scope.value)

        // Offline at launch: signed out, says why, and keeps the token for next time.
        val offline = demoFake()
        offline.failNext(FakeEndpoint.ME, ApiError.transport())
        val offlineSession = session(offline)
        offlineSession.start()
        assertEquals(Auth.SignedOut, offlineSession.state.value.auth)
        assertEquals(SignInError.Transport, offlineSession.state.value.signInError)
        assertTrue(offline.hasSession)
    }

    @Test
    fun signInValidatesTheServerAndMapsErrors() = runTest {
        val fake = demoFake(email = null)
        val settings = InMemoryServerSettings()
        val session =
            AppSession(settings, InMemoryTokenStore(), { _, _ ->
                fake
            }, InMemoryFeaturePrefs(), backgroundScope, TestTime.clock)

        session.signIn("not a url", "alice@example.com", "x")
        assertEquals(SignInError.BadServer, session.state.value.signInError)

        fake.failNext(FakeEndpoint.HEALTH, ApiError.transport())
        session.signIn("project.example.com", "alice@example.com", DemoIds.PASSWORD)
        assertEquals(SignInError.Transport, session.state.value.signInError)

        fake.failNext(FakeEndpoint.HEALTH, ApiError(ApiError.Code("HTTP 404"), 404, "Not a Mekari Project server"))
        session.signIn("project.example.com", "alice@example.com", DemoIds.PASSWORD)
        assertEquals(SignInError.BadServer, session.state.value.signInError)

        session.signIn("project.example.com", "alice@example.com", "wrong")
        assertEquals(SignInError.WrongCredentials, session.state.value.signInError)
        assertEquals("Wrong email or password", session.state.value.signInError?.message)

        fake.failNext(FakeEndpoint.LOGIN, ApiError(ApiError.Code.RATE_LIMITED, 429, "Slow down"))
        session.signIn("project.example.com", "alice@example.com", DemoIds.PASSWORD)
        assertEquals(SignInError.RateLimited, session.state.value.signInError)
        assertNull(settings.serverUrl)

        session.signIn(" project.example.com/ ", " alice@example.com ", DemoIds.PASSWORD)
        assertNull(session.state.value.signInError)
        assertFalse(session.state.value.isSigningIn)
        assertTrue(session.state.value.auth is Auth.SignedIn)
        assertEquals("https://project.example.com", settings.serverUrl)
        assertEquals(DemoIds.MEKARI, session.scope.value?.id)
    }

    @Test
    fun switchingReplacesTheScopeAndMovesTheHeaderWithIt() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        val before = session.scope.value!!
        session.switchWorkspace(DemoIds.ACME_PILOT)
        val after = session.scope.value!!
        assertTrue(before !== after)
        assertFalse("the old scope's poller is stopped", before.poller.isRunning)
        assertEquals(DemoIds.ACME_PILOT, after.id)
        assertEquals("Switched to Acme Pilot", session.state.value.notice)
        assertEquals(2, session.state.value.generation)
        assertFalse(session.state.value.isSwitching)
        assertTrue(session.state.value.isAdmin)
        after.projects.load()
        assertEquals(DemoIds.ACME_PILOT, fake.lastHeader())
        session.dismissNotice()
        assertNull(session.state.value.notice)

        // Already there (app and server): nothing to do.
        val switches = fake.calls.count { it.endpoint == FakeEndpoint.SWITCH_WORKSPACE }
        session.switchWorkspace(DemoIds.ACME_PILOT)
        assertEquals(switches, fake.calls.count { it.endpoint == FakeEndpoint.SWITCH_WORKSPACE })
    }

    @Test
    fun switchesRunOneAtATimeAndASupersededOneIsSkippedBeforeItsPost() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        fake.hold(FakeEndpoint.SWITCH_WORKSPACE)
        val a = launch { session.switchWorkspace(DemoIds.ACME_PILOT) }
        runCurrent()
        val b = launch { session.switchWorkspace(DemoIds.MEKARI) }
        runCurrent()
        val c = launch { session.switchWorkspace(DemoIds.ACME_PILOT) }
        runCurrent()
        assertEquals(3, session.queuedJobs)
        assertTrue(session.state.value.isSwitching)
        assertEquals(1, fake.parkedCount(FakeEndpoint.SWITCH_WORKSPACE))

        fake.release(FakeEndpoint.SWITCH_WORKSPACE) // A's POST answers; A was superseded, so it doesn't commit
        a.join()
        b.join()
        assertEquals("B was skipped before its POST", 1, session.skippedJobs)
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        fake.release(FakeEndpoint.SWITCH_WORKSPACE, all = true) // C's POST
        c.join()
        assertEquals(DemoIds.ACME_PILOT, session.state.value.current?.id)
        assertEquals(2, fake.calls.count { it.endpoint == FakeEndpoint.SWITCH_WORKSPACE })
        assertFalse(session.state.value.isSwitching)
        assertEquals(DemoIds.ACME_PILOT, fake.me().currentWorkspace?.id) // the server's default matches
    }

    @Test
    fun lostAccessAdoptsAnotherWorkspace() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        session.switchWorkspace(DemoIds.ACME_PILOT)
        session.scope.value!!.poller.stop()
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        session.scope.value!!.myTasks.refresh() // 404 → WorkspaceLost → recovery
        runCurrent()
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        assertEquals(DemoIds.MEKARI, session.scope.value?.id)
        assertEquals("You no longer have access to Acme Pilot", session.state.value.notice)
        assertFalse(session.state.value.isSwitching)
    }

    @Test
    fun aFailedRecoveryKeepsTheHeaderAndScopeTogetherAndRetriesLater() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        session.switchWorkspace(DemoIds.ACME_PILOT)
        session.scope.value!!.poller.stop()
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        fake.failNext(FakeEndpoint.ME, ApiError.transport())
        session.scope.value!!.myTasks.refresh()
        runCurrent()
        assertEquals(DemoIds.ACME_PILOT, session.state.value.current?.id)
        assertTrue(session.state.value.notice!!.contains("retrying"))
        // The header still names the lost workspace: the next scoped request 404s instead of showing
        // another workspace's data under this label, and recovery runs again.
        session.scope.value!!.projects.load()
        assertEquals(DemoIds.ACME_PILOT, fake.lastHeader())
        assertTrue(session.scope.value!!.projects.state.value.projects.isEmpty())
        runCurrent()
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
    }

    @Test
    fun recoveryNeverCancelsAUsersSwitch() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        session.switchWorkspace(DemoIds.ACME_PILOT)
        session.scope.value!!.poller.stop()
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        fake.hold(FakeEndpoint.ME)
        session.scope.value!!.myTasks.refresh() // recovery starts, /auth/me parked
        runCurrent()
        assertEquals(1, fake.parkedCount(FakeEndpoint.ME))
        val userSwitch = launch { session.switchWorkspace(DemoIds.MEKARI) }
        runCurrent()
        assertEquals(2, session.queuedJobs)
        fake.release(FakeEndpoint.ME, all = true)
        userSwitch.join()
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        assertEquals("Switched to Mekari", session.state.value.notice)
        assertEquals(0, session.queuedJobs)
    }

    @Test
    fun aRecoveryQueuedBehindAUsersSwitchDoesNotSupersedeIt() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        session.switchWorkspace(DemoIds.ACME_PILOT)
        session.scope.value!!.poller.stop()
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        fake.hold(FakeEndpoint.SWITCH_WORKSPACE)
        val userSwitch = launch { session.switchWorkspace(DemoIds.MEKARI) }
        runCurrent()
        fake.emit(ApiEvent.WorkspaceLost(DemoIds.ACME_PILOT)) // lost access lands while the switch is in flight
        runCurrent()
        assertEquals(2, session.queuedJobs)
        fake.release(FakeEndpoint.SWITCH_WORKSPACE, all = true)
        userSwitch.join()
        runCurrent()
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        assertEquals("the user's switch committed", "Switched to Mekari", session.state.value.notice)
        assertEquals(0, session.queuedJobs)
    }

    @Test
    fun unauthorizedAndSignOutEndTheSession() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        val scope = session.scope.value!!
        fake.emit(ApiEvent.Unauthorized)
        runCurrent()
        assertEquals(Auth.SignedOut, session.state.value.auth)
        assertNull(session.scope.value)
        assertFalse(scope.poller.isRunning)

        val other = startedSession(demoFake())
        other.signOut()
        assertEquals(Auth.SignedOut, other.state.value.auth)
        assertNull(other.scope.value)
    }

    @Test
    fun deepLinksSwitchWorkspaceWhenNeeded() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        val acmeTask = fake.currentTasks().first {
            it.workspaceId == DemoIds.ACME_PILOT &&
                it.assignee?.id == DemoIds.ALICE
        }
        assertEquals(
            DeepLinkResult.Open(NavigationTarget.Task(acmeTask.id, acmeTask.projectId)),
            session.resolve(DeepLink.Task(acmeTask.id))
        )
        assertEquals(DemoIds.ACME_PILOT, session.state.value.current?.id)
        assertEquals(
            DeepLinkResult.Open(NavigationTarget.Project(DemoIds.BOARD_PROJECT)),
            session.resolve(DeepLink.Project(DemoIds.BOARD_PROJECT))
        )
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        assertEquals(DeepLinkResult.NoAccess, session.resolve(DeepLink.Task("../users")))
        assertEquals(DeepLinkResult.NoAccess, session.resolve(DeepLink.Task("00000000-0000-4000-8000-00000000dead")))
        assertEquals("https://demo.invalid/inbox?workspace=${DemoIds.MEKARI}", session.webUrl("/inbox"))
    }

    @Test
    fun noWorkspaceMeansNoScope() = runTest {
        val fake = demoFake()
        fake.removeMembership(DemoIds.ALICE, DemoIds.MEKARI)
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        val session = startedSession(fake)
        assertTrue(session.state.value.auth is Auth.SignedIn)
        assertNull(session.scope.value)
        assertNull(session.state.value.current)
    }
}
