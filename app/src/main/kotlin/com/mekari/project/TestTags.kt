package com.mekari.project

/** Compose test tags used by UI tests (SPEC-ANDROID §5); exposed as resource ids too. */
object TestTags {
    const val PLACEHOLDER = "app.placeholder"

    // Frame
    const val NAV_MY_TASKS = "nav.myTasks"
    const val NAV_PROJECTS = "nav.projects"
    const val NAV_INBOX = "nav.inbox"
    const val NAV_SEARCH = "nav.search"
    const val NAV_RAIL = "nav.rail"
    const val INBOX_BADGE = "nav.inbox.badge"
    const val WORKSPACE_SWITCHER = "topbar.workspaceSwitcher"
    const val SETTINGS_BUTTON = "topbar.settings"

    // Shared rows and fields
    const val ADD_TASK_FIELD = "addTask.field"
    fun taskRow(id: String) = "task.row.$id"
    fun taskCheckbox(id: String) = "task.checkbox.$id"
}
