package com.mekari.project.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.feature.AppSession
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.components.GuestChip
import com.mekari.project.ui.components.WorkspaceTile
import com.mekari.project.ui.launchAction

/** The workspace switcher (top bar → sheet). Switching keeps going after the sheet closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(session: AppSession, onDismiss: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(TestTags.WORKSPACE_SHEET)) {
        Text(
            "Workspaces",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(state.all, key = { it.id }) { workspace ->
                val current = workspace.id == state.current?.id
                ListItem(
                    leadingContent = { WorkspaceTile(workspace.name, workspace.color, size = 32.dp) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(workspace.name)
                            if (workspace.isGuest) {
                                Spacer(Modifier.width(8.dp))
                                GuestChip()
                            }
                        }
                    },
                    trailingContent = {
                        if (current) Icon(Icons.Filled.Check, contentDescription = "Current workspace")
                    },
                    modifier = Modifier
                        .testTag(TestTags.workspaceOption(workspace.id))
                        .semantics {
                            selected = current
                            role = Role.RadioButton
                        }
                        .clickable {
                            if (!current) appScope.launchAction { session.switchWorkspace(workspace.id) }
                            onDismiss()
                        }
                )
            }
        }
    }
}
