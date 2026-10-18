package com.mekari.project.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.core.InboxNotification
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.NavigationTarget
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.RelativeTime
import com.mekari.project.ui.components.Avatar
import com.mekari.project.ui.launchAction

/** Notifications, newest first; unread in bold with a dot. Tapping marks it read and opens its task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(scope: WorkspaceScope, onOpen: (NavigationTarget) -> Unit, modifier: Modifier = Modifier) {
    val state by scope.inbox.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(scope) { if (state.phase == LoadState.Idle) scope.inbox.load() }
    ErrorSnackbar(state.lastError) { scope.inbox.clearError() }

    Column(modifier.fillMaxSize().testTag(TestTags.INBOX)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (state.unreadCount > 0) "${state.unreadCount} unread" else "All caught up",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { appScope.launchAction { scope.inbox.markAllRead() } },
                enabled = state.unreadCount > 0,
                modifier = Modifier.testTag(TestTags.INBOX_MARK_ALL)
            ) { Text("Mark all as read") }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                appScope.launchAction {
                    scope.inbox.load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            if (state.notifications.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (state.phase) {
                        LoadState.Loaded -> Text(
                            "Assignments, comments and mentions show up here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is LoadState.Failed -> Text(
                            "Couldn't load the inbox",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> CircularProgressIndicator()
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.notifications, key = { it.id }) { notification ->
                        InboxRow(notification) {
                            appScope.launchAction { scope.inbox.open(notification.id)?.let(onOpen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(notification: InboxNotification, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(TestTags.inboxRow(notification.id))
            .semantics(mergeDescendants = true) {
                contentDescription = (if (notification.read) "" else "Unread: ") + notification.summary
            },
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(8.dp)
                .background(
                    if (notification.read) Color.Transparent else MaterialTheme.colorScheme.primary,
                    CircleShape
                )
                .clearAndSetSemantics { }
        )
        Spacer(Modifier.width(8.dp))
        Avatar(notification.actor.name, notification.actor.avatarColor, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                notification.summary,
                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                RelativeTime.format(notification.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
