package com.mekari.project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.mekari.project.core.Workspace
import com.mekari.project.core.WorkspaceRole
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeSeed
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Galaxy Z Fold5 device-QA findings (dark mode, a phone-width screen), checked on Robolectric. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class DeviceQaFixesTest : AppFlowTestBase() {
    private companion object {
        const val BURST = "celebration.burst"
    }

    /** A1: the workspace sheet opens fully, and every workspace can be reached. */
    @Test
    fun theWorkspaceSheetOpensFullyAndScrollsToEveryWorkspace() {
        val seed = FakeSeed.demo()
        val extra = (1..12).map { n ->
            val id = "1000000a-0000-4000-8000-%012d".format(n)
            Workspace(id, "Workspace $n", false, null, "#4186e0", WorkspaceRole.MEMBER, 0, Instant.EPOCH)
        }
        launch(
            fake = FakeApi(
                seed.copy(
                    workspaces = seed.workspaces + extra,
                    memberships = seed.memberships + extra.map {
                        FakeSeed.Membership(it.id, DemoIds.ALICE, WorkspaceRole.MEMBER)
                    }
                )
            )
        )
        waitForTag(TestTags.MY_TASKS)
        compose.onNodeWithTag(TestTags.WORKSPACE_SWITCHER).performClick()
        waitForTag(TestTags.WORKSPACE_LIST)
        advance(1_000)
        // Half open, the sheet's top sat mid-screen with only its first row showing.
        val screen = compose.onAllNodes(isRoot())[0].getBoundsInRoot()
        val list = compose.onNodeWithTag(TestTags.WORKSPACE_LIST).getBoundsInRoot()
        assertTrue("sheet list starts at ${list.top} on a ${screen.bottom} screen", list.top < screen.bottom * 0.3f)
        assertTrue("sheet list ends at ${list.bottom}", list.bottom >= screen.bottom - 1.dp)
        val last = TestTags.workspaceOption(extra.last().id)
        compose.onNodeWithTag(TestTags.WORKSPACE_LIST).performScrollToNode(hasTestTag(last))
        compose.onNodeWithTag(last).assertIsDisplayed()
    }

    /**
     * A2: the stray dot was a petal burst frozen at its start, every petal stacked on the tapped checkbox
     * (its effect key was nulled right after it began, cancelling the animation). Completing the last task
     * due today celebrates; the burst must start, finish, and leave no accent speck behind.
     */
    @Test
    fun theCelebrationRunsToTheEndAndLeavesNoSpeck() {
        val seed = FakeSeed.demo()
        val last = seed.tasks.first {
            it.assignee?.id == DemoIds.ALICE && !it.completed && it.projectId == DemoIds.BOARD_PROJECT
        }.copy(dueDate = LocalDate.now())
        launch(fake = FakeApi(seed.copy(tasks = seed.tasks.filter { it.assignee?.id != DemoIds.ALICE } + last)))
        waitForTag(TestTags.taskCheckbox(last.id))
        assertTrue("the accent check sees the open task's header", accentPixels().isNotEmpty())
        // Drive the frame clock by hand: with auto-advance, one idle would run the whole burst unseen.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(TestTags.taskCheckbox(last.id)).performClick()
        var startedAt: Long? = null
        for (step in 1..200) {
            stepFrames(10)
            if (!isGone(BURST)) {
                startedAt = step * 10L
                break
            }
        }
        assertTrue("the completion didn't celebrate", startedAt != null)
        // Midway it's still playing; well after its 1.2 s it's gone (the frozen burst stayed for good).
        stepFrames(600)
        assertTrue("the burst ended early", !isGone(BURST))
        stepFrames(1_400)
        assertTrue("the burst is still on screen 2 s after it started", isGone(BURST))
        compose.mainClock.autoAdvance = true
        waitForText("Nothing on your plate")
        advance(1_000)
        assertTrue(isGone(TestTags.taskRow(last.id)))
        val specks = accentPixels()
        assertTrue("accent pixels in the empty list (dp): ${specks.take(10)}", specks.isEmpty())
    }

    /** Moves the compose clock and the main looper (the app's coroutines) forward together. */
    private fun stepFrames(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
    }

    /** Accent-coloured pixels (dp) in the list area: below the top bar, above the FAB and the nav bar. */
    private fun accentPixels(): List<Pair<Int, Int>> {
        lateinit var bitmap: Bitmap
        scenario!!.onActivity {
            val decor = it.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
        }
        val density = bitmap.density / 160f
        val hits = mutableSetOf<Pair<Int, Int>>()
        for (y in (64 * density).toInt() until bitmap.height - (220 * density).toInt()) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                // Both primaries (#E0257F light, #FF5FA2 dark) and their anti-aliased edges.
                if (r > 150 && b > 60 && r - g > 80) hits += (x / density).toInt() to (y / density).toInt()
            }
        }
        return hits.toList()
    }
}
