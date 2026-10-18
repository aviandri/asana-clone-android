package com.mekari.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The inline "Add task" / "Add subtask" field.
 *
 * The Mac crash lesson: never push text into a field while the user types during list updates. So:
 * - the text lives in a [TextFieldState] that the SCREEN owns (`rememberTextFieldState()` outside the lazy
 *   items, one per section), so recycling or reordering rows can't recreate or reset it;
 * - nothing ever writes into it from outside: there's no value/onValueChange round trip;
 * - it clears only after [onSubmit] reports that the row is in, and only if the text hasn't changed since.
 *
 * Done on the keyboard submits and keeps focus for the next one.
 */
@Composable
fun InlineAddField(
    state: TextFieldState,
    placeholder: String,
    onSubmit: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    testTag: String = "addTask.field"
) {
    val scope = rememberCoroutineScope()
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(12.dp))
        BasicTextField(
            state = state,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = {
                val submitted = state.text.toString()
                val title = submitted.trim()
                if (title.isNotEmpty()) {
                    scope.launch {
                        // Clear only if the add went in and nothing new was typed meanwhile.
                        if (onSubmit(title) && state.text.toString() == submitted) state.clearText()
                    }
                }
            },
            modifier = Modifier.weight(1f).testTag(testTag).semantics { contentDescription = placeholder },
            decorator = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
    }
}
