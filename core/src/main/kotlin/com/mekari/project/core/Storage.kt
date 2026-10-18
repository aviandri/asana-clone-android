package com.mekari.project.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The session token. Synchronous: OkHttp interceptors read it on a background thread. The app's
 * Keystore + DataStore implementation reads storage ONCE before `AppSession.start()`, then serves
 * from memory and writes through asynchronously. Never log the token.
 */
interface TokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

class InMemoryTokenStore(token: String? = null) : TokenStore {
    @Volatile private var token: String? = token

    override fun load() = token
    override fun save(token: String) {
        this.token = token
    }
    override fun clear() {
        token = null
    }
}

/** The one server this install talks to (SPEC-ANDROID §3). The app: DataStore, read once at start. */
interface ServerSettings {
    var serverUrl: String?
}

class InMemoryServerSettings(override var serverUrl: String? = null) : ServerSettings

object ServerUrl {
    /**
     * What the user typed → `scheme://host[:port][/path]` without a trailing slash, or null for junk.
     * Adds `https://` when there's no scheme; only http and https are allowed.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty() || url.querySize > 0 || url.fragment != null) return null
        val path = url.encodedPath.trimEnd('/')
        val port = if (url.port == if (url.scheme == "https") 443 else 80) "" else ":${url.port}"
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return "${url.scheme}://$host$port$path"
    }
}
