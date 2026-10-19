package com.mekari.project.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.core.TaskItem
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.Formatting
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.components.Avatar
import com.mekari.project.ui.components.CompletionCheckbox
import com.mekari.project.ui.components.CompletionTitle
import com.mekari.project.ui.components.InlineAddField
import com.mekari.project.ui.components.parseHexColor
import com.mekari.project.ui.components.rememberAddFieldState
import com.mekari.project.ui.components.taskRowAnimation
import com.mekari.project.ui.launchAction
import com.mekari.project.ui.runAction
import java.time.LocalDate

/** Overdue / Today / Upcoming / Later / No date (SPEC-ANDROID §1), with inline add and Show completed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MyTasksScreen(
    scope: WorkspaceScope,
    onOpenTask: (String) -> Unit,
    selectedTaskId: String?,
    modifier: Modifier = Modifier
) {
    val state by scope.myTasks.state.collectAsStateWithLifecycle()
    val tasks by scope.tasks.tasks.collectAsStateWithLifecycle()
    // canEdit reads the projects' roles: collect them so rows update when roles arrive.
    val projects by scope.projects.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    // The add field's text lives here, outside the lazy items, so list updates can't recreate or reset it.
    val addField = rememberAddFieldState()
    var refreshing by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    ErrorSnackbar(state.lastError) { scope.myTasks.clearError() }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            appScope.launchAction {
                scope.refresh()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize().testTag(TestTags.MY_TASKS)
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag(TestTags.MY_TASKS_LIST)) {
            item(key = "add.myTasks", contentType = "add") {
                InlineAddField(
                    state = addField,
                    placeholder = "Add task",
                    onSubmit = { title -> appScope.runAction { scope.myTasks.addTask(title) } }
                )
                HorizontalDivider()
            }
            item(key = "filters", contentType = "filters") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = state.showCompleted,
                        onClick = { appScope.launchAction { scope.myTasks.setShowCompleted(!state.showCompleted) } },
                        label = { Text("Show completed") },
                        modifier = Modifier.testTag(TestTags.MY_TASKS_SHOW_COMPLETED)
                    )
                }
            }
            if (state.groups.isEmpty()) {
                item(key = "empty", contentType = "empty") { EmptyOrLoading(state.phase, scope) }
            }
            state.groups.forEach { group ->
                stickyHeader(key = "header.${group.bucket.name}", contentType = "header") {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(group.taskIds, key = { it }, contentType = { "task" }) { id ->
                    val task = tasks[id] ?: return@items
                    TaskRow(
                        task = task,
                        canEdit = remember(task, projects) { scope.canEdit(task) },
                        today = today,
                        showProject = true,
                        selected = id == selectedTaskId,
                        onToggle = { appScope.launchAction { scope.myTasks.toggleComplete(id) } },
                        onOpen = { onOpenTask(id) },
                        // Only the rows animate (insert, the collapse after the linger), never the add field.
                        modifier = taskRowAnimation()
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyOrLoading(phase: LoadState, scope: WorkspaceScope) {
    val appScope = LocalAppScope.current
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            is LoadState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load My Tasks", style = MaterialTheme.typography.titleMedium)
                Text(phase.error.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { appScope.launchAction { scope.myTasks.refresh() } }) { Text("Try again") }
            }
            LoadState.Loaded -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing on your plate", style = MaterialTheme.typography.titleMedium)
                Text("Tasks assigned to you show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> CircularProgressIndicator()
        }
    }
}

/**
 * One task: checkbox, title (strike sweep), and meta (project, due date, assignee). Tapping the row opens
 * it; the checkbox's 48 dp box is its own target, and a read-only box swallows the tap.
 */
@Composable
fun TaskRow(
    task: TaskItem,
    canEdit: Boolean,
    today: LocalDate,
    showProject: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** An overflow menu (project rows: Move to section, Open in browser). */
    trailing: (@Composable () -> Unit)? = null
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(background)
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 16.dp)
            .testTag(TestTags.taskRow(task.id)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompletionCheckbox(
            checked = task.completed,
            title = task.title,
            enabled = canEdit,
            onToggle = onToggle,
            testTag = TestTags.taskCheckbox(task.id)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            CompletionTitle(task.title, task.completed)
            Meta(task, today, showProject)
        }
        task.assignee?.let { assignee ->
            Spacer(Modifier.width(8.dp))
            Avatar(assignee.name, assignee.avatarColor, size = 24.dp)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Meta(task: TaskItem, today: LocalDate, showProject: Boolean) {
    val project = task.project.takeIf { showProject }
    val due = task.dueDate
    if (project == null && due == null && task.subtaskCount == 0 && task.commentCount == 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val metaStyle = MaterialTheme.typography.bodySmall
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        if (due != null) {
            val overdue = !task.completed && Formatting.isOverdue(due, today)
            Text(
                Formatting.due(due, today),
                style = metaStyle,
                color = if (overdue) MaterialTheme.colorScheme.error else secondary
            )
        }
        if (project != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(parseHexColor(project.color), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(project.name, style = metaStyle, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (task.subtaskCount > 0) {
            Text("${task.completedSubtaskCount}/${task.subtaskCount} subtasks", style = metaStyle, color = secondary)
        }
        if (task.commentCount > 0) {
            Text("${task.commentCount} comments", style = metaStyle, color = secondary)
        }
    }
}

/** Shows a holder's transient error once, then clears it. */
@Composable
fun ErrorSnackbar(error: com.mekari.project.core.ApiError?, clear: () -> Unit) {
    val host = LocalSnackbarHost.current
    LaunchedEffect(error) {
        val shown = error ?: return@LaunchedEffect
        host?.showSnackbar(shown.message)
        clear()
    }
}
