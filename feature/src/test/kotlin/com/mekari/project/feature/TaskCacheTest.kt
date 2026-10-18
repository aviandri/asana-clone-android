package com.mekari.project.feature

import app.cash.turbine.test
import com.mekari.project.core.ApiError
import com.mekari.project.core.Patch
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskCacheTest {
    private val id = DemoIds.TASK_WITH_STORIES

    @Test
    fun editsShowAtOnceAndAFailedOneDropsOnlyItself() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.tasks.upsert(fake.task(id))
        fake.hold(FakeEndpoint.UPDATE_TASK)
        fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
        val first = launch {
            try {
                scope.tasks.update(id, TaskChanges(title = "First"))
                fail("expected the first edit to fail")
            } catch (_: ApiError) {
            }
        }
        val second = launch { scope.tasks.update(id, TaskChanges(priority = com.mekari.project.core.Priority.LOW)) }
        runCurrent()
        assertEquals("First", scope.title(id))
        assertEquals(com.mekari.project.core.Priority.LOW, scope.tasks[id]?.priority)
        assertTrue(scope.tasks.hasPendingEdits(id))

        fake.release(FakeEndpoint.UPDATE_TASK) // the first: fails
        first.join()
        assertEquals("the failed title drops out", "Fix login redirect", scope.title(id))
        assertEquals("the later edit stays", com.mekari.project.core.Priority.LOW, scope.tasks[id]?.priority)

        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        second.join()
        assertFalse(scope.tasks.hasPendingEdits(id))
        assertEquals(com.mekari.project.core.Priority.LOW, scope.tasks[id]?.priority)
    }

    @Test
    fun anOlderServerCopyIsIgnoredAndTombstonesStick() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val fresh = fake.task(id).task
        scope.tasks.upsert(listOf(fresh))
        scope.tasks.upsert(listOf(fresh.copy(title = "Stale", updatedAt = fresh.updatedAt.minusSeconds(60))))
        assertEquals("Fix login redirect", scope.title(id))
        scope.tasks.upsert(listOf(fresh.copy(title = "Newer", updatedAt = fresh.updatedAt.plusSeconds(60))))
        assertEquals("Newer", scope.title(id))

        scope.tasks.remove(id)
        assertNull(scope.tasks[id])
        scope.tasks.upsert(listOf(fresh.copy(updatedAt = fresh.updatedAt.plusSeconds(3600))))
        scope.tasks.upsert(fake.task(id))
        assertNull("a later copy never brings it back", scope.tasks[id])
    }

    @Test
    fun editOnesAnswerArrivingAfterEditTwosKeepsBoth() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.tasks.upsert(fake.task(id))
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val one = launch { scope.tasks.update(id, TaskChanges(title = "One")) }
        runCurrent()
        val two = launch { scope.tasks.update(id, TaskChanges(priority = com.mekari.project.core.Priority.LOW)) }
        runCurrent()
        fake.release(FakeEndpoint.UPDATE_TASK, newest = true) // edit 2 answers first (without the title)
        two.join()
        assertEquals("edit 1 is still in flight and stays visible", "One", scope.title(id))
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        one.join()
        assertEquals("One", scope.title(id))
        assertEquals(com.mekari.project.core.Priority.LOW, scope.tasks[id]?.priority)
        assertFalse(scope.tasks.hasPendingEdits(id))
    }

    @Test
    fun aPendingEditStaysVisibleThroughAListLoad() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val edit = launch { scope.tasks.update(id, TaskChanges(title = "Renamed")) }
        runCurrent()
        scope.myTasks.refresh() // the server still has the old title
        assertEquals("Renamed", scope.title(id))
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        edit.join()
        assertEquals("Renamed", scope.title(id))
    }

    @Test
    fun anEditReplacesOnlyItsOwnEntry() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        val (a, b) = scope.myTasks.state.value.groups.flatMap { it.taskIds }.take(2)
        val before = scope.tasks.tasks.value[b]
        scope.tasks.observe(b).test {
            assertSame(before, awaitItem())
            scope.tasks.update(a, TaskChanges(title = "Renamed"))
            assertSame("other rows keep their identity", before, scope.tasks.tasks.value[b])
            expectNoEvents()
        }
    }

    @Test
    fun aCompletedTaskLingersThenLeavesAndAnUncompleteEndsIt() = runTest {
        val scope = workspaceScope()
        scope.myTasks.refresh()
        val today = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds
        val task = today.first()

        scope.myTasks.toggleComplete(task)
        assertTrue(scope.tasks.isLingering(task))
        advanceTimeBy(299)
        assertTrue(scope.tasks.isLingering(task))
        advanceTimeBy(2)
        assertFalse(scope.tasks.isLingering(task))

        // Complete, un-complete at 150 ms, complete again: the first timer (due at 300) can't end the new linger.
        scope.myTasks.toggleComplete(task) // un-complete
        scope.myTasks.toggleComplete(task)
        advanceTimeBy(150)
        scope.myTasks.toggleComplete(task)
        assertFalse(scope.tasks.isLingering(task))
        scope.myTasks.toggleComplete(task)
        advanceTimeBy(160) // past the first timer
        assertTrue(scope.tasks.isLingering(task))
        advanceTimeBy(150)
        assertFalse(scope.tasks.isLingering(task))
    }

    @Test
    fun aFailedCompletionEndsTheLingerButAnOlderFailureLeavesANewerOne() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        val task = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds.first()

        fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
        scope.myTasks.toggleComplete(task)
        assertFalse(scope.tasks.isLingering(task))
        assertEquals(false, scope.tasks[task]?.completed)

        // Complete (held, will fail), un-complete, complete again; release one at a time.
        fake.hold(FakeEndpoint.UPDATE_TASK)
        fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
        val first = launch { scope.myTasks.toggleComplete(task) }
        runCurrent()
        val undo = launch { scope.myTasks.toggleComplete(task) }
        runCurrent()
        val again = launch { scope.myTasks.toggleComplete(task) }
        runCurrent()
        assertEquals(3, fake.parkedCount(FakeEndpoint.UPDATE_TASK))
        fake.release(FakeEndpoint.UPDATE_TASK)
        first.join()
        assertTrue("the old failure leaves the new linger", scope.tasks.isLingering(task))
        fake.release(FakeEndpoint.UPDATE_TASK)
        undo.join()
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        again.join()
        assertEquals(true, scope.tasks[task]?.completed)
    }

    @Test
    fun lingerDurationIsReadWhenEachLingerStartsAndZeroTurnsItOff() = runTest {
        val scope = workspaceScope()
        scope.myTasks.refresh()
        val (first, second) = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds
        scope.myTasks.toggleComplete(first) // 300 ms
        scope.tasks.lingerDuration = kotlin.time.Duration.parse("150ms")
        scope.myTasks.toggleComplete(second) // 150 ms
        advanceTimeBy(151)
        assertTrue(scope.tasks.isLingering(first))
        assertFalse(scope.tasks.isLingering(second))
        advanceTimeBy(150)
        assertFalse(scope.tasks.isLingering(first))

        scope.tasks.lingerDuration = kotlin.time.Duration.ZERO
        scope.myTasks.toggleComplete(second) // un-complete
        scope.myTasks.toggleComplete(second)
        assertFalse(scope.tasks.isLingering(second))
    }

    @Test
    fun cancellationRevertsTheEditAndPropagates() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.tasks.upsert(fake.task(id))
        fake.hold(FakeEndpoint.UPDATE_TASK)
        var cancelled = false
        val job = launch {
            try {
                scope.tasks.update(id, TaskChanges(title = "Going away"))
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        assertEquals("Going away", scope.title(id))
        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertEquals("Fix login redirect", scope.title(id))
        assertFalse(scope.tasks.hasPendingEdits(id))
    }

    @Test
    fun theAssigneeShowsAtOnce() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.tasks.upsert(fake.task(id))
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val job =
            launch { scope.tasks.update(id, TaskChanges(assigneeId = Patch.Set(bob.id)), assignee = Patch.Set(bob)) }
        runCurrent()
        assertEquals(bob, scope.tasks[id]?.assignee)
        assertEquals(bob, scope.tasks.detail(id)?.task?.assignee)
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        job.join()
        assertEquals(bob.id, scope.tasks[id]?.assignee?.id)
    }
}
