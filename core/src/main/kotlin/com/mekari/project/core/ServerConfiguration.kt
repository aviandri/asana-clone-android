package com.mekari.project.core

/**
 * The server the app talks to (SPEC-ANDROID §3): the user enters the web app's URL, the API lives
 * under `/api/v1`, and `/health` validates it. Placeholder from the scaffold; :core owns it from PR 2.
 */
data class ServerConfiguration(val serverUrl: String) {
    val apiBaseUrl: String get() = "${serverUrl.trimEnd('/')}/api/v1/"
    val healthUrl: String get() = "${serverUrl.trimEnd('/')}/health"

    companion object {
        /** Trims whitespace and a trailing slash, adds `https://` when no scheme is given. */
        fun normalize(raw: String): ServerConfiguration? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val scheme = withScheme.substringBefore("://").lowercase()
            val rest = withScheme.substringAfter("://").trimEnd('/')
            if (scheme != "http" && scheme != "https") return null
            if (rest.isEmpty()) return null
            return ServerConfiguration("$scheme://$rest")
        }
    }
}
