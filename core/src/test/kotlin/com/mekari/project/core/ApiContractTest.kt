package com.mekari.project.core

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * [LiveApi] against a real, freshly seeded BE (`npm run seed`); a port of the Mac ContractTests.
 * Skipped unless MP_BASE_URL is set. Never point it at a shared database: it creates tasks and comments.
 */
class ApiContractTest {
    private lateinit var server: String

    @Before
    fun requireBackend() {
        val raw = System.getenv("MP_BASE_URL").orEmpty()
        assumeTrue("MP_BASE_URL not set", raw.isNotBlank())
        server = ServerUrl.normalize(raw)!!
    }

    /** A signed-in client with its current workspace sent as the header. */
    private suspend fun signIn(email: String): Pair<LiveApi, Me> {
        val api = LiveApi(server, InMemoryTokenStore())
        val me = api.login(email, PASSWORD)
        api.setWorkspace(me.currentWorkspace?.id)
        return api to me
    }

    private fun Me.workspace(name: String) = workspaces.firstOrNull { it.name == name } ?: error("no workspace $name")

    private suspend fun expectError(code: ApiError.Code, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ApiError) {
            assertEquals(e.message, code, e.code)
        }
    }

    // Auth & session

    @Test
    fun healthLoginCookieAndMe() = runBlocking {
        val api = LiveApi(server, InMemoryTokenStore())
        api.health()
        expectError(ApiError.Code.UNAUTHORIZED) { api.login("alice@example.com", "wrong-password") }
        val me = api.login("alice@example.com", PASSWORD)
        assertEquals("Alice Johnson", me.user.name)
        assertEquals(setOf("Mekari", "Acme Pilot"), me.workspaces.map { it.name }.toSet())
        assertEquals(me.workspaces.map { it.name }.sorted(), me.workspaces.map { it.name })
        assertTrue(api.hasSession)
        assertEquals(me.id, api.me().id)
    }

    @Test
    fun a401ClearsTheSessionAndEmitsAnEvent() = runBlocking {
        val tokens = InMemoryTokenStore("not-a-valid-jwt")
        val api = LiveApi(server, tokens)
        expectError(ApiError.Code.UNAUTHORIZED) { api.me() }
        assertNull(tokens.load())
        assertEquals(ApiEvent.Unauthorized, withTimeout(5_000) { api.events.first() })
    }

    @Test
    fun logout() = runBlocking {
        val (api, _) = signIn("bob@example.com")
        api.logout()
        assertFalse(api.hasSession)
        expectError(ApiError.Code.UNAUTHORIZED) { api.me() }
    }

    // Workspaces

    @Test
    fun theHeaderScopesListsAndSwitchMovesTheDefault() = runBlocking {
        val (api, me) = signIn("alice@example.com")
        val mekari = me.workspace("Mekari")
        val acme = me.workspace("Acme Pilot")

        api.setWorkspace(acme.id)
        assertEquals(listOf("Pilot Rollout"), api.projects().map { it.name })
        api.setWorkspace(mekari.id)
        val mekariProjects = api.projects().map { it.name }
        assertTrue("Mobile App v2" in mekariProjects)
        assertFalse("Pilot Rollout" in mekariProjects)

        val switched = api.switchWorkspace(acme.id)
        assertEquals(acme.id, switched.id)
        assertEquals(WorkspaceRole.ADMIN, switched.myRole)
        assertEquals(acme.id, api.me().currentWorkspace?.id)
        api.switchWorkspace(mekari.id)
        Unit
    }

    @Test
    fun aForeignWorkspaceHeaderIsLostAccess() = runBlocking {
        val (api, _) = signIn("admin@example.com")
        val foreign = "00000000-0000-4000-8000-00000000dead"
        api.setWorkspace(foreign)
        expectError(ApiError.Code.NOT_FOUND) { api.projects() }
        assertEquals(ApiEvent.WorkspaceLost(foreign), withTimeout(5_000) { api.events.first() })
        // /auth/me never sends the header, so the session survives.
        assertNotNull(api.me().currentWorkspace)
    }

    // Tasks

    @Test
    fun myTasksAndProjectTasksDecode() = runBlocking {
        val (api, _) = signIn("alice@example.com")
        val mine = api.myTasks(includeCompleted = true)
        assertTrue(mine.isNotEmpty())
        assertTrue(mine.all { it.assignee?.name == "Alice Johnson" })
        assertTrue(api.myTasks(includeCompleted = false).none { it.completed })

        val project = api.projects().first { it.name == "Mobile App v2" }
        assertTrue(api.project(project.id).sections.isNotEmpty())
        val tasks = api.projectTasks(project.id, completed = null)
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.all { it.parentId == null && it.projectId == project.id })
    }

    @Test
    fun createPatchCompleteAndDeleteATask() = runBlocking {
        val (api, me) = signIn("alice@example.com")
        val project = api.projects().first { it.name == "Mobile App v2" }
        val section = api.project(project.id).sections.first()
        val due = LocalDate.now().plusDays(3)

        val created = api.createTask(
            NewTask(
                title = "Contract test task",
                projectId = project.id,
                sectionId = section.id,
                assigneeId = me.id,
                dueDate = due,
                priority = Priority.MEDIUM
            )
        )
        assertEquals("Contract test task", created.task.title)
        assertEquals(due, created.task.dueDate)
        assertEquals(section.id, created.task.sectionId)
        assertEquals(project.workspaceId, created.task.workspaceId)

        val subtask = api.createTask(NewTask(title = "Contract subtask", parentId = created.id))
        assertEquals(created.id, subtask.task.parentId)

        val patched = api.updateTask(
            created.id,
            TaskChanges(
                title = "Renamed",
                description = Patch.Set("**bold**"),
                dueDate = Patch.Clear,
                priority = Priority.HIGH,
                position = 123.5
            )
        )
        assertEquals("Renamed", patched.task.title)
        assertEquals("**bold**", patched.task.description)
        assertNull(patched.task.dueDate)
        assertEquals(123.5, patched.task.position, 0.0)
        assertEquals(listOf(subtask.id), patched.subtasks.map { it.id })

        val done = api.updateTask(created.id, TaskChanges(completed = true))
        assertTrue(done.task.completed)
        assertNotNull(done.task.completedAt)
        assertNull(api.updateTask(created.id, TaskChanges(assigneeId = Patch.Clear)).task.assignee)

        expectError(ApiError.Code.VALIDATION) { api.updateTask(created.id, TaskChanges(title = "")) }
        api.deleteTask(created.id)
        expectError(ApiError.Code.NOT_FOUND) { api.task(created.id) }
    }

    @Test
    fun personalTaskAndFollowers() = runBlocking {
        val (api, me) = signIn("alice@example.com")
        val bob = api.users("bob").first()
        assertEquals(WorkspaceRole.MEMBER, bob.role)
        val task = api.createTask(NewTask(title = "Personal contract task", assigneeId = me.id))
        assertNull(task.task.projectId)
        assertTrue(api.addFollower(task.id, bob.id).followers.any { it.id == bob.id })
        assertFalse(api.removeFollower(task.id, bob.id).followers.any { it.id == bob.id })
        api.deleteTask(task.id)
    }

    // Stories, inbox, search

    @Test
    fun commentsWithMentionsNotifyAndCanBeEdited() = runBlocking {
        val (alice, _) = signIn("alice@example.com")
        val (bob, bobMe) = signIn("bob@example.com")
        val project = alice.projects().first { it.name == "Mobile App v2" }
        val task = alice.createTask(NewTask(title = "Mention contract task", projectId = project.id))

        val comment = alice.addComment(task.id, "Hi ${Mentions.token(bobMe.user.summary)}, please check")
        assertTrue(Mentions.Segment.Mention("Bob Smith", bobMe.id) in comment.segments)
        assertNotNull(alice.editComment(comment.id, "Edited").editedAt)
        expectError(ApiError.Code.FORBIDDEN) { bob.editComment(comment.id, "Not mine") }

        val stories = alice.stories(task.id)
        assertTrue(stories.any { it is Comment && it.id == comment.id })
        assertTrue(stories.any { it is Activity && it.type == ActivityType.CREATED })

        val inbox = bob.notifications(unreadOnly = true)
        val mention = inbox.data.first { it.task?.id == task.id && it.type == NotificationType.MENTIONED }
        assertTrue(inbox.unreadCount > 0)
        bob.markRead(mention.id)
        assertEquals(true, bob.notifications().data.firstOrNull { it.id == mention.id }?.read)
        bob.markAllRead()
        assertEquals(0, bob.notifications(unreadOnly = true).unreadCount)

        alice.deleteComment(comment.id)
        alice.deleteTask(task.id)
    }

    @Test
    fun search() = runBlocking {
        val (api, _) = signIn("alice@example.com")
        expectError(ApiError.Code.VALIDATION) { api.search("a") }
        assertTrue(api.search("mobile").projects.any { it.name == "Mobile App v2" })
        assertTrue(api.search("bob").users.any { it.name == "Bob Smith" })
    }

    // Guests (W1, W7, W8)

    @Test
    fun aGuestSeesOnlyTheirProjectAndCoMembersWithRoles() = runBlocking {
        val (guest, me) = signIn("guest@partner.test")
        assertEquals(listOf("Acme Pilot"), me.workspaces.map { it.name })
        assertEquals(WorkspaceRole.GUEST, me.currentWorkspace?.myRole)
        val projects = guest.projects()
        assertEquals(listOf("Pilot Rollout"), projects.map { it.name })
        assertEquals(ProjectRole.EDITOR, projects.first().myRole)

        val users = guest.users(null)
        assertTrue(users.any { it.id == me.id && it.isGuest })
        assertFalse("Bob isn't on Pilot Rollout", users.any { it.name == "Bob Smith" })
        assertTrue(users.any { it.name == "Alice Johnson" && it.role == WorkspaceRole.ADMIN })

        // As an editor the guest can still create tasks in their project (W7 limits managing, not editing).
        val task = guest.createTask(NewTask(title = "Guest contract task", projectId = projects.first().id))
        assertEquals(me.id, task.task.creator.id)
        guest.deleteTask(task.id)
    }

    @Test
    fun membersSeeGuestsBadgedInPickers() = runBlocking {
        val (alice, me) = signIn("alice@example.com")
        alice.setWorkspace(me.workspace("Acme Pilot").id)
        assertTrue(alice.users(null).any { it.user.email == "guest@partner.test" && it.isGuest })
    }

    private companion object {
        const val PASSWORD = "password123"
    }
}
