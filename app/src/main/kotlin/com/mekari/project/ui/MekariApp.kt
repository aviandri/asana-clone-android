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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.mekari.project.feature.WorkspaceScope
import com.mekari.project.ui.components.CompletionEffects
import com.mekari.project.ui.components.PetalBurst
import com.mekari.project.ui.screens.LocalSnackbarHost
import com.mekari.project.ui.screens.MyTasksScreen
import com.mekari.project.ui.screens.SettingsScreen
import com.mekari.project.ui.screens.SignInScreen
import com.mekari.project.ui.screens.TaskDetailScreen
import com.mekari.project.ui.screens.WorkspaceSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

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
    // Don't replay an event that was already shown (rotation, coming back to the app).
    var shown by rememberSaveable { mutableStateOf(event?.id) }
    val play = event?.id?.takeIf { it != shown }
    LaunchedEffect(play) { if (play != null) shown = play }
    PetalBurst(eventId = play)

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
    const val SETTINGS = "settings"
    fun task(id: String) = "task/$id"
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

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            AppScaffold(
                selected = selected,
                onSelect = { selected = it },
                workspace = state.current?.let { WorkspaceBadge(it.name, it.color, it.isGuest) },
                inboxUnread = inbox.unreadCount,
                onSwitchWorkspace = { showWorkspaces = true },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            ) { modifier ->
                val openTask: (String) -> Unit = { id ->
                    if (expanded) paneTask = id else nav.navigate(Routes.task(id))
                }
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
                                            TaskDetailScreen(scope, id, onBack = {
                                                paneTask = null
                                            }, onOpenTask = openTask, inPane = true)
                                        }
                                    } ?: NothingSelected()
                                }
                            }
                        } else {
                            MyTasksScreen(scope, onOpenTask = openTask, selectedTaskId = null, modifier = modifier)
                        }
                    else -> ComingSoon(selected, modifier)
                }
            }
        }
        composable(Routes.TASK) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            TaskDetailScreen(scope, id, onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate(Routes.task(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(session, container, onBack = { nav.popBackStack() })
        }
    }

    if (showWorkspaces) {
        WorkspaceSheet(session, onDismiss = { showWorkspaces = false })
    }
}

@Composable
private fun NothingSelected() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Select a task to see its details", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Projects, Inbox and Search arrive with PR 5 (SPEC-ANDROID §7). */
@Composable
private fun ComingSoon(destination: TopDestination, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(destination.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
