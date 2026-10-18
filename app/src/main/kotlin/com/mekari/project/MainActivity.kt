package com.mekari.project

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        val session = container.session(
            fakeApi = LaunchFlags.usesFakeApi(intent),
            signedIn = !LaunchFlags.startsSignedOut(intent)
        )
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
}
