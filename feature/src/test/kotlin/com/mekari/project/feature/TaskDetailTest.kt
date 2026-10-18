package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.Comment
import com.mekari.project.core.PickableUser
import com.mekari.project.core.Priority
import com.mekari.project.core.WorkspaceRole
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeEndpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetailTest {
    @Test
    fun theCachedRowShowsAtOnceThenTheDetailLoads() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        fake.hold(FakeEndpoint.TASK)
        val detail = scope.taskDetail(DemoIds.TASK_WITH_STORIES)
        assertEquals("Fix login redirect", detail.state.value.task?.title)
        assertNull("followers etc. are still loading", detail.state.value.detail)
        val load = launch { detail.load() }
        runCurrent()
        // "Loading details…" for the extra parts only: the cached row stays on screen meanwhile.
        assertEquals(LoadState.Loading, detail.state.value.phase)
        assertEquals("Fix login redirect", detail.state.value.task?.title)
        fake.release(FakeEndpoint.TASK, all = true)
        load.join()
        val state = detail.state.value
        assertEquals(LoadState.Loaded, state.phase)
        assertEquals(2, state.detail?.subtasks?.size)
        assertTrue(state.isFollowing)
        val comments = state.stories.filterIsInstance<Comment>()
        assertEquals(2, comments.size)
        assertTrue(
            comments[0].segments.any {
                it is com.mekari.project.core.Mentions.Segment.Mention &&
                    it.userId == DemoIds.BOB
            }
        )
        assertTrue(scope.taskDetail(DemoIds.TASK_WITH_STORIES) === detail)
    }

    @Test
    fun editsGoThroughTheCache() = runTest {
        val scope = workspaceScope()
        val detail = scope.taskDetail(DemoIds.TASK_WITH_STORIES)
        detail.load()
        detail.setTitle("  Fix login redirect for SSO ")
        assertEquals("Fix login redirect for SSO", scope.title(DemoIds.TASK_WITH_STORIES))
        detail.setAssignee(bob)
        assertEquals(bob.id, detail.state.value.task?.assignee?.id)
        detail.setDueDate(TestTime.today.plusDays(1))
        detail.setPriority(Priority.LOW)
        detail.setDescription("  ")
        assertNull(scope.tasks[DemoIds.TASK_WITH_STORIES]?.description)
        assertEquals(TestTime.today.plusDays(1), scope.tasks[DemoIds.TASK_WITH_STORIES]?.dueDate)

        detail.unfollow()
        assertFalse(detail.state.value.isFollowing)
        detail.follow()
        assertTrue(detail.state.value.isFollowing)

        assertTrue(detail.addSubtask("Write a test"))
        assertEquals(3, detail.state.value.detail?.subtasks?.size)
        val sub = detail.state.value.detail!!.subtasks.first { !it.completed }
        detail.toggleSubtask(sub.id)
        assertEquals(true, detail.state.value.detail?.subtasks?.first { it.id == sub.id }?.completed)
    }

    @Test
    fun theKnownRoleAppliesBeforeTheFetchAndTheFetchWins() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        scope.myTasks.refresh()
        val editable = scope.myTasks.state.value.groups.flatMap { it.taskIds }.first {
            scope.tasks[it]?.projectId ==
                DemoIds.BOARD_PROJECT
        }
        val detail = scope.taskDetail(editable)
        assertFalse("nobody knows the project yet: read-only", detail.state.value.canEdit)
        scope.projects.load()
        assertTrue("the Projects tab knows I'm EDITOR", detail.state.value.canEdit)

        val readOnly = scope.project(DemoIds.READ_ONLY_PROJECT)
        readOnly.load()
        val readOnlyTask = readOnly.state.value.taskIds(readOnly.state.value.sections[0].id).first()
        val readOnlyDetail = scope.taskDetail(readOnlyTask)
        assertFalse(readOnlyDetail.state.value.canEdit)

        // A failed role fetch falls back to the known role.
        fake.failNext(FakeEndpoint.PROJECT, ApiError.transport())
        detail.load()
        assertTrue(detail.state.value.canEdit)

        // Personal tasks need no role.
        val personal = scope.myTasks.state.value.groups.flatMap { it.taskIds }.first {
            scope.tasks[it]?.projectId ==
                null
        }
        assertTrue(scope.taskDetail(personal).state.value.canEdit)
        assertTrue(scope.canEdit(scope.tasks[personal]!!))
        assertFalse(scope.canEdit(scope.tasks[readOnlyTask]!!))
    }

    @Test
    fun workspaceAdminsCanAlwaysEdit() = runTest {
        val fake = demoFake("admin@example.com")
        val admin = com.mekari.project.core.UserSummary(DemoIds.ADMIN, "Ada Admin", "admin@example.com", "#7a6ff0")
        val scope = workspaceScope(fake, me = admin)
        assertEquals(WorkspaceRole.ADMIN, scope.workspace.myRole)
        val project = scope.project(DemoIds.READ_ONLY_PROJECT)
        project.load()
        val task = project.state.value.taskIds(project.state.value.sections[0].id).first()
        assertTrue(scope.taskDetail(task).state.value.canEdit)
        assertTrue(scope.canEdit(scope.tasks[task]!!))
    }

    @Test
    fun commenterEditsAreRejectedAndReverted() = runTest {
        val scope = workspaceScope()
        val project = scope.project(DemoIds.READ_ONLY_PROJECT)
        project.load()
        val task = project.state.value.taskIds(project.state.value.sections[0].id).first()
        val detail = scope.taskDetail(task)
        detail.load()
        assertFalse(detail.state.value.canEdit)
        val title = detail.state.value.task?.title
        detail.setTitle("Nope")
        assertEquals(ApiError.Code.FORBIDDEN, detail.state.value.lastError?.code)
        assertEquals(title, detail.state.value.task?.title)
    }

    @Test
    fun theComposerFindsMentionsAtTheCursorAndKeepsTheUiInChargeOfTheText() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val detail = scope.taskDetail(DemoIds.TASK_WITH_STORIES)
        detail.load()
        val composer = detail.composer

        assertEquals("bo", CommentComposer.mentionQuery("Hi @bo", 6))
        assertEquals("", CommentComposer.mentionQuery("@", 1))
        assertNull("@ inside a word", CommentComposer.mentionQuery("mail@bo", 7))
        assertNull("a finished token", CommentComposer.mentionQuery("@[Bob](u2)", 10))
        assertEquals("cursor in the middle", "bo", CommentComposer.mentionQuery("Hi @bob there", 6))

        composer.draftChanged("Ping @bo", 8)
        assertEquals("bo", composer.state.value.mentionQuery)
        advanceTimeBy(251)
        runCurrent()
        val bobPick = composer.users.state.value.results.single()
        assertEquals("Bob Smith", bobPick.name)

        // Typing elsewhere with the same mention at the cursor doesn't search again.
        val searches = fake.calls.count { it.endpoint == FakeEndpoint.USERS }
        composer.draftChanged("Ping @bo", 8)
        composer.draftChanged("Ping @bo", 8)
        advanceTimeBy(251)
        runCurrent()
        assertEquals(searches, fake.calls.count { it.endpoint == FakeEndpoint.USERS })

        val (text, cursor) = composer.insertMention(bobPick, "Ping @bo and more", 8)
        assertEquals("Ping @[Bob Smith](${DemoIds.BOB})  and more", text)
        assertEquals("Ping @[Bob Smith](${DemoIds.BOB}) ".length, cursor)
        assertNull(composer.state.value.mentionQuery)

        assertFalse(composer.canSend("   "))
        assertTrue(composer.send(text))
        assertTrue(detail.state.value.stories.any { it is Comment && it.body.startsWith("Ping @[Bob Smith]") })
        assertFalse(composer.state.value.isSending)
    }

    @Test
    fun aFailedSendKeepsTheTextAndReports() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val detail = scope.taskDetail(DemoIds.TASK_WITH_STORIES)
        detail.load()
        val before = detail.state.value.stories.size
        fake.failNext(FakeEndpoint.ADD_COMMENT, boom())
        assertFalse(detail.composer.send("Hello"))
        assertEquals("boom", detail.composer.state.value.lastError?.message)
        assertEquals(before, detail.state.value.stories.size)
    }

    @Test
    fun userSearchIsDebouncedAndDropsStaleResults() = runTest {
        val fake = demoFake()
        val scope = workspaceScope(fake)
        val search = scope.userSearch()
        search.setQuery("a")
        search.setQuery("al")
        advanceTimeBy(249)
        runCurrent()
        assertEquals(0, fake.calls.count { it.endpoint == FakeEndpoint.USERS })
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, fake.calls.count { it.endpoint == FakeEndpoint.USERS })
        assertEquals(listOf("Alice Johnson"), search.state.value.results.map { it.name })
        assertFalse(search.state.value.isLoading)

        // A slow older answer never replaces a newer one.
        fake.hold(FakeEndpoint.USERS)
        search.setQuery("bob")
        advanceTimeBy(251)
        runCurrent()
        search.setQuery("ada")
        advanceTimeBy(251)
        runCurrent()
        fake.release(FakeEndpoint.USERS, newest = true)
        runCurrent()
        fake.release(FakeEndpoint.USERS, all = true)
        runCurrent()
        assertEquals(listOf("Ada Admin"), search.state.value.results.map(PickableUser::name))
        assertNotNull(search.state.value.query)
    }
}
