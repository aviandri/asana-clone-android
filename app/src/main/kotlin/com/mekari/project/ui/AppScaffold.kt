package com.mekari.project.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekari.project.TestTags
import com.mekari.project.ui.components.GuestChip
import com.mekari.project.ui.components.WorkspaceTile

/** The four top-level destinations (SPEC-ANDROID §5). */
enum class TopDestination(val label: String, val icon: ImageVector, val tag: String) {
    MY_TASKS("My Tasks", Icons.Filled.CheckCircle, TestTags.NAV_MY_TASKS),
    PROJECTS("Projects", Icons.AutoMirrored.Filled.List, TestTags.NAV_PROJECTS),
    INBOX("Inbox", Icons.Filled.Notifications, TestTags.NAV_INBOX),
    SEARCH("Search", Icons.Filled.Search, TestTags.NAV_SEARCH)
}

/** Large screens (tablets, unfolded foldables) get a NavigationRail and list-detail (≥ 840 dp). */
@Composable
fun isExpandedWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** The current workspace, as the top bar shows it. */
data class WorkspaceBadge(val name: String, val color: String, val isGuest: Boolean)

/**
 * The app frame: a top bar with the workspace switcher and Settings, plus a bottom NavigationBar (phones)
 * or a NavigationRail (≥ 840 dp). [content] draws the selected destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    workspace: WorkspaceBadge?,
    inboxUnread: Int,
    onSwitchWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val expanded = isExpandedWidth()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { WorkspaceSwitcherButton(workspace, onSwitchWorkspace) },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(TestTags.SETTINGS_BUTTON)) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            if (!expanded) {
                NavigationBar {
                    TopDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selected,
                            onClick = { onSelect(destination) },
                            icon = { DestinationIcon(destination, inboxUnread) },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag(destination.tag)
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (expanded) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail(Modifier.testTag(TestTags.NAV_RAIL)) {
                    TopDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = destination == selected,
                            onClick = { onSelect(destination) },
                            icon = { DestinationIcon(destination, inboxUnread) },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag(destination.tag)
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            content(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun DestinationIcon(destination: TopDestination, inboxUnread: Int) {
    if (destination == TopDestination.INBOX && inboxUnread > 0) {
        BadgedBox(badge = { Badge(Modifier.testTag(TestTags.INBOX_BADGE)) { Text(badgeText(inboxUnread)) } }) {
            Icon(destination.icon, contentDescription = "${destination.label}, $inboxUnread unread")
        }
    } else {
        Icon(destination.icon, contentDescription = destination.label)
    }
}

/** "99+" beyond two digits, as Material badges do. */
fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()

/** Colour tile + name (+ Guest chip) that opens the workspace sheet. */
@Composable
private fun WorkspaceSwitcherButton(workspace: WorkspaceBadge?, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = workspace != null,
        modifier = Modifier.testTag(TestTags.WORKSPACE_SWITCHER).semantics {
            contentDescription = workspace?.let { "Workspace: ${it.name}. Switch workspace" } ?: "No workspace"
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (workspace != null) {
                WorkspaceTile(workspace.name, workspace.color, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (workspace.isGuest) {
                    Spacer(Modifier.width(6.dp))
                    GuestChip()
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            } else {
                Text("Mekari Project")
            }
        }
    }
}
