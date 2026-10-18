package com.mekari.project

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.feature.AppModel
import com.mekari.project.ui.MekariProjectTheme

class MainActivity : ComponentActivity() {
    private val model = AppModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fakeApi = LaunchFlags.usesFakeApi(intent)
        setContent {
            MekariProjectTheme {
                val greeting by model.greeting.collectAsStateWithLifecycle()
                PlaceholderScreen(greeting = greeting, fakeApi = fakeApi)
            }
        }
    }
}

/** Placeholder until the App shell PR (4) replaces it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlaceholderScreen(greeting: String, fakeApi: Boolean) {
    Scaffold(
        // Test tags double as resource ids, so UiAutomator and device QA can find them (SPEC-ANDROID §5).
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(TestTags.PLACEHOLDER),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(greeting, style = MaterialTheme.typography.headlineSmall)
            if (fakeApi) Text("Fake API", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview
@Composable
private fun PlaceholderPreview() {
    MekariProjectTheme { PlaceholderScreen(greeting = "Mekari Project", fakeApi = true) }
}
