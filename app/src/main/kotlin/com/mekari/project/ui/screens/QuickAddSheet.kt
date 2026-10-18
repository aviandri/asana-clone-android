package com.mekari.project.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.Formatting
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.runAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * Quick add (FAB on My Tasks, the launcher shortcut): a personal task assigned to me, with an optional due
 * date. The title and date live here; submit(title, dueDate) returns true once it's in, then the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(scope: WorkspaceScope, onDismiss: () -> Unit) {
    val state by scope.quickAdd.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutines = androidx.compose.runtime.rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val today = LocalDate.now()
    ErrorSnackbar(state.lastError) { scope.quickAdd.clearError() }

    val submit: () -> Unit = {
        if (scope.quickAdd.canSubmit(title)) {
            val submitted = title
            coroutines.launch {
                if (appScope.runAction { scope.quickAdd.submit(submitted.trim(), due) }) {
                    sheet.hide()
                    onDismiss()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        modifier = Modifier.testTag(TestTags.QUICK_ADD_SHEET)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Inside the sheet: its content composes after the sheet itself, in the sheet's own window.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Text("New task", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Task name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(TestTags.QUICK_ADD_FIELD)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = due == today, onClick = {
                    due = if (due ==
                        today
                    ) {
                        null
                    } else {
                        today
                    }
                }, label = { Text("Today") })
                val tomorrow = today.plusDays(1)
                FilterChip(selected = due == tomorrow, onClick = {
                    due = if (due ==
                        tomorrow
                    ) {
                        null
                    } else {
                        tomorrow
                    }
                }, label = { Text("Tomorrow") })
                val other = due?.takeIf { it != today && it != tomorrow }
                FilterChip(
                    selected = other != null,
                    onClick = { pickingDate = true },
                    label = { Text(other?.let { Formatting.due(it, today) } ?: "Pick a date") }
                )
            }
            Button(
                onClick = submit,
                enabled = scope.quickAdd.canSubmit(title),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag(TestTags.QUICK_ADD_SUBMIT)
            ) { Text(if (state.isSubmitting) "Adding…" else "Add task") }
        }
    }

    if (pickingDate) {
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = due?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    due =
                        picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = picker) }
    }
}
