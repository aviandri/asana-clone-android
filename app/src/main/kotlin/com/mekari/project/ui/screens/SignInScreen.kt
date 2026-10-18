package com.mekari.project.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekari.project.TestTags
import com.mekari.project.feature.AppSession
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.components.WorkspaceTile
import com.mekari.project.ui.launchAction

/**
 * Server, email and password (SPEC-ANDROID §3). The fields are plain local state: nothing outside the
 * screen writes into them. Creating an account and accepting invites happen on the web.
 */
@Composable
fun SignInScreen(session: AppSession) {
    val state by session.state.collectAsStateWithLifecycle()
    val appScope = LocalAppScope.current
    var server by rememberSaveable { mutableStateOf(session.settings.serverUrl ?: "") }
    var email by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: the password must never land in the saved-state Bundle.
    var password by remember { mutableStateOf("") }
    val canSubmit = server.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && !state.isSigningIn
    val submit = {
        if (canSubmit) appScope.launchAction { session.signIn(server, email, password) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TestTags.SIGN_IN),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WorkspaceTile("Mekari Project", "#E0257F", size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text("Sign in to Mekari Project", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SIGN_IN_SERVER)
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SIGN_IN_EMAIL)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SIGN_IN_PASSWORD)
            )
            state.signInError?.let { error ->
                Text(
                    error.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.SIGN_IN_ERROR).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag(TestTags.SIGN_IN_BUTTON)
            ) {
                if (state.isSigningIn) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}
