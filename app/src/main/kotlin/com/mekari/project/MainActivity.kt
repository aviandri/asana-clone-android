package com.mekari.project

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.core.DeepLink
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.MekariApp
import com.mekari.project.ui.MekariProjectTheme
import com.mekari.project.ui.components.CompletionPrefs
import com.mekari.project.ui.components.LocalCompletionPrefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        // Only a fresh start's intent: a rotation must not open the same link again.
        if (savedInstanceState == null) handle(intent)
        val flags = LaunchFlags.read(intent, allowed = BuildConfig.DEBUG)
        val session = container.session(fakeApi = flags.fakeApi, signedIn = !flags.signedOut)
        setContent {
            val prefs by container.prefs.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalAppScope provides container.appScope,
                LocalCompletionPrefs provides CompletionPrefs(playSound = prefs.completionSound)
            ) {
                MekariProjectTheme { MekariApp(session = session, container = container) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** mekariproject:// links (deep links, notification taps) and the launcher's quick-add shortcut. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_ADD -> container.pendingQuickAdd.value = true
            Intent.ACTION_VIEW -> intent.data?.toString()?.let(DeepLink::parse)?.let {
                container.pendingLink.value = it
            }
        }
    }

    companion object {
        /** The launcher shortcut's action (res/xml/shortcuts.xml). */
        const val ACTION_QUICK_ADD = "com.mekari.project.QUICK_ADD"
    }
}
