package com.mekari.project

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.core.DeepLink
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.MekariApp
import com.mekari.project.ui.MekariProjectTheme
import com.mekari.project.ui.components.CompletionPrefs
import com.mekari.project.ui.components.CompletionSound
import com.mekari.project.ui.components.LocalCompletionPrefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        // Only a fresh start's intent: a rotation must not open the same link again.
        if (savedInstanceState == null) handleIntent(intent)
        val flags = LaunchFlags.read(intent, allowed = BuildConfig.DEBUG)
        val session = container.session(fakeApi = flags.fakeApi, signedIn = !flags.signedOut)
        setContent {
            val prefs by container.prefs.collectAsStateWithLifecycle()
            // Decode the chime before the first tap, once the setting is on.
            if (prefs.completionSound) LaunchedEffect(Unit) { CompletionSound.prepare(applicationContext) }
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
        handleIntent(intent)
    }

    /**
     * Incoming intents are UNTRUSTED (the VIEW filter and the launcher are exported): only the action and the
     * data URI are read, never extras (the test launch flags are read once, in onCreate, in debug builds).
     * A link goes through :core's DeepLink.parse (exactly one UUID segment under task/ or project/), and it
     * only ever navigates: AppSession.resolve opens it, switching only to a workspace the user is already
     * in. Anything else is ignored.
     */
    internal fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_ADD -> container.pendingQuickAdd.value = true
            Intent.ACTION_VIEW -> intent.data?.toString()?.let(DeepLink::parse)?.let {
                container.pendingLink.value = it
            }
            else -> Unit
        }
    }

    companion object {
        /** The launcher shortcut's action (res/xml/shortcuts.xml). */
        const val ACTION_QUICK_ADD = "com.mekari.project.QUICK_ADD"
    }
}
