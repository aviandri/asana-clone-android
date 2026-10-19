package com.mekari.project

import com.mekari.project.core.TokenStore

/**
 * The session token in memory, written through to storage (SPEC-ANDROID §3).
 *
 * load/save/clear/clearIf share ONE lock, and each change hands its persist step to [persist] INSIDE that
 * lock, so storage always ends on the same value as memory. That's what makes clearIf safe: a late 401 for
 * an old token (OkHttp's thread) can't wipe a fresh sign-in's token (main thread), in memory or on disk.
 *
 * Every end of a session is logged through [log] with its reason (QA A5): never the token itself.
 */
class PersistentTokenStore(
    initial: String?,
    private val log: (String) -> Unit = {},
    /** The new token, or null to remove it. Called under the lock, in call order. */
    private val persist: (String?) -> Unit
) : TokenStore {
    private val lock = Any()
    private var token: String? = initial

    override fun load(): String? = synchronized(lock) { token }

    override fun save(token: String) = synchronized(lock) {
        this.token = token
        persist(token)
    }

    /** Only a sign-out clears unconditionally (LiveApi.logout). */
    override fun clear() = synchronized(lock) {
        if (token != null) log("session cleared: user-sign-out or sign-in to another server")
        token = null
        persist(null)
    }

    override fun clearIf(expected: String): Boolean = clearIf(expected, "unknown")

    /** A 401 from the client named [source] for the token it sent ([expected]). */
    fun clearIf(expected: String, source: String): Boolean = synchronized(lock) {
        if (token != expected) {
            val why = if (token == null) "already signed out" else "sent with an older session"
            log("401 ignored ($source): $why")
            return false
        }
        token = null
        persist(null)
        log("session cleared: 401-clear($source)")
        true
    }

    /**
     * The token a delayed restore opened (AppContainer.retryKeptSession): taken only if there's still no
     * session, and not persisted, since storage already holds it. False if a sign-in got there first.
     */
    fun adopt(restored: String): Boolean = synchronized(lock) {
        if (token != null) return false
        token = restored
        true
    }

    /** Removes the stored token, unless a sign-in stored a new one meanwhile (a delayed restore found it unreadable). */
    fun dropStoredIfSignedOut() = synchronized(lock) {
        if (token == null) persist(null)
    }

    /** This store as one client sees it: its 401s are logged under [source] ("app", "worker"). */
    fun forSource(source: String): TokenStore = object : TokenStore {
        override fun load() = this@PersistentTokenStore.load()
        override fun save(token: String) = this@PersistentTokenStore.save(token)
        override fun clear() = this@PersistentTokenStore.clear()
        override fun clearIf(expected: String) = this@PersistentTokenStore.clearIf(expected, source)
    }
}

/** What the stored, sealed token opened to (see SessionCipher.open). Never prints the token. */
internal sealed interface Opened {
    class Token(val value: String) : Opened {
        override fun toString() = "Token(…)"
    }

    /** Can never be opened: the key is gone (a restore, cleared Keystore) or the blob fails authentication. */
    data class Lost(val why: String) : Opened

    /** The Keystore didn't answer this time ([why]: the exception's class). The next launch may succeed. */
    data class Unavailable(val why: String) : Opened
}

/** The launch decision: [token] to start with, [reason] for the log, and whether to drop the stored blob. */
internal class Restored(val token: String?, val reason: String, val dropBlob: Boolean)

/**
 * Turns the stored blob into the launch's token. A blob that can never be opened is dropped; one the Keystore
 * merely failed to open (retried [attempts] times) is KEPT, and the key is never deleted here: a transient
 * Keystore error must not end a session for good. Sign-in replaces the blob either way.
 */
internal fun restoreSession(
    sealed: String?,
    open: (String) -> Opened,
    mustClear: Boolean = false,
    attempts: Int = 3,
    pause: () -> Unit = { Thread.sleep(100) }
): Restored {
    // A token write that failed twice (a sign-out among them, maybe): whatever storage holds is stale.
    if (mustClear) return Restored(null, "cleared(an earlier session write failed)", dropBlob = true)
    sealed ?: return Restored(null, "absent", dropBlob = false)
    var opened = open(sealed)
    var tries = 1
    while (opened is Opened.Unavailable && tries < attempts) {
        pause()
        opened = open(sealed)
        tries += 1
    }
    return when (opened) {
        is Opened.Token -> Restored(opened.value, "restored", dropBlob = false)
        is Opened.Lost -> Restored(null, "decrypt-failed(${opened.why})", dropBlob = true)
        is Opened.Unavailable ->
            Restored(
                null,
                "decrypt-failed(keystore:${opened.why}, $tries tries; kept for next launch)",
                dropBlob = false
            )
    }
}
