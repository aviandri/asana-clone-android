package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.TaskChanges
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeEndpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyTasksStoreTest {
    @Test
    fun groupsFollowTheWebBuckets() = runTest {
        val scope = workspaceScope()
        scope.myTasks.refresh()
        val state = scope.myTasks.state.value
        assertEquals(LoadState.Loaded, state.phase)
        assertEquals(
            listOf(DueBucket.OVERDUE, DueBucket.TODAY, DueBucket.UPCOMING, DueBucket.LATER, DueBucket.NO_DATE),
            state.groups.map { it.bucket }
        )
        assertTitles(listOf("Fix login redirect"), scope, state.groups[0].taskIds)
        assertTitles(listOf("Prepare 1:1 notes", "Push notification settings"), scope, state.groups[1].taskIds)
        val upcoming = state.groups[2].taskIds.map { scope.tasks[it]!!.dueDate!! }
        assertEquals(upcoming.sorted(), upcoming)
        assertEquals(state.groups[0].taskIds + state.groups[1].taskIds, state.dueSoon)
    }

    @Test
    fun aCompletedTaskLingersInItsOldBucketUnlessCompletedAreShown() = runTest {
        val scope = workspaceScope()
        scope.myTasks.refresh()
        val task = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.OVERDUE }.taskIds.first()
        scope.myTasks.toggleComplete(task)
        assertEquals(DueBucket.OVERDUE, scope.myTasks.state.value.groups.first { task in it.taskIds }.bucket)
        testScheduler.advanceTimeBy(301)
        testScheduler.runCurrent()
        assertTrue(scope.myTasks.state.value.groups.none { task in it.taskIds })

        scope.myTasks.setShowCompleted(true)
        val other = scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds.first()
        scope.myTasks.toggleComplete(other)
        assertEquals(DueBucket.COMPLETED, scope.myTasks.state.value.groups.first { other in it.taskIds }.bucket)
    }

    @Test
    fun toggleIsOptimisticAndRevertsOnFailure() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        val task = scope.myTasks.state.value.groups[1].taskIds.first()
        fake.failNext(FakeEndpoint.UPDATE_TASK, ApiError(ApiError.Code.FORBIDDEN, 403, "No"))
        scope.myTasks.toggleComplete(task)
        assertEquals(false, scope.tasks[task]?.completed)
        assertEquals("No", scope.myTasks.state.value.lastError?.message)
        scope.myTasks.clearError()
        assertNull(scope.myTasks.state.value.lastError)
    }

    @Test
    fun reassigningAwayDropsATaskAtOnce() = runTest {
        val scope = workspaceScope()
        scope.myTasks.refresh()
        val task = scope.myTasks.state.value.groups[1].taskIds.last()
        scope.taskDetail(task).setAssignee(bob)
        assertTrue(scope.myTasks.state.value.groups.none { task in it.taskIds })
    }

    @Test
    fun addTaskAndQuickAddShowWithoutAReload() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        assertFalse(scope.myTasks.addTask("   "))
        assertTrue(scope.myTasks.addTask("  Write the report ", TestTime.today))
        assertTrue(
            scope.myTasks.state.value.groups.first { it.bucket == DueBucket.TODAY }.taskIds.any {
                scope.title(it) ==
                    "Write the report"
            }
        )

        assertFalse(scope.quickAdd.canSubmit(" "))
        assertTrue(scope.quickAdd.submit("Quick one", null))
        assertTrue(
            scope.myTasks.state.value.groups.first { it.bucket == DueBucket.NO_DATE }.taskIds.any {
                scope.title(it) ==
                    "Quick one"
            }
        )
        val created = fake.currentTasks().first { it.title == "Quick one" }
        assertEquals(alice.id, created.assignee?.id)
        assertNull(created.projectId)

        fake.failNext(FakeEndpoint.CREATE_TASK, boom())
        assertFalse(scope.quickAdd.submit("Fails", null))
        assertEquals("boom", scope.quickAdd.state.value.lastError?.message)
        assertFalse(scope.quickAdd.state.value.isSubmitting)
    }

    @Test
    fun anOlderRefreshNeverOverwritesANewerOne() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        fake.hold(FakeEndpoint.MY_TASKS)
        val older = launch { scope.myTasks.refresh() }
        runCurrent()
        val newer = launch { scope.myTasks.setShowCompleted(true) }
        runCurrent()
        fake.release(FakeEndpoint.MY_TASKS, newest = true) // the newer answers first (with completed tasks)
        newer.join()
        assertTrue(scope.myTasks.state.value.groups.any { it.bucket == DueBucket.COMPLETED })
        fake.release(FakeEndpoint.MY_TASKS, all = true)
        older.join()
        assertTrue(
            "the older list (no completed) is dropped",
            scope.myTasks.state.value.groups.any {
                it.bucket ==
                    DueBucket.COMPLETED
            }
        )
    }
}

class ProjectListStoreTest {
    @Test
    fun anOlderFailedLoadAfterANewerOneIsIgnored() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        fake.hold(FakeEndpoint.PROJECTS)
        val older = launch { scope.projects.load() }
        runCurrent()
        val newer = launch { scope.projects.load() }
        runCurrent()
        fake.release(FakeEndpoint.PROJECTS, newest = true)
        newer.join()
        assertEquals(LoadState.Loaded, scope.projects.state.value.phase)
        fake.failNext(FakeEndpoint.PROJECTS, ApiError.transport())
        fake.release(FakeEndpoint.PROJECTS, all = true)
        older.join()
        assertEquals(LoadState.Loaded, scope.projects.state.value.phase)
        assertNull(scope.projects.state.value.lastError)
        assertEquals(2, scope.projects.state.value.projects.size)
    }
}

class ProjectStoreTest {
    private suspend fun kotlinx.coroutines.test.TestScope.board(
        fake: com.mekari.project.core.fake.FakeApi = demoFake()
    ): Pair<WorkspaceScope, ProjectStore> {
        val scope = workspaceScope(fake)
        val store = scope.project(DemoIds.BOARD_PROJECT)
        store.load()
        return scope to store
    }

    private fun ProjectStore.ids(section: Int) = state.value.taskIds(state.value.sections[section].id)

    @Test
    fun loadPicksTheDefaultViewAndPermissions() = runTest {
        val (scope, board) = board()
        assertEquals(ProjectStore.ViewMode.BOARD, board.state.value.viewMode)
        assertEquals(listOf("To do", "In progress", "Done"), board.state.value.sections.map { it.name })
        assertTrue(board.state.value.canEdit)
        val readOnly = scope.project(DemoIds.READ_ONLY_PROJECT)
        readOnly.load()
        assertEquals(ProjectStore.ViewMode.LIST, readOnly.state.value.viewMode)
        assertFalse(readOnly.state.value.canEdit)
        // Reloads keep the user's choice.
        board.setViewMode(ProjectStore.ViewMode.LIST)
        board.load()
        assertEquals(ProjectStore.ViewMode.LIST, board.state.value.viewMode)
    }

    @Test
    fun aViewModeChosenDuringTheFirstLoadSticks() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val board = scope.project(DemoIds.BOARD_PROJECT) // defaultView BOARD
        fake.hold(FakeEndpoint.PROJECT)
        val load = launch { board.load() }
        runCurrent()
        board.setViewMode(ProjectStore.ViewMode.LIST)
        fake.release(FakeEndpoint.PROJECT, all = true)
        load.join()
        assertEquals(ProjectStore.ViewMode.LIST, board.state.value.viewMode)
    }

    @Test
    fun completedTasksAreHiddenUntilShown() = runTest {
        val (scope, board) = board()
        assertEquals(emptyList<String>(), board.ids(2))
        board.setShowCompleted(true)
        assertTitles(listOf("Upgrade analytics SDK", "Release 2.0 beta"), scope, board.ids(2))
    }

    @Test
    fun movesAreAnchoredAndANoOpSendsNothing() = runTest {
        val fake = demoFake()
        val (scope, board) = board(fake)
        assertTitles(
            listOf("Push notification settings", "Offline mode for task list", "Dark mode QA pass", "Tablet layout"),
            scope,
            board.ids(0)
        )
        val (push, offline, dark, tablet) = board.ids(0)

        board.move(tablet, board.state.value.sections[0].id, before = offline)
        assertEquals(listOf(push, tablet, offline, dark), board.ids(0))
        val calls = fake.calls.count { it.endpoint == FakeEndpoint.UPDATE_TASK }
        board.move(tablet, board.state.value.sections[0].id, before = offline) // already there
        board.move(tablet, board.state.value.sections[0].id, before = tablet)
        assertEquals("no-op moves send nothing", calls, fake.calls.count { it.endpoint == FakeEndpoint.UPDATE_TASK })

        // Across sections, to the end, and to "No section".
        board.move(push, board.state.value.sections[1].id, before = null)
        assertEquals(push, board.ids(1).last())
        assertEquals(board.state.value.sections[1].id, fake.currentTasks().first { it.id == push }.sectionId)
        board.move(dark, null, before = null)
        assertEquals(listOf(dark), board.state.value.taskIds(null))
        assertTrue(board.state.value.hasUnsectionedTasks)
        assertNull(fake.currentTasks().first { it.id == dark }.sectionId)
    }

    /** "To do" = push, offline, dark, tablet; dark completed (hidden); push and offline squeezed together. */
    private suspend fun crowd(scope: WorkspaceScope, board: ProjectStore): List<String> {
        val (push, offline, dark, tablet) = board.ids(0)
        board.toggleComplete(dark)
        scope.tasks.update(offline, TaskChanges(position = scope.tasks[push]!!.position + 1e-9))
        return listOf(push, offline, dark, tablet)
    }

    @Test
    fun crowdedPositionsAreRenumberedIncludingHiddenCompletedTasks() = runTest {
        val fake = demoFake()
        val (scope, board) = board(fake)
        val (push, offline, dark, tablet) = crowd(scope, board)
        advanceTimeBy(301) // the completed task's linger ends: it's hidden now
        assertEquals(listOf(push, offline, tablet), board.ids(0))

        board.move(tablet, board.state.value.sections[0].id, before = offline)
        assertEquals(listOf(push, tablet, offline), board.ids(0))
        // The whole section is on even steps, the hidden completed task included, in its old place.
        val after = fake.currentTasks().filter {
            it.sectionId == board.state.value.sections[0].id
        }.sortedBy { it.position }
        assertEquals(listOf(push, tablet, offline, dark), after.map { it.id })
        assertEquals(listOf(1000.0, 2000.0, 3000.0, 4000.0), after.map { it.position })
        assertNull(board.state.value.lastError)
    }

    @Test
    fun aRenumberRetriesOnceThenReloadsAndSaysSo() = runTest {
        val fake = demoFake()
        val (scope, board) = board(fake)
        val (_, offline, _, tablet) = crowd(scope, board)

        // One failure: retried, and the order sticks.
        fake.failNext(FakeEndpoint.UPDATE_TASK, boom())
        board.move(tablet, board.state.value.sections[0].id, before = offline)
        assertNull(board.state.value.lastError)

        // Failing for good: crowd push and tablet, move offline between them; reload and say so.
        val (push, movedTablet, movedOffline) = board.ids(0)
        assertEquals(listOf(tablet, offline), listOf(movedTablet, movedOffline))
        scope.tasks.update(tablet, TaskChanges(position = scope.tasks[push]!!.position + 1e-9))
        fake.failNext(FakeEndpoint.UPDATE_TASK, boom(), times = 20)
        val loads = fake.calls.count { it.endpoint == FakeEndpoint.PROJECT }
        board.move(offline, board.state.value.sections[0].id, before = tablet)
        assertEquals("Couldn't reorder; try again", board.state.value.lastError?.message)
        assertEquals(loads + 1, fake.calls.count { it.endpoint == FakeEndpoint.PROJECT })
    }

    @Test
    fun aFailedMoveRevertsAndReports() = runTest {
        val fake = demoFake()
        val (_, board) = board(fake)
        val before = board.ids(0)
        fake.failNext(FakeEndpoint.UPDATE_TASK, ApiError(ApiError.Code.FORBIDDEN, 403, "Read only"))
        board.move(before.last(), board.state.value.sections[0].id, before = before.first())
        assertEquals(before, board.ids(0))
        assertEquals("Read only", board.state.value.lastError?.message)
    }

    @Test
    fun addTaskSectionsRenameAndMoveSection() = runTest {
        val fake = demoFake()
        val (scope, board) = board(fake)
        assertTrue(board.addTask(" Ship it ", board.state.value.sections[0].id))
        assertEquals("Ship it", scope.title(board.ids(0).last()))
        assertFalse(board.addTask("  ", null))

        board.addSection("QA")
        assertEquals(listOf("To do", "In progress", "Done", "QA"), board.state.value.sections.map { it.name })
        val qa = board.state.value.sections.last().id
        board.moveSection(qa, before = board.state.value.sections[0].id)
        assertEquals(listOf("QA", "To do", "In progress", "Done"), board.state.value.sections.map { it.name })
        board.renameSection(qa, "Testing")
        assertEquals("Testing", board.state.value.sections[0].name)

        fake.failNext(FakeEndpoint.UPDATE_SECTION, boom())
        board.renameSection(qa, "Nope")
        assertEquals("a failed rename reverts", "Testing", board.state.value.sections[0].name)
        fake.failNext(FakeEndpoint.UPDATE_SECTION, boom())
        board.moveSection(qa, before = null)
        assertEquals("a failed move reverts", "Testing", board.state.value.sections[0].name)
    }

    @Test
    fun commenterEditsAreRejectedAndReverted() = runTest {
        val scope = workspaceScope()
        val readOnly = scope.project(DemoIds.READ_ONLY_PROJECT)
        readOnly.load()
        val task = readOnly.state.value.taskIds(readOnly.state.value.sections[0].id).first()
        readOnly.toggleComplete(task)
        assertEquals(false, scope.tasks[task]?.completed)
        assertEquals(ApiError.Code.FORBIDDEN, readOnly.state.value.lastError?.code)
    }
}
