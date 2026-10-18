package com.mekari.project.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.core.ProjectPrivacy
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.components.parseHexColor
import com.mekari.project.ui.launchAction

/** The Projects tab: the workspace's projects by name (guests see only theirs, W1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(scope: WorkspaceScope, onOpenProject: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by scope.projects.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(scope) { if (state.phase == LoadState.Idle) scope.projects.load() }
    ErrorSnackbar(state.lastError) { scope.projects.clearError() }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            appScope.launchAction {
                scope.projects.load()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize().testTag(TestTags.PROJECTS)
    ) {
        if (state.projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state.phase) {
                    LoadState.Loaded -> Text("No projects yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is LoadState.Failed -> Text(
                        "Couldn't load projects",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> CircularProgressIndicator()
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.projects, key = { it.id }) { project ->
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(20.dp).background(parseHexColor(project.color), RoundedCornerShape(5.dp)))
                        },
                        headlineContent = { Text(project.name) },
                        trailingContent = {
                            if (project.privacy == ProjectPrivacy.PRIVATE) {
                                Icon(Icons.Filled.Lock, contentDescription = "Private", Modifier.size(16.dp))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenProject(project.id) }
                            .padding(horizontal = 4.dp)
                            .testTag(TestTags.projectRow(project.id))
                    )
                }
            }
        }
    }
}
