package com.mekari.project.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.feature.NavigationTarget
import com.mekari.project.feature.SearchStore
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.components.Avatar
import com.mekari.project.ui.components.parseHexColor

/**
 * Tasks, projects and people. The query text lives here; the store only gets setQuery (debounced 250 ms,
 * at least 2 characters). People open an email to them.
 */
@Composable
fun SearchScreen(scope: WorkspaceScope, onOpen: (NavigationTarget) -> Unit, modifier: Modifier = Modifier) {
    val state by scope.search.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    ErrorSnackbar(state.lastError) {}

    Column(modifier.fillMaxSize().testTag(TestTags.SEARCH)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                scope.search.setQuery(it)
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search tasks, projects and people") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(TestTags.SEARCH_FIELD)
        )
        if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        val results = state.results
        when {
            query.trim().length < SearchStore.MINIMUM_LENGTH -> Hint(
                "Type at least ${SearchStore.MINIMUM_LENGTH} characters"
            )
            state.isEmpty -> Hint("No results for \"${query.trim()}\"")
            results != null -> LazyColumn(Modifier.fillMaxSize()) {
                if (results.tasks.isNotEmpty()) {
                    item(key = "h.tasks") { Header("Tasks") }
                    items(results.tasks, key = { "t.${it.id}" }) { task ->
                        ListItem(
                            headlineContent = { Text(task.title) },
                            supportingContent = task.project?.let { { Text(it.name) } },
                            modifier = Modifier.clickable {
                                onOpen(scope.search.target(task.id))
                            }.testTag(TestTags.searchTask(task.id))
                        )
                    }
                }
                if (results.projects.isNotEmpty()) {
                    item(key = "h.projects") { Header("Projects") }
                    items(results.projects, key = { "p.${it.id}" }) { project ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    Modifier.size(
                                        16.dp
                                    ).background(parseHexColor(project.color), RoundedCornerShape(4.dp))
                                )
                            },
                            headlineContent = { Text(project.name) },
                            modifier = Modifier
                                .clickable { onOpen(NavigationTarget.Project(project.id)) }
                                .testTag(TestTags.searchProject(project.id))
                        )
                    }
                }
                if (results.users.isNotEmpty()) {
                    item(key = "h.people") { Header("People") }
                    items(results.users, key = { "u.${it.id}" }) { user ->
                        ListItem(
                            leadingContent = { Avatar(user.name, user.avatarColor, size = 32.dp) },
                            headlineContent = { Text(user.name) },
                            supportingContent = { Text(user.email) },
                            modifier = Modifier.clickable { emailPerson(context, user.email) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An email to a person from search. The address comes from the server: encoded, and no mail app is fine. */
private fun emailPerson(context: android.content.Context, email: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(email, "@"))))
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No email app on this device", android.widget.Toast.LENGTH_SHORT).show()
    }
}
