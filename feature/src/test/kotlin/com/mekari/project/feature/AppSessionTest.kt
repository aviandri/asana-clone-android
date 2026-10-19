package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.ApiEvent
import com.mekari.project.core.DeepLink
import com.mekari.project.core.InMemoryServerSettings
import com.mekari.project.core.InMemoryTokenStore
import com.mekari.project.core.MekariApi
import com.mekari.project.core.ServerSettings
import com.mekari.project.core.TokenStore
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

    /**
     * Sign-in writes, in order: the URL (persisted synchronously by the app) before the token, which LiveApi saves
     * inside login(). Recorded through settings, the token store, and a client that saves like LiveApi.
     */
    private class Recorder(url: String?, token: String?) {
        val log = mutableListOf<String>()
        val settings = object : ServerSettings {
            override var serverUrl: String? = url
                set(value) {
                    field = value
                    log += "url=$value"
                }
        }
        val tokens = object : TokenStore {
            private val inner = InMemoryTokenStore(token)
            override fun load() = inner.load()
            override fun save(token: String) = inner.save(token).also { log += "save" }
            override fun clear() = inner.clear().also { log += "clear" }
            override fun clearIf(expected: String) = inner.clearIf(expected)
        }

        fun client(fake: FakeApi): (String, TokenStore) -> MekariApi = { _, store ->
            object : MekariApi by fake {
                override suspend fun health() = fake.health().also { log += "health(cookie=${store.load()})" }
                override suspend fun login(email: String, password: String) =
                    fake.login(email, password).also { store.save("new-token") }
            }
        }
    }

    @Test
    fun signInStoresTheServerBeforeTheTokenAndNeverSendsAnotherServersSession() = runTest {
        val old = "https://old.example.com"
        // Offline at launch left a kept session for the old server; the user signs in somewhere else.
        val switching = Recorder(old, "old-token")
        val fake = demoFake(email = null)
        val session = AppSession(
            switching.settings,
            switching.tokens,
            switching.client(fake),
            InMemoryFeaturePrefs(),
            backgroundScope,
            TestTime.clock
        )
        session.signIn("new.example.com", "alice@example.com", DemoIds.PASSWORD)
        assertTrue(session.state.value.auth is Auth.SignedIn)
        assertEquals(
            listOf("clear", "health(cookie=null)", "url=https://new.example.com", "save"),
            switching.log
        )

        // A failed sign-in elsewhere puts the old URL back (and still never sent the old cookie).
        val failing = Recorder(old, "old-token")
        val failingSession = AppSession(
            failing.settings,
            failing.tokens,
            failing.client(demoFake(email = null)),
            InMemoryFeaturePrefs(),
            backgroundScope,
            TestTime.clock
        )
        failingSession.signIn("new.example.com", "alice@example.com", "wrong")
        assertEquals(SignInError.WrongCredentials, failingSession.state.value.signInError)
        assertEquals(old, failing.settings.serverUrl)
        assertEquals(
            listOf("clear", "health(cookie=null)", "url=https://new.example.com", "url=$old"),
            failing.log
        )

        // The same server keeps the kept session until the new login replaces it.
        val same = Recorder(old, "old-token")
        val sameSession =
            AppSession(
                same.settings,
                same.tokens,
                same.client(demoFake(email = null)),
                InMemoryFeaturePrefs(),
                backgroundScope,
                TestTime.clock
            )
        sameSession.signIn(old, "alice@example.com", DemoIds.PASSWORD)
        assertEquals(listOf("health(cookie=old-token)", "url=$old", "save"), same.log)
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
    fun switchingAwayAndBackBeforeTheFirstPostAnswersEndsOnTheStart() = runTest {
        val fake = demoFake()
        val session = startedSession(fake) // A = Mekari
        fake.hold(FakeEndpoint.SWITCH_WORKSPACE)
        val toX = launch { session.switchWorkspace(DemoIds.ACME_PILOT) }
        runCurrent()
        val backToA = launch { session.switchWorkspace(DemoIds.MEKARI) } // queued behind X's POST
        runCurrent()
        fake.release(FakeEndpoint.SWITCH_WORKSPACE) // X's POST lands: the server now defaults to X
        toX.join()
        runCurrent()
        fake.release(FakeEndpoint.SWITCH_WORKSPACE, all = true)
        backToA.join()
        // The app never left A, but the server did: A must still be POSTed.
        assertEquals(2, fake.calls.count { it.endpoint == FakeEndpoint.SWITCH_WORKSPACE })
        assertEquals(DemoIds.MEKARI, session.state.value.current?.id)
        assertEquals(DemoIds.MEKARI, fake.me().currentWorkspace?.id)
    }

    @Test
    fun aClosedScopesLateConfirmNeitherCelebratesNorStartsTheCooldown() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        val old = session.scope.value!!
        val board = old.project(DemoIds.BOARD_PROJECT)
        board.load()
        val section = board.state.value.sections[1].id
        val ids = board.state.value.taskIds(section)
        for (id in ids.dropLast(1)) board.toggleComplete(id)
        // The last task in the section; the switch lands before its PATCH answers.
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val complete = launch { board.toggleComplete(ids.last()) }
        runCurrent()
        session.switchWorkspace(DemoIds.ACME_PILOT)
        assertTrue(old.isClosed)
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        complete.join()
        assertNull(old.completionFeedback.lastEvent.value)

        // The first real celebration in the new workspace isn't blocked by a cooldown.
        val acme = session.scope.value!!
        val pilot = acme.project(DemoIds.PILOT_PROJECT)
        pilot.load()
        val todo = pilot.state.value.sections[0].id
        for (id in pilot.state.value.taskIds(todo)) pilot.toggleComplete(id)
        assertTrue(acme.completionFeedback.lastEvent.value?.celebration is Celebration.SectionCleared)
    }

    @Test
    fun aRenumberInProgressStopsWhenTheWorkspaceChanges() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        val old = session.scope.value!!
        val board = old.project(DemoIds.BOARD_PROJECT)
        board.load()
        val todo = board.state.value.sections[0].id
        val (push, offline, _, tablet) = board.state.value.taskIds(todo)
        old.tasks.update(offline, com.mekari.project.core.TaskChanges(position = old.tasks[push]!!.position + 1e-9))
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val move = launch { board.move(tablet, todo, before = offline) } // crowded: renumbers the section
        runCurrent()
        assertEquals(1, fake.parkedCount(FakeEndpoint.UPDATE_TASK))
        val before = fake.calls.count { it.endpoint == FakeEndpoint.UPDATE_TASK }
        session.switchWorkspace(DemoIds.ACME_PILOT)
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        move.join()
        runCurrent()
        assertEquals(
            "no PATCH goes out after the switch",
            before,
            fake.calls.count {
                it.endpoint ==
                    FakeEndpoint.UPDATE_TASK
            }
        )
        assertNull("a cancellation, not an error", board.state.value.lastError)
        // Every other request from the closed scope is refused before it's sent, too.
        val loads = fake.calls.size
        try {
            old.myTasks.refresh()
            org.junit.Assert.fail("a closed scope's request is refused")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // As a cancellation: a ViewModel action just ends, nothing is shown.
        }
        assertEquals(loads, fake.calls.size)
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
