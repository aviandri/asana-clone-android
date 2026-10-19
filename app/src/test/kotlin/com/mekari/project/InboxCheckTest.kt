package com.mekari.project

import com.mekari.project.core.ApiError
import com.mekari.project.core.InboxNotification
import com.mekari.project.core.NotificationType
import com.mekari.project.core.UserSummary
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each background-check outcome the worker logs (QA A6). */
class InboxCheckTest {
    private val baselines = mutableMapOf<Pair<String, String>, Set<String>>()

    private fun check(api: FakeApi?, hasToken: Boolean = true) = runBlocking {
        runInboxCheck(
            api,
            hasToken,
            seen = { user, workspace -> baselines[user to workspace] },
            setSeen = { user, workspace, ids -> baselines[user to workspace] = ids }
        )
    }

    private fun notify(fake: FakeApi, id: String) {
        val actor = UserSummary(DemoIds.BOB, "Bob Smith", "bob@example.com", "#20aaea")
        fake.addNotification(
            InboxNotification(id, DemoIds.MEKARI, NotificationType.ASSIGNED, false, Instant.now(), actor),
            forUser = DemoIds.ALICE
        )
    }

    @Test
    fun noServerAndNoTokenStopBeforeAnyRequest() {
        val fake = FakeApi()
        assertEquals("no-server", check(null).outcome)
        assertEquals("no-token", check(fake, hasToken = false).outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun theFirstCheckOnlyRecordsABaselineThenReportsWhatIsNew() {
        val fake = FakeApi()
        assertEquals("baseline-only", check(fake).outcome)
        assertEquals("0-new", check(fake).outcome)
        notify(fake, "n-new-1")
        val next = check(fake)
        assertEquals("1-new", next.outcome)
        assertEquals(listOf("n-new-1"), next.notices.map { it.id })
        assertEquals("1-new/posted", next.describe(blocked = false))
        assertEquals("1-new/notifications-blocked", next.describe(blocked = true))
        assertEquals("0-new", check(fake).describe(blocked = true))
    }

    @Test
    fun failuresAreNamedByStatusOrKind() {
        val fake = FakeApi()
        fake.failNext(FakeEndpoint.NOTIFICATIONS, ApiError(ApiError.Code.INTERNAL, 500, "boom"))
        assertEquals("http-500", check(fake).outcome)
        fake.failNext(FakeEndpoint.ME, ApiError.transport())
        assertEquals("exception-transport", check(fake).outcome)
        // Last: the fake ends its session on a 401, like the server.
        fake.failNext(FakeEndpoint.ME, ApiError(ApiError.Code.UNAUTHORIZED, 401, "Session expired or invalid"))
        assertEquals("http-401", check(fake).outcome)
        assertTrue("a failure leaves no baseline", baselines.isEmpty())
    }

    @Test
    fun aUserWithoutAWorkspaceHasNothingToCheck() {
        val fake = FakeApi()
        fake.removeMembership(DemoIds.ALICE, DemoIds.MEKARI)
        fake.removeMembership(DemoIds.ALICE, DemoIds.ACME_PILOT)
        assertEquals("no-current-workspace", check(fake).outcome)
    }
}
