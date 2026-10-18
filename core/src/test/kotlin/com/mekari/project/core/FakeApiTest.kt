package com.mekari.project.core

import app.cash.turbine.test
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import com.mekari.project.core.fake.FakeSeed
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake applies the BE rules the UI and the :feature tests rely on. */
class FakeApiTest {
    private val today = LocalDate.of(2026, 10, 18)

    private suspend fun signedIn(email: String = "alice@example.com", workspace: String? = DemoIds.MEKARI): FakeApi {
        val fake = FakeApi(FakeSeed.demo(today), signedInAs = email)
        fake.setWorkspace(workspace)
        return fake
    }

    private suspend fun expectError(code: ApiError.Code, block: suspend () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiError) {
            assertEquals(e.message, code, e.code)
            return e
        }
        fail("expected $code")
        throw AssertionError()
    }

    @Test
    fun signedOutBootAndLogin() = runTest {
        val fake = FakeApi(FakeSeed.demo(today), signedInAs = null)
        assertFalse(fake.hasSession)
        fake.events.test {
            // Like LiveApi: signed out, no token is sent, so no Unauthorized event.
            expectError(ApiError.Code.UNAUTHORIZED) { fake.me() }
            expectNoEvents()
        }
        expectError(ApiError.Code.UNAUTHORIZED) { fake.login("alice@example.com", "nope") }
        val me = fake.login("ALICE@example.com", DemoIds.PASSWORD)
        assertEquals(DemoIds.ALICE, me.id)
        assertEquals(listOf("Acme Pilot", "Mekari"), me.workspaces.map { it.name })
        assertTrue(fake.hasSession)
    }

    @Test
    fun theHeaderScopesCollectionsAndLostAccessEmitsAnEvent() = runTest {
        val fake = signedIn()
        // Q4 Campaign is a TEAM project of Marketing, which alice isn't in.
        assertEquals(listOf("Mobile App v2", "Website Redesign"), fake.projects().map { it.name })
        fake.setWorkspace(DemoIds.ACME_PILOT)
        assertEquals(listOf("Pilot Rollout"), fake.projects().map { it.name })
        assertEquals(DemoIds.ACME_PILOT, fake.calls.last().workspaceId)

        val acmeTask = fake.myTasks(includeCompleted = false).first().id
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        fake.events.test {
            expectError(ApiError.Code.NOT_FOUND) { fake.projects() }
            assertEquals(ApiEvent.WorkspaceLost(DemoIds.ACME_PILOT), awaitItem())
            expectError(ApiError.Code.NOT_FOUND) { fake.task(acmeTask) } // id-addressed: no event
            expectNoEvents()
        }
        // /auth/me ignores the header and falls back to a workspace I still have.
        assertEquals(DemoIds.MEKARI, fake.me().currentWorkspace?.id)
    }

    @Test
    fun myTasksCoverEveryBucket() = runTest {
        val mine = signedIn().myTasks(includeCompleted = false)
        val due = mine.map { it.dueDate?.let { d -> java.time.temporal.ChronoUnit.DAYS.between(today, d) } }
        assertTrue(due.any { it != null && it < 0 })
        assertTrue(due.any { it == 0L })
        assertTrue(due.any { it != null && it in 1..7 })
        assertTrue(due.any { it != null && it > 7 })
        assertTrue(due.any { it == null })
        assertTrue(mine.none { it.completed })
        assertTrue(signedIn().myTasks(includeCompleted = true).any { it.completed })
    }

    @Test
    fun aCommenterIsReadOnly() = runTest {
        val fake = signedIn()
        val readOnly = fake.projectTasks(DemoIds.READ_ONLY_PROJECT).first()
        expectError(ApiError.Code.FORBIDDEN) { fake.updateTask(readOnly.id, TaskChanges(title = "Nope")) }
        expectError(ApiError.Code.FORBIDDEN) {
            fake.createTask(NewTask(title = "Nope", projectId = DemoIds.READ_ONLY_PROJECT))
        }
        assertEquals(ProjectRole.COMMENTER, fake.projects().first { it.id == DemoIds.READ_ONLY_PROJECT }.myRole)
        // Commenters can comment.
        fake.addComment(readOnly.id, "Looks good")
    }

    @Test
    fun aGuestSeesOnlyTheirProjectAndCoMembers() = runTest {
        val fake = signedIn(DemoIds.GUEST_EMAIL, DemoIds.ACME_PILOT)
        assertEquals(listOf("Pilot Rollout"), fake.projects().map { it.name })
        val users = fake.users(null)
        assertEquals(setOf(DemoIds.ALICE, DemoIds.GUEST), users.map { it.id }.toSet())
        assertTrue(users.first { it.id == DemoIds.GUEST }.isGuest)
        assertTrue(fake.search("pilot").users.isEmpty())
    }

    @Test
    fun createUpdateAndNotify() = runTest {
        val alice = signedIn()
        val created = alice.createTask(
            NewTask(title = "  New task ", projectId = DemoIds.BOARD_PROJECT, assigneeId = DemoIds.BOB)
        )
        assertEquals("New task", created.task.title)
        assertTrue(created.followers.any { it.id == DemoIds.BOB })
        val updated = alice.updateTask(created.id, TaskChanges(completed = true, assigneeId = Patch.Clear))
        assertTrue(updated.task.completed)
        assertNull(updated.task.assignee)
        expectError(ApiError.Code.VALIDATION) { alice.updateTask(created.id, TaskChanges(title = " ")) }
        expectError(ApiError.Code.VALIDATION) {
            alice.updateTask(created.id, TaskChanges(assigneeId = Patch.Set(DemoIds.GUEST)))
        }

        alice.login("bob@example.com", DemoIds.PASSWORD) // same fake, now as bob
        val inbox = alice.notifications().data.filter { it.task?.id == created.id }.map { it.type }.toSet()
        assertEquals(setOf(NotificationType.ASSIGNED, NotificationType.COMPLETED), inbox)
        val types = alice.stories(created.id).filterIsInstance<Activity>().map { it.type }
        assertEquals(
            // Creating with an assignee notifies but records no ASSIGNED activity (the BE's "created" covers it).
            listOf(ActivityType.CREATED, ActivityType.COMPLETED, ActivityType.UNASSIGNED),
            types.distinct()
        )
    }

    @Test
    fun commentsMentionAndInbox() = runTest {
        val fake = signedIn()
        val bob = UserSummary(DemoIds.BOB, "Bob Smith", "bob@example.com", "#20aaea")
        val guest = UserSummary(DemoIds.GUEST, "Gita Guest", DemoIds.GUEST_EMAIL, "#000")
        fake.addComment(DemoIds.TASK_WITH_STORIES, "Ping ${Mentions.token(bob)}")
        // Only members of the task's workspace can be mentioned.
        expectError(ApiError.Code.VALIDATION) {
            fake.addComment(DemoIds.TASK_WITH_STORIES, "Hi ${Mentions.token(guest)}")
        }

        fake.login("bob@example.com", DemoIds.PASSWORD) // same fake, now as bob
        val mentions = fake.notifications(unreadOnly = true).data
            .filter { it.type == NotificationType.MENTIONED && it.actor.id == DemoIds.ALICE }
        assertEquals(1, mentions.size)
    }

    @Test
    fun searchNeedsTwoCharactersAndRespectsVisibility() = runTest {
        val alice = signedIn()
        expectError(ApiError.Code.VALIDATION) { alice.search(" a ") }
        assertEquals(listOf("Fix login redirect"), alice.search("login").tasks.map { it.title })
        assertTrue(alice.search("q4").projects.isEmpty()) // TEAM project of a team alice isn't in
    }

    @Test
    fun noWorkspaceIs403() = runTest {
        val fake = signedIn(workspace = null)
        fake.removeMembership(DemoIds.ALICE, DemoIds.MEKARI)
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        assertTrue(expectError(ApiError.Code.FORBIDDEN) { fake.projects() }.isNoWorkspace)
    }

    @Test
    fun movingATaskBetweenProjectsFollowsTheBeRules() = runTest {
        val fake = signedIn()
        val subtask = fake.task(DemoIds.TASK_WITH_STORIES).subtasks.first()
        expectError(ApiError.Code.VALIDATION) {
            fake.updateTask(subtask.id, TaskChanges(projectId = Patch.Set(DemoIds.CAMPAIGN_PROJECT)))
        }
        // A project in another workspace.
        expectError(ApiError.Code.VALIDATION) {
            fake.updateTask(DemoIds.TASK_WITH_STORIES, TaskChanges(projectId = Patch.Set(DemoIds.PILOT_PROJECT)))
        }
        val personal = fake.createTask(NewTask(title = "Loose", assigneeId = DemoIds.ALICE))
        val moved = fake.updateTask(personal.id, TaskChanges(projectId = Patch.Set(DemoIds.BOARD_PROJECT)))
        assertEquals(DemoIds.BOARD_PROJECT, moved.task.projectId)
        assertNull(moved.task.sectionId)
    }

    @Test
    fun failNextHoldReleaseAndLatency() = runTest {
        val fake = signedIn()
        fake.failNext(FakeEndpoint.MY_TASKS, ApiError.transport(), times = 2)
        repeat(2) { expectError(ApiError.Code.TRANSPORT) { fake.myTasks(includeCompleted = false) } }
        fake.myTasks(includeCompleted = false)

        // Held calls run in the order released, one at a time.
        fake.hold(FakeEndpoint.UPDATE_TASK)
        val first = async { fake.updateTask(DemoIds.TASK_WITH_STORIES, TaskChanges(title = "First")) }
        val second = async { fake.updateTask(DemoIds.TASK_WITH_STORIES, TaskChanges(title = "Second")) }
        runCurrent()
        assertEquals(2, fake.parkedCount(FakeEndpoint.UPDATE_TASK))
        fake.release(FakeEndpoint.UPDATE_TASK, newest = true)
        assertEquals("Second", second.await().task.title)
        fake.release(FakeEndpoint.UPDATE_TASK, all = true)
        assertEquals("First", first.await().task.title)
        assertEquals(0, fake.parkedCount(FakeEndpoint.UPDATE_TASK))

        // An unauthorized failure signs the fake out and emits, like the live client.
        fake.events.test {
            fake.failNext(FakeEndpoint.ME, ApiError(ApiError.Code.UNAUTHORIZED, 401, "expired"))
            expectError(ApiError.Code.UNAUTHORIZED) { fake.me() }
            assertEquals(ApiEvent.Unauthorized, awaitItem())
        }
        assertFalse(fake.hasSession)

        // Latency runs on virtual time.
        val slow = signedIn()
        slow.latency = 300.milliseconds
        val call = async { slow.projects() }
        advanceTimeBy(299)
        assertFalse(call.isCompleted)
        advanceTimeBy(2)
        assertEquals(2, call.await().size)
    }
}
