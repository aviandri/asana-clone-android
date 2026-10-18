package com.mekari.project.feature

import com.mekari.project.core.Comment
import com.mekari.project.core.DeepLink
import com.mekari.project.core.InMemoryServerSettings
import com.mekari.project.core.InMemoryTokenStore
import com.mekari.project.core.LiveApi
import com.mekari.project.core.Mentions
import com.mekari.project.core.NotificationType
import com.mekari.project.core.Priority
import com.mekari.project.core.ServerUrl
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The :feature flows end to end through LiveApi against a real, freshly seeded BE (a port of the Mac
 * Features and AppFlow contract suites). Skipped unless MP_BASE_URL is set. Each test makes its own data
 * and cleans up what it can. Never point it at a shared database.
 */
class FeatureContractTest {
    private lateinit var server: String

    @Before
    fun requireBackend() {
        val raw = System.getenv("MP_BASE_URL").orEmpty()
        assumeTrue("MP_BASE_URL not set", raw.isNotBlank())
        server = ServerUrl.normalize(raw)!!
    }

    /** Runs [block] with a supervised app scope on this (single) thread, cancelled afterwards. */
    private fun contract(block: suspend CoroutineScope.(appScope: CoroutineScope) -> Unit) = runBlocking {
        val appScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            block(appScope)
        } finally {
            appScope.cancel()
        }
    }

    private suspend fun signedIn(appScope: CoroutineScope, email: String, workspace: String = "Mekari"): AppSession {
        val session =
            AppSession(InMemoryServerSettings(), InMemoryTokenStore(), { url, tokens ->
                LiveApi(url, tokens)
            }, InMemoryFeaturePrefs(), appScope)
        session.signIn(server, email, PASSWORD)
        assertNull(session.state.value.signInError)
        val target = session.state.value.all.first { it.name == workspace }
        session.switchWorkspace(target.id)
        assertEquals(target.id, session.scope.value?.id)
        session.scope.value!!.poller.stop() // the tests drive the inbox themselves
        return session
    }

    private val AppSession.ws get() = scope.value!!

    @Test
    fun signInErrorsAreMapped() = contract { appScope ->
        val session =
            AppSession(InMemoryServerSettings(), InMemoryTokenStore(), { url, tokens ->
                LiveApi(url, tokens)
            }, InMemoryFeaturePrefs(), appScope)
        session.signIn(server, "alice@example.com", "wrong-password")
        assertEquals(SignInError.WrongCredentials, session.state.value.signInError)
        session.signIn("http://127.0.0.1:9", "alice@example.com", PASSWORD)
        assertEquals(SignInError.Transport, session.state.value.signInError)
        session.signIn(server, "alice@example.com", PASSWORD)
        assertTrue(session.state.value.auth is Auth.SignedIn)
        session.signOut()
        assertEquals(Auth.SignedOut, session.state.value.auth)
    }

    @Test
    fun switchingWorkspacesAndTheProjectsTab() = contract { appScope ->
        val session = signedIn(appScope, "alice@example.com")
        assertEquals(listOf("Acme Pilot", "Mekari"), session.state.value.all.map { it.name })
        session.switchWorkspace(session.state.value.all.first { it.name == "Acme Pilot" }.id)
        session.ws.projects.load()
        assertEquals(listOf("Pilot Rollout"), session.ws.projects.state.value.projects.map { it.name })
        session.switchWorkspace(session.state.value.all.first { it.name == "Mekari" }.id)
        session.ws.projects.load()
        assertTrue(session.ws.projects.state.value.projects.any { it.name == "Mobile App v2" })
    }

    @Test
    fun myTasksQuickAddAndToggle() = contract { appScope ->
        val session = signedIn(appScope, "alice@example.com")
        val scope = session.ws
        scope.myTasks.refresh()
        assertEquals(LoadState.Loaded, scope.myTasks.state.value.phase)
        assertTrue(scope.quickAdd.submit("Contract quick add", LocalDate.now()))
        val id = scope.myTasks.state.value.groups.flatMap {
            it.taskIds
        }.first { scope.title(it) == "Contract quick add" }
        assertTrue(id in scope.myTasks.state.value.dueSoon)
        val saved = scope.tasks[id]!!
        assertNull(saved.projectId)
        assertEquals(session.state.value.me!!.id, saved.assignee?.id)

        scope.myTasks.toggleComplete(id)
        assertEquals(true, scope.tasks[id]?.completed)
        assertNull(scope.myTasks.state.value.lastError)
        LiveApi(server, InMemoryTokenStore()).also { it.login("alice@example.com", PASSWORD) }.deleteTask(id)
    }

    @Test
    fun boardMovesAndDetailEditsPersistAfterReload() = contract { appScope ->
        val session = signedIn(appScope, "alice@example.com")
        val scope = session.ws
        scope.projects.load()
        val projectId = scope.projects.state.value.projects.first { it.name == "Mobile App v2" }.id
        val board = scope.project(projectId)
        board.load()
        assertTrue(board.state.value.canEdit)
        val (todo, doing) = board.state.value.sections.let { it[0].id to it[1].id }

        val cards = mutableListOf<String>()
        for (title in listOf("Board card A", "Board card B", "Board card C")) {
            assertTrue(board.addTask(title, todo))
            cards += board.state.value.taskIds(todo).last()
        }
        val (a, b, c) = cards
        val doingBefore = board.state.value.taskIds(doing)
        board.move(c, doing, before = doingBefore.firstOrNull())
        board.move(a, doing, before = null)
        board.move(b, doing, before = c)
        assertNull(board.state.value.lastError)
        val expected = listOf(b, c) + doingBefore + a
        assertEquals(expected, board.state.value.taskIds(doing))

        val detail = scope.taskDetail(b)
        detail.load()
        detail.setDueDate(LocalDate.now().plusDays(2))
        detail.setPriority(Priority.HIGH)
        assertTrue(detail.addSubtask("Contract subtask"))
        assertTrue(detail.composer.send("Done from the contract suite"))
        assertNull(detail.state.value.lastError)
        assertEquals(1, detail.state.value.detail?.subtasks?.size)
        assertTrue(detail.state.value.stories.any { it is Comment && it.body == "Done from the contract suite" })

        // A second session (a fresh launch) sees the same order and edits.
        val fresh = signedIn(appScope, "alice@example.com")
        val reloaded = fresh.ws.project(projectId)
        reloaded.load()
        assertEquals(expected, reloaded.state.value.taskIds(doing))
        val freshDetail = fresh.ws.taskDetail(b)
        freshDetail.load()
        assertEquals(Priority.HIGH, freshDetail.state.value.task?.priority)
        assertEquals(LocalDate.now().plusDays(2), freshDetail.state.value.task?.dueDate)

        val api = LiveApi(server, InMemoryTokenStore()).also { it.login("alice@example.com", PASSWORD) }
        for (id in cards) api.deleteTask(id)
    }

    @Test
    fun inboxMarkReadAndMarkAllReadRoundTrip() = contract { appScope ->
        val alice = signedIn(appScope, "alice@example.com")
        val bob = signedIn(appScope, "bob@example.com")
        bob.ws.inbox.load()
        bob.ws.inbox.markAllRead()
        assertNull(bob.ws.inbox.state.value.lastError)

        alice.ws.projects.load()
        val board = alice.ws.project(alice.ws.projects.state.value.projects.first { it.name == "Mobile App v2" }.id)
        board.load()
        val section = board.state.value.sections[0].id
        assertTrue(board.addTask("Inbox flow task", section))
        val taskId = board.state.value.taskIds(section).first { alice.ws.title(it) == "Inbox flow task" }
        val detail = alice.ws.taskDetail(taskId)
        detail.load()
        val bobSummary = bob.state.value.me!!.user.summary
        detail.setAssignee(bobSummary)
        assertTrue(detail.composer.send("Over to you ${Mentions.token(bobSummary)}"))

        bob.ws.inbox.load()
        val fresh = bob.ws.inbox.state.value.notifications.filter { it.task?.id == taskId && !it.read }
        assertEquals(setOf(NotificationType.ASSIGNED, NotificationType.MENTIONED), fresh.map { it.type }.toSet())
        assertEquals(2, bob.ws.inbox.state.value.unreadCount)

        val mention = fresh.first { it.type == NotificationType.MENTIONED }
        assertEquals(NavigationTarget.Task(taskId, board.projectId), bob.ws.inbox.open(mention.id))
        assertEquals(1, bob.ws.inbox.state.value.unreadCount)
        bob.ws.inbox.markAllRead()
        val again = signedIn(appScope, "bob@example.com")
        again.ws.inbox.load()
        assertEquals(0, again.ws.inbox.state.value.unreadCount)

        LiveApi(server, InMemoryTokenStore()).also { it.login("alice@example.com", PASSWORD) }.deleteTask(taskId)
    }

    @Test
    fun searchGroupsAndGuestVisibility() = contract { appScope ->
        val alice = signedIn(appScope, "alice@example.com")
        val search = alice.ws.search
        suspend fun run(query: String): com.mekari.project.core.SearchResults {
            search.setQuery(query)
            repeat(500) {
                if (!search.state.value.isSearching &&
                    search.state.value.query == query
                ) {
                    return search.state.value.results!!
                }
                delay(10)
            }
            error("no results for $query")
        }
        assertTrue(run("mobile").projects.any { it.name == "Mobile App v2" })
        assertEquals(listOf("Bob Smith"), run("bob").users.map { it.name })
        val onboarding = run("onboarding").tasks.first { it.title == "Onboarding screens" }
        assertEquals("Mobile App v2", onboarding.project?.name)
        assertTrue(run("zzzz-no-such-thing").isEmpty)
        assertTrue(search.state.value.isEmpty)

        // A guest only finds their own project and co-members (W1/W8), and can't open other projects.
        val guest = signedIn(appScope, "guest@partner.test", workspace = "Acme Pilot")
        assertTrue(guest.state.value.isGuest)
        guest.ws.projects.load()
        assertEquals(listOf("Pilot Rollout"), guest.ws.projects.state.value.projects.map { it.name })
        assertEquals(DeepLinkResult.NoAccess, guest.resolve(DeepLink.Project("00000000-0000-4000-8000-00000000dead")))
        assertFalse(guest.ws.canEdit(onboarding))
    }

    private companion object {
        const val PASSWORD = "password123"
    }
}
