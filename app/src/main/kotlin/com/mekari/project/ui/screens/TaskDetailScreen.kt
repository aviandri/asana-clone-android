package com.mekari.project.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.core.Activity
import com.mekari.project.core.ActivityType
import com.mekari.project.core.Comment
import com.mekari.project.core.Priority
import com.mekari.project.core.Story
import com.mekari.project.core.TaskItem
import com.mekari.project.core.UserSummary
import com.mekari.project.feature.CommentComposer
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.TaskDetailStore
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.Formatting
import com.mekari.project.ui.InlineMarkdown
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.RelativeTime
import com.mekari.project.ui.components.Avatar
import com.mekari.project.ui.components.CompletionCheckbox
import com.mekari.project.ui.components.CompletionEffects
import com.mekari.project.ui.components.CompletionMark
import com.mekari.project.ui.components.CompletionTitle
import com.mekari.project.ui.components.GuestChip
import com.mekari.project.ui.components.InlineAddField
import com.mekari.project.ui.components.parseHexColor
import com.mekari.project.ui.components.rememberAddFieldState
import com.mekari.project.ui.components.taskRowAnimation
import com.mekari.project.ui.launchAction
import com.mekari.project.ui.runAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The task (SPEC-ANDROID §5): every field edits in place and saves on done or focus loss, never per
 * keystroke. The cached row shows at once; subtasks, followers and stories load in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    scope: WorkspaceScope,
    taskId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    inPane: Boolean = false
) {
    val store = remember(taskId) { scope.taskDetail(taskId) }
    val state by store.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    LaunchedEffect(store) { store.load() }
    ErrorSnackbar(state.lastError) { store.clearError() }

    Scaffold(
        modifier = Modifier.testTag(TestTags.TASK_DETAIL),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (inPane) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.task != null) Composer(store.composer)
        }
    ) { padding ->
        val task = state.task
        if (task == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val failed = state.phase as? LoadState.Failed
                Text(
                    failed?.let {
                        if (it.error.status ==
                            404
                        ) {
                            "You don't have access to this task"
                        } else {
                            it.error.message
                        }
                    }
                        ?: "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        val canEdit = state.canEdit
        val subtaskField = rememberAddFieldState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
        ) {
            item(key = "complete") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompleteButton(task, canEdit) { appScope.launchAction { store.toggleComplete() } }
                    Spacer(Modifier.weight(1f))
                    if (!canEdit) ReadOnlyLabel()
                }
            }
            item(key = "title") {
                EditableTitle(task.title, canEdit) { title -> appScope.launchAction { store.setTitle(title) } }
            }
            item(key = "fields") { Fields(scope, store, task, canEdit) }
            item(key = "description") {
                DescriptionField(task.description.orEmpty(), canEdit) { text ->
                    appScope.launchAction { store.setDescription(text.ifBlank { null }) }
                }
            }
            val detail = state.detail
            if (detail == null) {
                item(key = "loading") {
                    Text(
                        "Loading details…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag(TestTags.TASK_LOADING_DETAILS)
                    )
                }
            } else {
                item(key = "subtasks.header") { SectionHeader("Subtasks") }
                items(detail.subtasks, key = { "sub.${it.id}" }) { subtask ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onOpenTask(subtask.id)
                        }.testTag(TestTags.subtaskRow(subtask.id))
                            .then(taskRowAnimation()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(4.dp))
                        CompletionCheckbox(subtask.completed, subtask.title, canEdit, onToggle = {
                            appScope.launchAction { store.toggleSubtask(subtask.id) }
                        })
                        CompletionTitle(subtask.title, subtask.completed, Modifier.weight(1f))
                    }
                }
                if (canEdit) {
                    item(key = "subtasks.add") {
                        InlineAddField(
                            state = subtaskField,
                            placeholder = "Add subtask",
                            onSubmit = { title -> appScope.runAction { store.addSubtask(title) } },
                            testTag = TestTags.SUBTASK_ADD_FIELD
                        )
                    }
                }
                item(key = "followers") { Followers(detail.followers, state.isFollowing, store) }
            }
            item(key = "stories.header") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionHeader("Comments and activity")
            }
            items(state.stories, key = { it.key }) { story -> StoryRow(story, scope.me, store) }
        }
    }
}

@Composable
private fun CompleteButton(task: TaskItem, canEdit: Boolean, onToggle: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    FilledTonalButton(
        onClick = {
            CompletionEffects.play(view, completing = !task.completed, title = task.title, sound = false)
            onToggle()
        },
        enabled = canEdit,
        modifier = Modifier.testTag(TestTags.TASK_COMPLETE)
    ) {
        CompletionMark(task.completed, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(if (task.completed) "Completed" else "Mark complete")
    }
}

@Composable
private fun ReadOnlyLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Read only",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/**
 * The title, edited in place. The field owns its text: the server's title replaces it only while the
 * field isn't focused. Done or focus loss commits; an empty title reverts.
 */
@Composable
private fun EditableTitle(title: String, canEdit: Boolean, onCommit: (String) -> Unit) {
    val field = rememberTextFieldState(title)
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestTitle by rememberUpdatedState(title)
    LaunchedEffect(title) { if (!focused && field.text.toString() != title) field.setTextAndPlaceCursorAtEnd(title) }
    fun commit() {
        val edited = field.text.toString().trim()
        when {
            edited.isEmpty() -> field.setTextAndPlaceCursorAtEnd(latestTitle)
            edited != latestTitle -> onCommit(edited)
        }
    }
    // Leaving the screen doesn't move focus first.
    DisposableEffect(Unit) { onDispose { if (focused) commit() } }
    BasicTextField(
        state = field,
        readOnly = !canEdit,
        textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { focusManager.clearFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(TestTags.TASK_TITLE)
            .semantics { contentDescription = "Task name" }
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Fields(scope: WorkspaceScope, store: TaskDetailStore, task: TaskItem, canEdit: Boolean) {
    val appScope = LocalAppScope.current
    var pickingAssignee by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingPriority by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Column {
        FieldRow("Assignee", TestTags.TASK_ASSIGNEE, canEdit, onClick = { pickingAssignee = true }) {
            val assignee = task.assignee
            if (assignee != null) {
                Avatar(assignee.name, assignee.avatarColor, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(assignee.name)
            } else {
                Text("Unassigned", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FieldRow("Due date", TestTags.TASK_DUE, canEdit, onClick = { pickingDate = true }) {
            val due = task.dueDate
            if (due != null) {
                val overdue = !task.completed && Formatting.isOverdue(due, today)
                Text(
                    Formatting.due(due, today),
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text("No due date", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            FieldRow("Priority", TestTags.TASK_PRIORITY, canEdit, onClick = { pickingPriority = true }) {
                Text(priorityLabel(task.priority))
            }
            DropdownMenu(expanded = pickingPriority, onDismissRequest = { pickingPriority = false }) {
                Priority.known.forEach { priority ->
                    DropdownMenuItem(text = { Text(priorityLabel(priority)) }, onClick = {
                        pickingPriority = false
                        appScope.launchAction { store.setPriority(priority) }
                    })
                }
            }
        }
        task.project?.let { project ->
            FieldRow("Project", null, enabled = false, onClick = {}) {
                Box(Modifier.size(10.dp).background(parseHexColor(project.color), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(project.name)
            }
        }
    }
    if (pickingAssignee) {
        UserPickerDialog(
            scope = scope,
            title = "Assignee",
            allowNone = true,
            onPick = { user ->
                pickingAssignee = false
                appScope.launchAction { store.setAssignee(user) }
            },
            onDismiss = { pickingAssignee = false }
        )
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = task.dueDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    // The picker's millis are UTC midnight: a calendar date, never shifted by the zone.
                    val date = picker.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    appScope.launchAction { store.setDueDate(date) }
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickingDate = false
                    appScope.launchAction { store.setDueDate(null) }
                }) { Text("Clear") }
            }
        ) { DatePicker(state = picker) }
    }
}

fun priorityLabel(priority: Priority): String = when (priority) {
    Priority.HIGH -> "High"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
    Priority.NONE -> "None"
    else -> priority.raw.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun FieldRow(
    label: String,
    tag: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    value: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { value() }
    }
}

/** The description, rendered as inline markdown; tap to edit (plain text), commit on focus loss. */
@Composable
private fun DescriptionField(text: String, canEdit: Boolean, onCommit: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Description", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(4.dp))
        if (editing) {
            val field = rememberTextFieldState(text)
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            DisposableEffect(Unit) { onDispose { if (field.text.toString() != text) onCommit(field.text.toString()) } }
            BasicTextField(
                state = field,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .focusRequester(focus)
                    .testTag(TestTags.TASK_DESCRIPTION)
                    .onFocusChanged {
                        if (hadFocus && !it.isFocused) editing = false // onDispose commits
                        hadFocus = it.isFocused
                    }
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = canEdit) { editing = true }
                    .testTag(TestTags.TASK_DESCRIPTION)
            ) {
                if (text.isBlank()) {
                    Text(
                        if (canEdit) "Add a description" else "No description",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(InlineMarkdown.render(text, accent), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Followers(followers: List<UserSummary>, isFollowing: Boolean, store: TaskDetailStore) {
    val appScope = LocalAppScope.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Followers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { appScope.launchAction { if (isFollowing) store.unfollow() else store.follow() } }) {
                Text(if (isFollowing) "Unfollow" else "Follow")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            followers.forEach { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.name, user.avatarColor, size = 24.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(user.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (followers.isEmpty()) Text("No followers", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StoryRow(story: Story, me: UserSummary, store: TaskDetailStore) {
    when (story) {
        is Comment -> CommentRow(story, mine = story.author.id == me.id, store)
        is Activity -> Text(
            "${story.actor.name} ${activityText(story)} · ${RelativeTime.format(story.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        else -> Unit
    }
}

private fun activityText(activity: Activity): String = when (activity.type) {
    ActivityType.CREATED -> "created this task"
    ActivityType.COMPLETED -> "completed this task"
    ActivityType.REOPENED -> "reopened this task"
    ActivityType.ASSIGNED -> "changed the assignee"
    ActivityType.UNASSIGNED -> "removed the assignee"
    ActivityType.DUE_DATE_CHANGED -> "changed the due date"
    ActivityType.MOVED_SECTION -> "moved this task"
    ActivityType.RENAMED -> "renamed this task"
    ActivityType.PRIORITY_CHANGED -> "changed the priority"
    ActivityType.DESCRIPTION_CHANGED -> "changed the description"
    else -> "updated this task"
}

@Composable
private fun CommentRow(comment: Comment, mine: Boolean, store: TaskDetailStore) {
    val appScope = LocalAppScope.current
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(comment.author.name, comment.author.avatarColor, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author.name, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    RelativeTime.format(comment.createdAt) + if (comment.editedAt != null) " · edited" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(InlineMarkdown.renderComment(comment.body, accent), style = MaterialTheme.typography.bodyMedium)
        }
        if (mine) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Comment options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        appScope.launchAction { store.deleteComment(comment.id) }
                    })
                }
            }
        }
    }
}

/** Shows "@[Name](id)" mention tokens as "@Name" while the text keeps the storage form. */
private object MentionDisplay : OutputTransformation {
    private val token = Regex("@\\[([^\\]]+)\\]\\([^)]+\\)")

    override fun TextFieldBuffer.transformOutput() {
        val matches = token.findAll(asCharSequence()).toList().asReversed()
        for (match in matches) replace(match.range.first, match.range.last + 1, "@" + match.groupValues[1])
    }
}

/** The comment box: the UI owns the text; the composer finds "@word" and sends. */
@Composable
private fun Composer(composer: CommentComposer) {
    val appScope = LocalAppScope.current
    val field = rememberTextFieldState()
    val state by composer.state.collectAsStateWithLifecycle()
    val users by composer.users.state.collectAsStateWithLifecycle()
    LaunchedEffect(composer) {
        snapshotFlow {
            field.text.toString() to field.selection.end
        }.collect { (text, cursor) -> composer.draftChanged(text, cursor) }
    }
    val text = field.text.toString()
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Column {
            if (state.mentionQuery != null && users.results.isNotEmpty()) {
                Column {
                    users.results.take(5).forEach { pickable ->
                        ListItem(
                            leadingContent = { Avatar(pickable.user.name, pickable.user.avatarColor, size = 28.dp) },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(pickable.user.name)
                                    if (pickable.isGuest) {
                                        Spacer(Modifier.width(6.dp))
                                        GuestChip()
                                    }
                                }
                            },
                            modifier = Modifier.testTag(TestTags.mentionOption(pickable.user.id)).clickable {
                                val (updated, cursor) = composer.insertMention(
                                    pickable,
                                    field.text.toString(),
                                    field.selection.end
                                )
                                field.edit {
                                    replace(0, length, updated)
                                    selection = TextRange(cursor)
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    CommentField(field)
                }
                IconButton(
                    onClick = {
                        val body = field.text.toString()
                        appScope.launchAction {
                            if (composer.send(body) && field.text.toString() == body) field.clearText()
                        }
                    },
                    enabled = composer.canSend(text) && !state.isSending,
                    modifier = Modifier.testTag(TestTags.COMMENT_SEND)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send comment")
                }
            }
        }
    }
}

@Composable
private fun CommentField(field: TextFieldState) {
    val style: TextStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        state = field,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
        outputTransformation = MentionDisplay,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.COMMENT_FIELD).semantics {
            contentDescription =
                "Add a comment"
        },
        decorator = { inner ->
            Box {
                if (field.text.isEmpty()) {
                    Text(
                        "Add a comment… (@ to mention)",
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inner()
            }
        }
    )
}

/** Pick a person (GET /users?q=, Guest badge per W8). The search text is local; the store only queries. */
@Composable
fun UserPickerDialog(
    scope: WorkspaceScope,
    title: String,
    allowNone: Boolean,
    onPick: (UserSummary?) -> Unit,
    onDismiss: () -> Unit
) {
    val search = remember { scope.userSearch() }
    val state by search.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { search.setQuery("") }
    ErrorSnackbar(state.lastError) { search.clearError() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        search.setQuery(it)
                    },
                    singleLine = true,
                    placeholder = { Text("Search people") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (allowNone) {
                        item(key = "none") {
                            ListItem(headlineContent = {
                                Text("Unassigned")
                            }, modifier = Modifier.clickable { onPick(null) })
                        }
                    }
                    items(state.results, key = { it.user.id }) { pickable ->
                        ListItem(
                            leadingContent = { Avatar(pickable.user.name, pickable.user.avatarColor, size = 28.dp) },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(pickable.user.name)
                                    if (pickable.isGuest) {
                                        Spacer(Modifier.width(6.dp))
                                        GuestChip()
                                    }
                                }
                            },
                            supportingContent = { Text(pickable.user.email) },
                            modifier = Modifier.clickable { onPick(pickable.user) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
