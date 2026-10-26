package com.mekari.project.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mekari.project.AppContainer
import com.mekari.project.TestTags
import com.mekari.project.feature.AppSession
import com.mekari.project.feature.Auth
import com.mekari.project.feature.LoadState
import com.mekari.project.feature.NavigationTarget
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.components.CompletionEffects
import com.mekari.project.ui.components.PetalBurst
import com.mekari.project.ui.screens.InboxScreen
import com.mekari.project.ui.screens.LocalSnackbarHost
import com.mekari.project.ui.screens.MyTasksScreen
import com.mekari.project.ui.screens.ProjectScreen
import com.mekari.project.ui.screens.ProjectsScreen
import com.mekari.project.ui.screens.QuickAddSheet
import com.mekari.project.ui.screens.SearchScreen
import com.mekari.project.ui.screens.SettingsScreen
import com.mekari.project.ui.screens.SignInScreen
import com.mekari.project.ui.screens.TaskDetailScreen
import com.mekari.project.ui.screens.WorkspaceSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The app-level scope for holder actions (AppContainer.appScope): writes outlive the screen that started them. */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope> { MainScope() }

/** Sign-in, the main shell, or "no workspace", depending on the session (SPEC-ANDROID §5). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MekariApp(session: AppSession, container: AppContainer) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope by session.scope.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Test tags double as resource ids on every screen, so UiAutomator and device QA can find them (§5).
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        CompositionLocalProvider(LocalSnackbarHost provides snackbar) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (state.auth) {
                    Auth.Restoring -> Splash()
                    Auth.SignedOut -> SignInScreen(session)
                    is Auth.SignedIn -> {
                        val current = scope
                        if (current == null) {
                            NoWorkspace(session)
                        } else {
                            // A new scope object per workspace: all per-workspace UI state starts over.
                            key(current) { MainShell(session, current, container, snackbar) }
                        }
                    }
                }
            }
        }
        scope?.let { key(it) { CompletionOverlays(it, snackbar) } }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    val linkNotice by container.linkNotice.collectAsStateWithLifecycle()
    LaunchedEffect(linkNotice) {
        val notice = linkNotice ?: return@LaunchedEffect
        container.linkNotice.value = null
        snackbar.showSnackbar(notice)
    }

    // "You no longer have access to X" and friends.
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        session.dismissNotice()
    }
}

/** The petal burst (when Features decides) and the "Couldn't complete X" rollback announcement. */
@Composable
private fun CompletionOverlays(scope: WorkspaceScope, snackbar: SnackbarHostState) {
    val event by scope.completionFeedback.lastEvent.collectAsStateWithLifecycle()
    // PetalBurst itself skips an event it already played (rotation, coming back to the app).
    PetalBurst(eventId = event?.id)

    val view = LocalView.current
    LaunchedEffect(scope) {
        scope.completionFeedback.rolledBack.collect { taskId ->
            val title = scope.tasks[taskId]?.title ?: "the task"
            CompletionEffects.announceRollback(view, title)
            snackbar.showSnackbar("Couldn't complete $title")
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().testTag(TestTags.SPLASH), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Signed in, but in no workspace yet (an invite to accept, say): that happens on the web. */
@Composable
private fun NoWorkspace(session: AppSession) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(TestTags.NO_WORKSPACE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No workspace yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Accept an invite or create a workspace in the web app, then come back.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            session.webUrl("/")?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }) { Text("Open in browser") }
        val appScope = LocalAppScope.current
        TextButton(onClick = { appScope.launchAction { session.signOut() } }) { Text("Sign out") }
    }
}

private object Routes {
    const val HOME = "home"
    const val TASK = "task/{id}"
    const val PROJECT = "project/{id}"
    const val SETTINGS = "settings"
    fun task(id: String) = "task/$id"
    fun project(id: String) = "project/$id"
}

/** Tabs + task detail + settings for one workspace. Large screens show My Tasks with the detail in a pane. */
@Composable
private fun MainShell(
    session: AppSession,
    scope: WorkspaceScope,
    container: AppContainer,
    snackbar: SnackbarHostState
) {
    val nav = rememberNavController()
    val state by session.state.collectAsStateWithLifecycle()
    val inbox by scope.inbox.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(TopDestination.MY_TASKS) }
    var paneTask by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkspaces by remember { mutableStateOf(false) }
    val expanded = isExpandedWidth()

    // First load for this workspace, once (not on rotation: the phase is no longer Idle then). Coming back to
    // the foreground refreshes via the process lifecycle (AppContainer).
    LaunchedEffect(scope) {
        if (scope.myTasks.state.value.phase == LoadState.Idle) scope.refresh()
    }

    // Wide → compact (unfolding back, rotating a tablet): keep the task that was open in the pane.
    LaunchedEffect(expanded) {
        val open = paneTask
        if (!expanded && open != null) {
            paneTask = null
            nav.navigate(Routes.task(open))
        }
    }

    // The pane's task is left when another is picked or the pane is closed: its composer's drafts go then (not
    // when a fold moves it to the full-screen route, above).
    val appScope = LocalAppScope.current
    val leavePane: (String?) -> Unit = { next ->
        val previous = paneTask
        if (previous != null && previous != next) appScope.launch { scope.taskDetail(previous).composer.discard() }
    }
    // The pane has no back stack: replacing or closing its task leaves it. With attachments in the composer, that
    // asks first ("Discard N attachments?"); drafts are never dropped silently.
    var confirmingPane by remember { mutableStateOf<PaneChange?>(null) }
    val changePane: (String?) -> Unit = { next ->
        val previous = paneTask
        val chips = previous?.let { scope.taskDetail(it).composer.state.value.attachments.size } ?: 0
        if (previous != null && previous != next && chips > 0) {
            confirmingPane = PaneChange(next, chips)
        } else {
            leavePane(next)
            paneTask = next
        }
    }
    val openTask: (String) -> Unit = { id ->
        if (expanded &&
            selected == TopDestination.MY_TASKS
        ) {
            changePane(id)
        } else {
            nav.navigate(Routes.task(id))
        }
    }
    val open: (NavigationTarget) -> Unit = { target ->
        when (target) {
            is NavigationTarget.Task -> nav.navigate(Routes.task(target.id))
            is NavigationTarget.Project -> nav.navigate(Routes.project(target.id))
        }
    }
    var quickAdd by rememberSaveable { mutableStateOf(false) }
    confirmingPane?.let { change ->
        AlertDialog(
            onDismissRequest = { confirmingPane = null },
            title = {
                Text(
                    if (change.attachments ==
                        1
                    ) {
                        "Discard 1 attachment?"
                    } else {
                        "Discard ${change.attachments} attachments?"
                    }
                )
            },
            text = { Text("They're in the comment you haven't sent on this task.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPane = null
                    leavePane(change.next)
                    paneTask = change.next
                }, modifier = Modifier.testTag(TestTags.PANE_DISCARD)) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmingPane = null
                }, modifier = Modifier.testTag(TestTags.PANE_KEEP)) { Text("Cancel") }
            }
        )
    }

    // A resolved deep link or notification tap (AppContainer resolves it, switching workspace if needed, so this
    // is the NEW shell after a switch) and the launcher's quick-add shortcut: consumed once.
    val pendingTarget by container.pendingTarget.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        container.pendingTarget.value = null
        open(target)
    }
    val pendingQuickAdd by container.pendingQuickAdd.collectAsStateWithLifecycle()
    LaunchedEffect(pendingQuickAdd) {
        if (pendingQuickAdd) {
            container.pendingQuickAdd.value = false
            selected = TopDestination.MY_TASKS
            quickAdd = true
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            AppScaffold(
                selected = selected,
                onSelect = { selected = it },
                workspace = state.current?.let { WorkspaceBadge(it.name, it.color, it.isGuest) },
                inboxUnread = inbox.unreadCount,
                onSwitchWorkspace = { showWorkspaces = true },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                floatingActionButton = {
                    if (selected == TopDestination.MY_TASKS) {
                        FloatingActionButton(onClick = {
                            quickAdd = true
                        }, modifier = Modifier.testTag(TestTags.QUICK_ADD_FAB)) {
                            Icon(Icons.Filled.Add, contentDescription = "Quick add")
                        }
                    }
                }
            ) { modifier ->
                when (selected) {
                    TopDestination.MY_TASKS ->
                        if (expanded) {
                            Row(modifier) {
                                MyTasksScreen(
                                    scope,
                                    onOpenTask = openTask,
                                    selectedTaskId = paneTask,
                                    modifier = Modifier.weight(1f)
                                )
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    paneTask?.let { id ->
                                        key(id) {
                                            TaskDetailScreen(
                                                scope,
                                                id,
                                                onBack = { changePane(null) },
                                                onOpenTask = openTask,
                                                inPane = true,
                                                server = session.settings.serverUrl
                                            )
                                        }
                                    } ?: NothingSelected()
                                }
                            }
                        } else {
                            MyTasksScreen(scope, onOpenTask = openTask, selectedTaskId = null, modifier = modifier)
                        }
                    TopDestination.PROJECTS -> ProjectsScreen(scope, onOpenProject = {
                        nav.navigate(Routes.project(it))
                    }, modifier = modifier)
                    TopDestination.INBOX -> InboxScreen(scope, onOpen = open, modifier = modifier)
                    TopDestination.SEARCH -> SearchScreen(scope, onOpen = open, modifier = modifier)
                }
            }
        }
        composable(Routes.TASK) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            TaskDetailScreen(
                scope,
                id,
                onBack = { nav.popBackStack() },
                onOpenTask = { nav.navigate(Routes.task(it)) },
                server = session.settings.serverUrl
            )
        }
        composable(Routes.PROJECT) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ProjectScreen(session, scope, id, onBack = {
                nav.popBackStack()
            }, onOpenTask = { nav.navigate(Routes.task(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(session, container, onBack = { nav.popBackStack() })
        }
    }

    if (showWorkspaces) {
        WorkspaceSheet(session, onDismiss = { showWorkspaces = false })
    }
    if (quickAdd) {
        QuickAddSheet(scope, onDismiss = { quickAdd = false })
    }
    AskNotificationPermissionOnce(container)
}

@Composable
private fun NothingSelected() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Select a task to see its details", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Replacing (or closing, [next] null) the pane's task while its composer holds [attachments] chips. */
private data class PaneChange(val next: String?, val attachments: Int)
