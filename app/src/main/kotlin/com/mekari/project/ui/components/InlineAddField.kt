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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mekari.project.ui.LocalAppScope
import kotlinx.coroutines.launch

/**
 * An add field's state, owned by the SCREEN (outside the lazy items): the text and whether an add is in flight.
 * Both live here, not in the item, so recycling or scrolling the row away can't reset the text or drop the
 * double-submit guard. The text survives rotation ([rememberAddFieldState]); an in-flight add finishes in the
 * app scope anyway.
 */
@Stable
class AddFieldState(initialText: String = "") {
    val text = TextFieldState(initialText)
    var submitting by mutableStateOf(false)
        internal set

    companion object {
        val Saver: Saver<AddFieldState, String> =
            Saver(save = { it.text.text.toString() }, restore = { AddFieldState(it) })
    }
}

@Composable
fun rememberAddFieldState(): AddFieldState = rememberSaveable(saver = AddFieldState.Saver) { AddFieldState() }

/** One [AddFieldState] per key (a project's sections), saved across rotation. */
@Stable
class AddFieldStates(initial: Map<String, String> = emptyMap()) {
    private val states = mutableMapOf<String, AddFieldState>().apply {
        initial.forEach { (key, text) -> put(key, AddFieldState(text)) }
    }

    operator fun get(key: String): AddFieldState = states.getOrPut(key) { AddFieldState() }

    companion object {
        val Saver: Saver<AddFieldStates, Map<String, String>> = Saver(
            save = { all -> all.states.mapValues { it.value.text.text.toString() }.filterValues { it.isNotEmpty() } },
            restore = { AddFieldStates(it) }
        )
    }
}

@Composable
fun rememberAddFieldStates(): AddFieldStates = rememberSaveable(saver = AddFieldStates.Saver) { AddFieldStates() }

/**
 * The inline "Add task" / "Add subtask" field.
 *
 * The Mac crash lesson: never push text into a field while the user types during list updates. So:
 * - the state ([AddFieldState]) is the SCREEN's, so recycling or reordering rows can't recreate or reset it;
 * - nothing ever writes into the text from outside: there's no value/onValueChange round trip;
 * - it clears only after [onSubmit] reports that the row is in, and only if the text hasn't changed since;
 * - one add at a time: a fast double Done never creates the task twice, even if the row scrolled away.
 *
 * Done on the keyboard submits and keeps focus for the next one.
 */
@Composable
fun InlineAddField(
    state: AddFieldState,
    placeholder: String,
    onSubmit: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    testTag: String = "addTask.field"
) {
    // The app scope: the add (and the clear after it) finishes even if this row is recycled meanwhile.
    val appScope = LocalAppScope.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val text = state.text
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(12.dp))
        BasicTextField(
            state = text,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = {
                val submitted = text.text.toString()
                val title = submitted.trim()
                if (title.isNotEmpty() && !state.submitting) {
                    state.submitting = true
                    appScope.launch {
                        try {
                            // Clear only if the add went in and nothing new was typed meanwhile.
                            if (onSubmit(title) && text.text.toString() == submitted) text.clearText()
                        } finally {
                            state.submitting = false
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).testTag(testTag).semantics { contentDescription = placeholder },
            decorator = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.text.isEmpty()) {
                        Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
    }
}
