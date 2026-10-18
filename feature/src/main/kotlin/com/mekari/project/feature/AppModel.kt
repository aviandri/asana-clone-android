package com.mekari.project.feature

import com.mekari.project.core.ServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Placeholder state holder from the scaffold, so :app → :feature → :core is wired end to end. */
class AppModel(server: ServerConfiguration? = null) {
    private val _greeting = MutableStateFlow(greetingFor(server))
    val greeting: StateFlow<String> = _greeting.asStateFlow()

    fun connect(server: ServerConfiguration) {
        _greeting.value = greetingFor(server)
    }

    private fun greetingFor(server: ServerConfiguration?) =
        if (server == null) "Mekari Project" else "Mekari Project · ${server.serverUrl}"
}
