package com.mekari.project.feature

import app.cash.turbine.test
import com.mekari.project.core.NewTask
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a completion celebrates (the Mac 0.1.1 rules, incl. the review fixes). */
class CompletionFeedbackTest {
    private suspend fun TestScope.loaded(
        fake: FakeApi = demoFake(),
        board: Boolean = true,
        prefs: FeaturePrefs = InMemoryFeaturePrefs()
    ): WorkspaceScope {
        val scope = workspaceScope(fake, prefs = prefs)
        scope.myTasks.refresh()
        if (board) scope.project(DemoIds.BOARD_PROJECT).load()
        return scope
    }

    private val WorkspaceScope.board get() = project(DemoIds.BOARD_PROJECT)
    private val WorkspaceScope.feedback get() = completionFeedback

    /** Open top-level tasks of the board's section [index] (null: "No section"). */
    private fun WorkspaceScope.open(index: Int?): List<String> {
        val state = board.state.value
        return state.taskIds(index?.let { state.sections[it].id }).filter { tasks[it]?.completed == false }
    }

    private fun WorkspaceScope.section(index: Int) = board.state.value.sections[index].id

    @Test
    fun clearingASectionCelebratesOnlyOnTheLastTask() = runTest {
        val scope = loaded()
        val ids = scope.open(1)
        assertTrue(ids.size > 1)
        for (id in ids.dropLast(1)) {
            scope.board.toggleComplete(id)
            assertNull("not the last one", scope.feedback.lastEvent.value)
        }
        scope.board.toggleComplete(ids.last())
        assertEquals(
            CompletionFeedback.Event(
                1,
                ids.last(),
                Celebration.SectionCleared(DemoIds.BOARD_PROJECT, scope.section(1))
            ),
            scope.feedback.lastEvent.value
        )
    }

    @Test
    fun clearingTheProjectWinsOverItsSectionAndIsRateLimited() = runTest {
        val scope = loaded()
        val open = (0 until scope.board.state.value.sections.size).flatMap { scope.open(it) } + scope.open(null)
        for (id in open.dropLast(1)) scope.board.toggleComplete(id)
        // Clearing the earlier sections celebrated once; the rest fell inside the 30 s window.
        assertEquals(1, scope.feedback.lastEvent.value?.id)
        advanceTimeBy(30_000)
        runCurrent() // advanceTimeBy stops just short of tasks due exactly then
        scope.board.toggleComplete(open.last())
        assertEquals(
            CompletionFeedback.Event(2, open.last(), Celebration.ProjectCleared(DemoIds.BOARD_PROJECT)),
            scope.feedback.lastEvent.value
        )
    }

    @Test
    fun theRateLimitWindowIsThirtySeconds() = runTest {
        val scope = loaded()
        for (id in scope.open(1)) scope.board.toggleComplete(id)
        assertEquals(1, scope.feedback.lastEvent.value?.id)
        advanceTimeBy(29_999)
        val todo = scope.open(0)
        for (id in todo) scope.board.toggleComplete(id)
        assertEquals("suppressed at 29.999 s", 1, scope.feedback.lastEvent.value?.id)
        advanceTimeBy(1)
        runCurrent()
        scope.board.toggleComplete(todo.last()) // un-complete
        scope.board.toggleComplete(todo.last())
        assertEquals(2, scope.feedback.lastEvent.value?.id)
    }

    @Test
    fun myLastTaskDueTodayCelebratesAndOverdueDoesNotCount() = runTest {
        // Board not loaded: the project and section rules can't apply, so only due today can.
        val scope = loaded(board = false)
        val today = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds
        assertEquals(2, today.size)
        scope.myTasks.toggleComplete(today[0])
        assertNull(scope.feedback.lastEvent.value)
        scope.myTasks.toggleComplete(today[1])
        assertEquals(Celebration.DueTodayCleared, scope.feedback.lastEvent.value?.celebration)

        advanceTimeBy(30_000)
        runCurrent() // advanceTimeBy stops just short of tasks due exactly then
        val overdue = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.OVERDUE }.taskIds.first()
        scope.myTasks.toggleComplete(overdue)
        assertEquals("overdue isn't today", 1, scope.feedback.lastEvent.value?.id)
    }

    @Test
    fun anOpenSubtaskDueTodayDoesNotHoldBackDueTodayCleared() = runTest {
        val fake = demoFake()
        fake.setWorkspace(DemoIds.MEKARI)
        fake.createTask(
            NewTask(
                title = "Subtask due today",
                parentId = DemoIds.TASK_WITH_STORIES,
                assigneeId = DemoIds.ALICE,
                dueDate = TestTime.today
            )
        )
        val scope = loaded(fake, board = false)
        val today = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds
        val subtask = today.first { scope.title(it) == "Subtask due today" }
        for (id in today.filter { it != subtask }) scope.myTasks.toggleComplete(id)
        assertEquals(false, scope.tasks[subtask]?.completed)
        assertEquals(Celebration.DueTodayCleared, scope.feedback.lastEvent.value?.celebration)
    }

    @Test
    fun rejectedOrUncompletedCompletionsNeverCelebrateAndRejectionsAreAnnounced() = runTest {
        val fake = demoFake()
        val scope = loaded(fake)
        val ids = scope.open(1)
        for (id in ids.dropLast(1)) scope.board.toggleComplete(id)
        val last = ids.last()

        scope.feedback.rolledBack.test {
            fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
            scope.board.toggleComplete(last)
            assertEquals(last, awaitItem())
        }
        assertNull(scope.feedback.lastEvent.value)

        // Un-completed before the server answered.
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val complete = launch { scope.board.toggleComplete(last) }
        runCurrent()
        val undo = launch { scope.board.toggleComplete(last) }
        runCurrent()
        fake.release(FakeEndpoint.UPDATE_TASK)
        complete.join()
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        undo.join()
        assertEquals(false, scope.tasks[last]?.completed)
        assertNull(scope.feedback.lastEvent.value)

        // An un-complete never celebrates; a real completion right after does (no window was started).
        scope.board.toggleComplete(last)
        assertEquals(last, scope.feedback.lastEvent.value?.taskId)
    }

    @Test
    fun aSiblingThatFailsAndReopensCancelsTheCelebration() = runTest {
        val fake = demoFake()
        val scope = loaded(fake)
        val ids = scope.open(1)
        for (id in ids.dropLast(2)) scope.board.toggleComplete(id)
        val (a, b) = ids.takeLast(2)
        fake.hold(FakeEndpoint.UPDATE_TASK)
        fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
        val completeA = launch { scope.board.toggleComplete(a) }
        runCurrent()
        val completeB = launch { scope.board.toggleComplete(b) }
        runCurrent()
        fake.release(FakeEndpoint.UPDATE_TASK) // A fails and reopens
        completeA.join()
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        completeB.join()
        assertEquals(false, scope.tasks[a]?.completed)
        assertEquals(true, scope.tasks[b]?.completed)
        assertNull("the section isn't cleared", scope.feedback.lastEvent.value)
    }

    @Test
    fun aNewTaskFromARefreshCancelsTheCelebration() = runTest {
        val fake = demoFake()
        val scope = loaded(fake)
        val ids = scope.open(1)
        for (id in ids.dropLast(1)) scope.board.toggleComplete(id)
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val complete = launch { scope.board.toggleComplete(ids.last()) }
        runCurrent()
        // A teammate adds a task to the section; a refresh brings it in before the PATCH answers.
        fake.createTask(
            NewTask(title = "Teammate's task", projectId = DemoIds.BOARD_PROJECT, sectionId = scope.section(1))
        )
        scope.board.load()
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        complete.join()
        assertEquals(true, scope.tasks[ids.last()]?.completed)
        assertNull(scope.feedback.lastEvent.value)
    }

    @Test
    fun subtasksNeverCelebrate() = runTest {
        val scope = loaded()
        val detail = scope.taskDetail(DemoIds.TASK_WITH_STORIES)
        detail.load()
        val open = detail.state.value.detail!!.subtasks.filter { !it.completed }
        assertTrue(open.isNotEmpty())
        for (subtask in open) detail.toggleSubtask(subtask.id)
        assertTrue(open.all { scope.tasks[it.id]?.completed == true })
        assertNull(scope.feedback.lastEvent.value)
    }

    @Test
    fun theSettingIsOnByDefaultAndSilencesEventsWithoutStartingAWindow() = runTest {
        val prefs = InMemoryFeaturePrefs()
        val scope = loaded(prefs = prefs)
        assertTrue(scope.feedback.celebrationsEnabled)
        scope.feedback.celebrationsEnabled = false
        assertFalse(prefs.celebrationsEnabled)
        val ids = scope.open(1)
        for (id in ids) scope.board.toggleComplete(id)
        assertNull(scope.feedback.lastEvent.value)

        prefs.celebrationsEnabled = true // e.g. toggled in Settings
        scope.board.toggleComplete(ids.last())
        scope.board.toggleComplete(ids.last())
        assertEquals(1, scope.feedback.lastEvent.value?.id)
    }

    @Test
    fun theRateLimitSurvivesAWorkspaceSwitch() = runTest {
        val session = startedSession()
        val first = session.scope.value!!
        first.board.load()
        for (id in first.open(1)) first.board.toggleComplete(id)
        assertEquals(1, first.feedback.lastEvent.value?.id)

        session.switchWorkspace(DemoIds.ACME_PILOT)
        session.switchWorkspace(DemoIds.MEKARI)
        val second = session.scope.value!!
        assertTrue(first !== second)
        second.board.load()
        val todo = second.open(0)
        for (id in todo) second.board.toggleComplete(id)
        assertNull("still inside the window from before the switch", second.feedback.lastEvent.value)
        advanceTimeBy(30_000)
        runCurrent() // advanceTimeBy stops just short of tasks due exactly then
        second.board.toggleComplete(todo.last())
        second.board.toggleComplete(todo.last())
        assertEquals(1, second.feedback.lastEvent.value?.id)
    }
}
