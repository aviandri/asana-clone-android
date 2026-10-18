package com.mekari.project.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.AppContainer
import com.mekari.project.InboxNotifications
import com.mekari.project.TestTags
import com.mekari.project.feature.AppSession
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.launchAction
import com.mekari.project.ui.rememberNotificationPermissionRequest

/** The app's snackbar host, for holders' transient errors (null in isolated previews). */
val LocalSnackbarHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/** Server (sign out to change), notifications, sound and celebrations (SPEC-ANDROID §5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(session: AppSession, container: AppContainer, onBack: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val prefs by container.prefs.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    val requestPermission = rememberNotificationPermissionRequest()
    val context = LocalContext.current
    // Re-checked on resume: the user may have just changed it in system settings.
    var canPost by remember { mutableStateOf(InboxNotifications.canPost(context)) }
    LifecycleResumeEffect(Unit) {
        canPost = InboxNotifications.canPost(context)
        onPauseOrDispose { }
    }
    // FeaturePrefs isn't observable: mirror it locally so the switch moves when tapped.
    var celebrations by remember { mutableStateOf(container.featurePrefs.celebrationsEnabled) }

    Scaffold(
        modifier = Modifier.testTag(TestTags.SETTINGS),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Server") },
                supportingContent = { Text(session.settings.serverUrl ?: "—") }
            )
            state.me?.let { me ->
                ListItem(
                    headlineContent = { Text("Signed in as") },
                    supportingContent = { Text("${me.user.name} (${me.user.email})") }
                )
            }
            HorizontalDivider()
            SwitchRow("Notifications", "New inbox items while you're away", prefs.notificationsEnabled) {
                container.setNotifications(it)
                if (it) requestPermission()
            }
            if (prefs.notificationsEnabled && !canPost) {
                // On, but blocked by the system (denied, or turned off in Settings): show the way out.
                ListItem(
                    headlineContent = { Text("Notifications are blocked") },
                    supportingContent = { Text("Allow them for Mekari Project in system settings.") },
                    trailingContent = {
                        TextButton(onClick = { openNotificationSettings(context) }) { Text("Open settings") }
                    },
                    modifier = Modifier.testTag(TestTags.NOTIFICATIONS_BLOCKED)
                )
            }
            SwitchRow("Sound", "A soft tick when you complete a task", prefs.completionSound) {
                container.setCompletionSound(it)
            }
            SwitchRow("Celebrations", "An occasional burst of petals when you finish a list", celebrations) {
                celebrations = it
                container.featurePrefs.celebrationsEnabled = it
            }
            HorizontalDivider()
            TextButton(
                onClick = { appScope.launchAction { session.signOut() } },
                modifier = Modifier.padding(8.dp).testTag(TestTags.SIGN_OUT)
            ) {
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

/** The app's notification settings page (Android 8+), else the app's details page. */
private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No settings screen to open on this device.
    }
}
