package com.mekari.project.feature

import app.cash.turbine.test
import com.mekari.project.core.ApiError
import com.mekari.project.core.InboxNotification
import com.mekari.project.core.NotificationTaskRef
import com.mekari.project.core.NotificationType
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeEndpoint
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {
    @Test
    fun loadMarkReadOpenAndMarkAll() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.inbox.load()
        val state = scope.inbox.state.value
        assertEquals(2, state.notifications.size)
        assertEquals(1, state.unreadCount)
        assertEquals(state.notifications.sortedByDescending { it.createdAt }, state.notifications)

        val unread = state.notifications.first { !it.read }
        val target = scope.inbox.open(unread.id)
        assertEquals(NavigationTarget.Task(DemoIds.TASK_WITH_STORIES, DemoIds.BOARD_PROJECT), target)
        assertEquals(0, scope.inbox.state.value.unreadCount)

        // A failed mark-all reverts (nothing newer landed meanwhile).
        fake.addNotification(notification("n-new"), DemoIds.ALICE)
        scope.inbox.load()
        fake.failNext(FakeEndpoint.MARK_ALL_READ, ApiError.transport())
        scope.inbox.markAllRead()
        assertEquals(1, scope.inbox.state.value.unreadCount)
        assertEquals(ApiError.Code.TRANSPORT, scope.inbox.state.value.lastError?.code)
        scope.inbox.markAllRead()
        assertEquals(0, scope.inbox.state.value.unreadCount)
    }

    @Test
    fun aLoadInFlightIsJoinedNotRepeated() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        fake.hold(FakeEndpoint.NOTIFICATIONS)
        val poll = launch { scope.inbox.load() } // e.g. the poller starting on a switch…
        runCurrent()
        val refresh = launch { scope.inbox.load() } // …and the foreground refresh at the same moment
        runCurrent()
        assertEquals(1, fake.parkedCount(FakeEndpoint.NOTIFICATIONS))
        fake.release(FakeEndpoint.NOTIFICATIONS, all = true)
        poll.join()
        refresh.join()
        assertEquals(1, fake.calls.count { it.endpoint == FakeEndpoint.NOTIFICATIONS })
        assertEquals(2, scope.inbox.state.value.notifications.size)
    }

    @Test
    fun aMarkReadFailingAfterANewerLoadDoesNotRevertIt() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.inbox.load()
        assertEquals(1, scope.inbox.state.value.unreadCount)
        val unread = scope.inbox.state.value.notifications.first { !it.read }
        fake.hold(FakeEndpoint.MARK_READ)
        fake.failNext(FakeEndpoint.MARK_READ, ApiError.transport())
        val mark = launch { scope.inbox.markRead(unread.id) }
        runCurrent()
        assertEquals("optimistic", 0, scope.inbox.state.value.unreadCount)
        scope.inbox.load() // newer: the server still has it unread
        assertEquals(1, scope.inbox.state.value.unreadCount)
        fake.release(FakeEndpoint.MARK_READ, all = true)
        mark.join()
        assertEquals("the failure doesn't revert on top of newer state", 1, scope.inbox.state.value.unreadCount)
        assertEquals(ApiError.Code.TRANSPORT, scope.inbox.state.value.lastError?.code)
    }

    @Test
    fun thePollerReportsOnlyNewUnreadAfterTheFirstPoll() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.poller.newItems.test {
            scope.poller.start()
            runCurrent()
            assertTrue(scope.poller.isRunning)
            expectNoEvents() // the first poll only learns what's there

            fake.addNotification(notification("n-1"), DemoIds.ALICE)
            advanceTimeBy(60_001)
            runCurrent()
            val notices = awaitItem()
            assertEquals(listOf("n-1"), notices.map { it.id })
            assertEquals("Bob Smith commented on Fix login redirect", notices.single().title)

            advanceTimeBy(60_001)
            runCurrent()
            expectNoEvents() // nothing new

            scope.poller.stop()
            assertFalse(scope.poller.isRunning)
            fake.addNotification(notification("n-2"), DemoIds.ALICE)
            advanceTimeBy(120_000)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun theDiffHelperForTheBackgroundWorker() {
        val page = com.mekari.project.core.InboxPage(
            listOf(notification("a"), notification("b").copy(read = true), notification("c")),
            2
        )
        assertEquals(emptyList<LocalNotice>(), NotificationDiff.newUnread(null, page))
        assertEquals(listOf("c"), NotificationDiff.newUnread(setOf("a", "b"), page).map { it.id })
    }

    @Test
    fun searchNeedsTwoCharactersIsDebouncedAndDropsStaleResults() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val search = scope.search
        search.setQuery("l")
        advanceTimeBy(300)
        runCurrent()
        assertNull(search.state.value.results)
        assertEquals(0, fake.calls.count { it.endpoint == FakeEndpoint.SEARCH })

        search.setQuery("lo")
        search.setQuery("login")
        assertTrue(search.state.value.isSearching)
        advanceTimeBy(251)
        runCurrent()
        assertEquals(1, fake.calls.count { it.endpoint == FakeEndpoint.SEARCH })
        val task = search.state.value.results!!.tasks.single()
        assertEquals(NavigationTarget.Task(task.id, DemoIds.BOARD_PROJECT), search.target(task.id))

        search.setQuery("login ") // whitespace only: no new search
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, fake.calls.count { it.endpoint == FakeEndpoint.SEARCH })

        fake.hold(FakeEndpoint.SEARCH)
        search.setQuery("mobile")
        advanceTimeBy(251)
        runCurrent()
        search.setQuery("zzzz")
        advanceTimeBy(251)
        runCurrent()
        fake.release(FakeEndpoint.SEARCH, newest = true)
        runCurrent()
        fake.release(FakeEndpoint.SEARCH, all = true)
        runCurrent()
        assertTrue("the stale 'mobile' answer is dropped", search.state.value.isEmpty)
    }

    @Test
    fun searchAndUserSearchErrorsCanBeCleared() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        fake.failNext(FakeEndpoint.SEARCH, ApiError.transport())
        scope.search.setQuery("login")
        advanceTimeBy(251)
        runCurrent()
        assertEquals(ApiError.Code.TRANSPORT, scope.search.state.value.lastError?.code)
        scope.search.clearError()
        assertNull(scope.search.state.value.lastError)

        val users = scope.userSearch()
        fake.failNext(FakeEndpoint.USERS, ApiError.transport())
        users.setQuery("bo")
        advanceTimeBy(251)
        runCurrent()
        assertEquals(ApiError.Code.TRANSPORT, users.state.value.lastError?.code)
        users.clearError()
        assertNull(users.state.value.lastError)
    }

    private fun notification(id: String) = InboxNotification(
        id,
        DemoIds.MEKARI,
        NotificationType.COMMENTED,
        false,
        Instant.parse("2026-10-18T05:00:00Z"),
        bob,
        NotificationTaskRef(DemoIds.TASK_WITH_STORIES, "Fix login redirect", DemoIds.BOARD_PROJECT)
    )
}
