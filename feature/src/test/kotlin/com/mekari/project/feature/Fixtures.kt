package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.UserSummary
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeSeed
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals

val alice = UserSummary(DemoIds.ALICE, "Alice Johnson", "alice@example.com", "#e8384f")
val bob = UserSummary(DemoIds.BOB, "Bob Smith", "bob@example.com", "#20aaea")

fun demoFake(email: String? = "alice@example.com") = FakeApi(FakeSeed.demo(TestTime.today), signedInAs = email)

fun boom(status: Int = 500) = ApiError(ApiError.Code.INTERNAL, status, "boom")

/**
 * A workspace scope on [fake] (default: Mekari as alice) with the test clock. Timers (linger, cooldown,
 * debounce, poller) run on the test's virtual time; the poller is not started.
 */
internal suspend fun TestScope.workspaceScope(
    fake: FakeApi = demoFake(),
    workspaceId: String = DemoIds.MEKARI,
    me: UserSummary = alice,
    prefs: FeaturePrefs = InMemoryFeaturePrefs(),
    cooldown: CelebrationCooldown = CelebrationCooldown(backgroundScope)
): WorkspaceScope {
    fake.setWorkspace(workspaceId)
    val workspace = fake.me().workspaces.first { it.id == workspaceId }
    return WorkspaceScope(fake, workspace, me, backgroundScope, TestTime.clock, prefs, cooldown)
}

/** A session on [fake] with the test clock, started (signed in when the fake is). */
suspend fun TestScope.startedSession(
    fake: FakeApi = demoFake(),
    prefs: FeaturePrefs = InMemoryFeaturePrefs()
): AppSession {
    val session = AppSession(
        com.mekari.project.core.InMemoryServerSettings(AppSession.DEMO_SERVER),
        com.mekari.project.core.InMemoryTokenStore(),
        { _, _ -> fake },
        prefs,
        backgroundScope,
        TestTime.clock
    )
    session.start()
    return session
}

fun WorkspaceScope.title(id: String) = tasks[id]?.title

fun assertTitles(expected: List<String>, scope: WorkspaceScope, ids: List<String>) = assertEquals(
    expected,
    ids.map {
        scope.title(it)
    }
)
