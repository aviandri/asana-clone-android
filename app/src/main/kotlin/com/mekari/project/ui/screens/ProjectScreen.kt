package com.mekari.project.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.core.Section
import com.mekari.project.core.TaskItem
import com.mekari.project.core.WebLinks
import com.mekari.project.feature.AppSession
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.ProjectStore
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.Browser
import com.mekari.project.ui.Formatting
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.components.Avatar
import com.mekari.project.ui.components.CompletionCheckbox
import com.mekari.project.ui.components.DropRules
import com.mekari.project.ui.components.InlineAddField
import com.mekari.project.ui.components.taskDragSource
import com.mekari.project.ui.components.taskDropTarget
import com.mekari.project.ui.launchAction
import com.mekari.project.ui.runAction
import java.time.LocalDate

/**
 * One project: List or Board (starting on its default view; CALENDAR → List), long-press drag to move,
 * "Move to section" in each row's overflow, inline add per section (SPEC-ANDROID §5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    session: AppSession,
    scope: WorkspaceScope,
    projectId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    val store = remember(projectId) { scope.project(projectId) }
    val state by store.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    var menu by remember { mutableStateOf(false) }
    var addingSection by remember { mutableStateOf(false) }
    // One add field per section, owned here (outside the lazy items): recycling never resets them.
    val addFields = remember { mutableMapOf<String?, TextFieldState>() }
    val fieldFor: (String?) -> TextFieldState = { section -> addFields.getOrPut(section) { TextFieldState() } }
    LaunchedEffect(store) { if (state.phase == LoadState.Idle) store.load() }
    ErrorSnackbar(state.lastError) { store.clearError() }
    val server = session.settings.serverUrl

    Scaffold(
        modifier = Modifier.testTag(TestTags.PROJECT),
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.project?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.canEdit) {
                        IconButton(onClick = {
                            addingSection = true
                        }, modifier = Modifier.testTag(TestTags.ADD_SECTION)) {
                            Icon(Icons.Filled.Add, contentDescription = "Add section")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TestTags.PROJECT_MENU)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Show completed") },
                                leadingIcon = { Checkbox(checked = state.showCompleted, onCheckedChange = null) },
                                onClick = {
                                    menu = false
                                    store.setShowCompleted(!state.showCompleted)
                                }
                            )
                            if (server != null) {
                                DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                                    menu = false
                                    Browser.open(context, WebLinks.project(projectId, server, scope.id))
                                })
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Only once loaded: the first load picks the project's default view, which would undo an earlier tap.
            if (state.detail !=
                null
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ProjectStore.ViewMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.viewMode == mode,
                            onClick = { store.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ProjectStore.ViewMode.entries.size),
                            modifier = Modifier.testTag(
                                if (mode ==
                                    ProjectStore.ViewMode.LIST
                                ) {
                                    TestTags.VIEW_LIST
                                } else {
                                    TestTags.VIEW_BOARD
                                }
                            )
                        ) { Text(if (mode == ProjectStore.ViewMode.LIST) "List" else "Board") }
                    }
                }
            }
            if (state.detail == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val failed = state.phase as? LoadState.Failed
                    if (failed != null) Text(failed.error.message) else CircularProgressIndicator()
                }
            } else {
                val rowMenu: @Composable (TaskItem) -> Unit = { task ->
                    TaskOverflow(task, state.sections, state.canEdit, server, scope.id) { section ->
                        appScope.launchAction { store.move(task.id, section, before = null) }
                    }
                }
                when (state.viewMode) {
                    ProjectStore.ViewMode.LIST -> ProjectList(scope, store, state, fieldFor, onOpenTask, rowMenu)
                    ProjectStore.ViewMode.BOARD -> ProjectBoard(scope, store, state, fieldFor, onOpenTask)
                }
            }
        }
    }

    if (addingSection) {
        NameDialog(title = "New section", initial = "", confirm = "Add", onDismiss = {
            addingSection = false
        }) { name ->
            addingSection = false
            appScope.launchAction { store.addSection(name) }
        }
    }
}

/** The sections a project shows: "No section" only when it has tasks, then the sections by position. */
private fun groups(state: ProjectStore.State): List<Pair<String?, String>> =
    (if (state.hasUnsectionedTasks) listOf<Pair<String?, String>>(null to "No section") else emptyList()) +
        state.sections.map { it.id to it.name }

/** A drop handler: DropRules first, then the anchored move ("before" that task, or the section's end). */
@Composable
private fun rememberDrop(
    scope: WorkspaceScope,
    store: ProjectStore,
    canEdit: Boolean
): (String, String?, String?) -> Boolean {
    val appScope = LocalAppScope.current
    return remember(store, canEdit) {
        { taskId, section, before ->
            val ok = DropRules.accepts(scope.tasks[taskId], store.projectId, canEdit, before)
            if (ok) appScope.launchAction { store.move(taskId, section, before) }
            ok
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectList(
    scope: WorkspaceScope,
    store: ProjectStore,
    state: ProjectStore.State,
    fieldFor: (String?) -> TextFieldState,
    onOpenTask: (String) -> Unit,
    rowMenu: @Composable (TaskItem) -> Unit
) {
    val tasks by scope.tasks.tasks.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    val drop = rememberDrop(scope, store, state.canEdit)
    val today = LocalDate.now()
    val colors = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf<Section?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag(TestTags.PROJECT_LIST)) {
        groups(state).forEach { (sectionId, title) ->
            val ids = state.taskIds(sectionId)
            stickyHeader(key = "header.${sectionId ?: "none"}", contentType = "header") {
                DropZone(onDrop = { drop(it, sectionId, ids.firstOrNull()) }) { highlight ->
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (highlight) colors.primaryContainer else colors.surface
                            )
                            .clickable(enabled = state.canEdit && sectionId != null) {
                                renaming = state.sections.firstOrNull { it.id == sectionId }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag(TestTags.sectionHeader(sectionId ?: "none"))
                    )
                }
            }
            items(ids, key = { it }, contentType = { "task" }) { id ->
                val task = tasks[id] ?: return@items
                DropZone(onDrop = { drop(it, sectionId, id) }, modifier = Modifier.animateItem()) { highlight ->
                    Column {
                        if (highlight) HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                        TaskRow(
                            task = task,
                            canEdit = state.canEdit,
                            today = today,
                            showProject = false,
                            onToggle = { appScope.launchAction { store.toggleComplete(id) } },
                            onOpen = { onOpenTask(id) },
                            modifier = Modifier.taskDragSource(id, state.canEdit),
                            trailing = { rowMenu(task) }
                        )
                    }
                }
            }
            if (state.canEdit) {
                item(key = "add.${sectionId ?: "none"}", contentType = "add") {
                    DropZone(onDrop = { drop(it, sectionId, null) }) { highlight ->
                        Column {
                            if (highlight) {
                                HorizontalDivider(
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            InlineAddField(
                                state = fieldFor(sectionId),
                                placeholder = "Add task",
                                onSubmit = { title -> appScope.runAction { store.addTask(title, sectionId) } },
                                testTag = TestTags.sectionAddField(sectionId ?: "none")
                            )
                        }
                    }
                }
            }
        }
    }
    renaming?.let { section ->
        NameDialog(title = "Rename section", initial = section.name, confirm = "Rename", onDismiss = {
            renaming = null
        }) { name ->
            renaming = null
            appScope.launchAction { store.renameSection(section.id, name) }
        }
    }
}

@Composable
private fun ProjectBoard(
    scope: WorkspaceScope,
    store: ProjectStore,
    state: ProjectStore.State,
    fieldFor: (String?) -> TextFieldState,
    onOpenTask: (String) -> Unit
) {
    val tasks by scope.tasks.tasks.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    val drop = rememberDrop(scope, store, state.canEdit)
    val today = LocalDate.now()
    LazyRow(
        Modifier.fillMaxSize().testTag(TestTags.PROJECT_BOARD),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(groups(state), key = { it.first ?: "none" }) { (sectionId, title) ->
            val ids = state.taskIds(sectionId)
            // Anywhere in the column that isn't a card: drop at the end.
            DropZone(onDrop = {
                drop(it, sectionId, null)
            }, modifier = Modifier.width(288.dp).fillMaxHeight()) { highlight ->
                val border by animateColorAsState(
                    if (highlight) MaterialTheme.colorScheme.primary else Color.Transparent,
                    label = "column"
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .border(2.dp, border, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                        .testTag(TestTags.boardColumn(sectionId ?: "none"))
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${ids.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ids, key = { it }) { id ->
                            val task = tasks[id] ?: return@items
                            DropZone(onDrop = {
                                drop(it, sectionId, id)
                            }, modifier = Modifier.animateItem()) { cardHighlight ->
                                BoardCard(
                                    task = task,
                                    canEdit = state.canEdit,
                                    today = today,
                                    highlight = cardHighlight,
                                    onToggle = { appScope.launchAction { store.toggleComplete(id) } },
                                    onOpen = { onOpenTask(id) }
                                )
                            }
                        }
                        if (state.canEdit) {
                            item(key = "add.${sectionId ?: "none"}") {
                                InlineAddField(
                                    state = fieldFor(sectionId),
                                    placeholder = "Add task",
                                    onSubmit = { t -> appScope.runAction { store.addTask(t, sectionId) } },
                                    testTag = TestTags.sectionAddField(sectionId ?: "none"),
                                    modifier = Modifier.padding(horizontal = 0.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(
    task: TaskItem,
    canEdit: Boolean,
    today: LocalDate,
    highlight: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        border = if (highlight) {
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            )
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .taskDragSource(task.id, canEdit)
            .testTag(TestTags.taskRow(task.id))
    ) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CompletionCheckbox(
                    task.completed,
                    task.title,
                    canEdit,
                    onToggle = onToggle,
                    testTag = TestTags.taskCheckbox(task.id)
                )
                // Card titles wrap: the strike and dim crossfade instead of the rows' sweep.
                val color by animateColorAsState(
                    if (task.completed) colors.onSurfaceVariant else colors.onSurface,
                    label = "cardTitle"
                )
                Text(
                    task.title,
                    color = color,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    modifier = Modifier.padding(top = 13.dp).weight(1f)
                )
            }
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                task.dueDate?.let { due ->
                    val overdue = !task.completed && Formatting.isOverdue(due, today)
                    Text(
                        Formatting.due(due, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) colors.error else colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                task.assignee?.let { Avatar(it.name, it.avatarColor, size = 22.dp) }
            }
        }
    }
}

/** A drop zone that reports hover for its highlight. */
@Composable
private fun DropZone(
    onDrop: (String) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit
) {
    var hover by remember { mutableStateOf(false) }
    Box(modifier.taskDropTarget(onHover = { hover = it }, onTask = onDrop)) { content(hover) }
}

/** A row's overflow: Move to section (the accessible way to move; drag is long-press) and Open in browser. */
@Composable
private fun TaskOverflow(
    task: TaskItem,
    sections: List<Section>,
    canEdit: Boolean,
    server: String?,
    workspaceId: String,
    onMove: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TestTags.taskMenu(task.id))) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${task.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canEdit && sections.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Move to section") }, onClick = {
                    open = false
                    moving = true
                }, modifier = Modifier.testTag(TestTags.MOVE_TO_SECTION))
            }
            if (server != null) {
                DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                    open = false
                    Browser.open(context, WebLinks.task(task, server, workspaceId))
                })
            }
        }
        DropdownMenu(expanded = moving, onDismissRequest = { moving = false }) {
            sections.forEach { section ->
                DropdownMenuItem(
                    text = { Text(section.name) },
                    enabled = section.id != task.sectionId,
                    onClick = {
                        moving = false
                        onMove(section.id)
                    },
                    modifier = Modifier.testTag(TestTags.moveTarget(section.id))
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Section name") },
                modifier = Modifier.heightIn(min = 56.dp).testTag(TestTags.SECTION_NAME_FIELD)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
