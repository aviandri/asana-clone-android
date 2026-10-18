package com.mekari.project

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR 5 flows: projects (List/Board, move, inline add), inbox, search, quick add, deep links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MekariApplication::class)
class AppCompleteFlowTest : AppFlowTestBase() {
    private val fixLogin = DemoIds.TASK_WITH_STORIES

    private fun sectionOf(fake: FakeApi, title: String): String =
        fake.currentTasks().first { it.title == title }.sectionId!!

    private fun openBoardProject() {
        waitForTag(TestTags.NAV_PROJECTS)
        compose.onNodeWithTag(TestTags.NAV_PROJECTS).performClick()
        waitForTag(TestTags.projectRow(DemoIds.BOARD_PROJECT))
        compose.onNodeWithTag(TestTags.projectRow(DemoIds.BOARD_PROJECT)).performClick()
        waitForTag(TestTags.PROJECT)
    }

    @Test
    fun projectListMovesATaskWithMoveToSection() {
        val fake = FakeApi()
        launch(fake)
        openBoardProject()
        waitForTag(TestTags.VIEW_LIST)
        compose.onNodeWithTag(TestTags.VIEW_LIST).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.PROJECT_LIST) // Board cards share the row tag: wait for the List itself
        val toDo = sectionOf(fake, "Tablet layout")
        scrollToTag(TestTags.PROJECT_LIST, TestTags.taskMenu(fixLogin))
        compose.onNodeWithTag(TestTags.taskMenu(fixLogin)).performClick()
        compose.onNodeWithTag(TestTags.MOVE_TO_SECTION).performClick()
        compose.onNodeWithTag(TestTags.moveTarget(toDo)).performClick()
        waitFor("the move to reach the server") { sectionOf(fake, "Fix login redirect") == toDo }
    }

    @Test
    fun boardViewShowsTheSectionsAsColumns() {
        val fake = FakeApi()
        launch(fake)
        openBoardProject()
        waitForTag(TestTags.VIEW_BOARD)
        compose.onNodeWithTag(TestTags.VIEW_BOARD).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.boardColumn(sectionOf(fake, "Tablet layout")))
        waitForTag(TestTags.taskRow(fixLogin))
    }

    /** The Mac lesson in a project list: a section's add field keeps its text while rows change. */
    @Test
    fun typingInASectionAddFieldWhileRowsChange() {
        val fake = FakeApi()
        launch(fake)
        openBoardProject()
        waitForTag(TestTags.VIEW_LIST)
        compose.onNodeWithTag(TestTags.VIEW_LIST).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.PROJECT_LIST)
        val inProgress = sectionOf(fake, "Fix login redirect")
        val fieldTag = TestTags.sectionAddField(inProgress)
        scrollToTag(TestTags.PROJECT_LIST, fieldTag)
        val field = compose.onNodeWithTag(fieldTag)
        field.performClick()
        field.performTextInput("Write")
        scrollToTag(TestTags.PROJECT_LIST, TestTags.taskCheckbox(fixLogin))
        compose.onNodeWithTag(TestTags.taskCheckbox(fixLogin)).performClick()
        advance(600) // the completed row lingers, then collapses out
        scrollToTag(TestTags.PROJECT_LIST, fieldTag)
        field.performTextInput(" the notes")
        assertEquals("Write the notes", field.text())
        field.performImeAction()
        waitFor("the new task in that section") {
            fake.currentTasks().any { it.title == "Write the notes" && it.sectionId == inProgress }
        }
    }

    @Test
    fun inboxOpensATaskAndMarksAllRead() {
        launch()
        waitForTag(TestTags.NAV_INBOX)
        compose.onNodeWithTag(TestTags.NAV_INBOX).performClick()
        waitForTag(TestTags.INBOX)
        waitForText("unread")
        compose.onNodeWithTag(TestTags.INBOX_MARK_ALL).performClick()
        waitForText("All caught up")
        compose.onNodeWithTag(TestTags.INBOX_MARK_ALL).assertIsNotEnabled()
    }

    @Test
    fun searchFindsATaskAndOpensIt() {
        launch()
        waitForTag(TestTags.NAV_SEARCH)
        compose.onNodeWithTag(TestTags.NAV_SEARCH).performClick()
        compose.onNodeWithTag(TestTags.SEARCH_FIELD).performTextInput("login")
        advance(400) // the 250 ms debounce
        waitForTag(TestTags.searchTask(fixLogin))
        compose.onNodeWithTag(TestTags.searchTask(fixLogin)).performClick()
        waitForTag(TestTags.TASK_DETAIL)
    }

    @Test
    fun quickAddFromTheFab() {
        val fake = FakeApi()
        launch(fake)
        waitForTag(TestTags.QUICK_ADD_FAB)
        compose.onNodeWithTag(TestTags.QUICK_ADD_FAB).performClick()
        waitForTag(TestTags.QUICK_ADD_FIELD)
        compose.onNodeWithTag(TestTags.QUICK_ADD_FIELD).performTextInput("Call the bank")
        // The sheet may still be sliding in: invoke the button's action rather than a positioned tap.
        compose.onNodeWithTag(TestTags.QUICK_ADD_SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the quick-added task") {
            fake.currentTasks().any { it.title == "Call the bank" && it.projectId == null }
        }
        waitFor("the sheet to close") { isGone(TestTags.QUICK_ADD_SHEET) }
    }

    @Test
    fun theLauncherShortcutOpensQuickAdd() {
        launch(intent = Intent(MainActivity.ACTION_QUICK_ADD))
        waitForTag(TestTags.QUICK_ADD_FIELD)
    }

    @Test
    fun aDeepLinkOpensTheTask() {
        launch(intent = Intent(Intent.ACTION_VIEW, Uri.parse("mekariproject://task/$fixLogin")))
        waitForTag(TestTags.TASK_DETAIL)
        waitFor("the task title") { compose.onNodeWithTag(TestTags.TASK_TITLE).text().isNotEmpty() }
    }
}
