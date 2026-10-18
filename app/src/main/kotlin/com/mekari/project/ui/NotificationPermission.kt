package com.mekari.project.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mekari.project.AppContainer
import com.mekari.project.InboxNotifications

/** Android 13+: asks for POST_NOTIFICATIONS once per install, after the first sign-in (as the Mac asks once). */
@Composable
fun AskNotificationPermissionOnce(container: AppContainer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (container.shouldAskNotificationPermission() && !InboxNotifications.canPost(context)) {
            container.markNotificationPermissionAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** A launcher for the permission (Settings asks again when notifications are switched on). */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !InboxNotifications.canPost(context)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
