package com.mekari.project.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Placeholder state holder from the scaffold (MainActivity still uses it); AppSession replaces it in PR 3/4. */
class AppModel(serverUrl: String? = null) {
    private val _greeting = MutableStateFlow(greetingFor(serverUrl))
    val greeting: StateFlow<String> = _greeting.asStateFlow()

    fun connect(serverUrl: String) {
        _greeting.value = greetingFor(serverUrl)
    }

    private fun greetingFor(serverUrl: String?) = if (serverUrl ==
        null
    ) {
        "Mekari Project"
    } else {
        "Mekari Project · $serverUrl"
    }
}
