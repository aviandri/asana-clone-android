package com.mekari.project

import com.mekari.project.core.InboxNotification
import com.mekari.project.core.InboxPage
import com.mekari.project.core.NotificationType
import com.mekari.project.core.UserSummary
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.ui.components.DropRules
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {
    @Test
    fun dropsOnlyMoveThisProjectsTasksForEditors() {
        val tasks = FakeApi().currentTasks()
        val task = tasks.first { it.projectId != null }
        val project = task.projectId!!
        val other = tasks.first { it.projectId != null && it.projectId != project }
        assertTrue(DropRules.accepts(task, project, canEdit = true, before = null))
        assertFalse("read-only", DropRules.accepts(task, project, canEdit = false, before = null))
        assertFalse("another project's task", DropRules.accepts(other, project, canEdit = true, before = null))
        assertFalse(
            "unknown id (text from another app)",
            DropRules.accepts(null, project, canEdit = true, before = null)
        )
        assertFalse("onto itself", DropRules.accepts(task, project, canEdit = true, before = task.id))
    }

    @Test
    fun theBackgroundCheckReportsOnlyNewUnreadItems() {
        val actor = UserSummary("u1", "Alice", "alice@example.com", "#123456")
        fun item(id: String, read: Boolean) =
            InboxNotification(id, "w", NotificationType.ASSIGNED, read, Instant.now(), actor)
        val page = InboxPage(listOf(item("a", false), item("b", false), item("c", true)), unreadCount = 2)
        val (first, seen) = diffInbox(null, page)
        assertTrue("no baseline yet: nothing on the first check", first.isEmpty())
        assertEquals(setOf("a", "b"), seen)
        val next = InboxPage(listOf(item("a", false), item("b", false), item("d", false)), unreadCount = 3)
        val (fresh, _) = diffInbox(seen, next)
        assertEquals(listOf("d"), fresh.map { it.id })
    }
}
