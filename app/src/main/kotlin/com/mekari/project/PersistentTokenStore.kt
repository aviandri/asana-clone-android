package com.mekari.project

import com.mekari.project.core.TokenStore

/**
 * The session token in memory, written through to storage (SPEC-ANDROID §3).
 *
 * load/save/clear/clearIf share ONE lock, and each change hands its persist step to [persist] INSIDE that
 * lock. [persist] must enqueue in call order (AppContainer: one serial channel), so storage always ends on
 * the same value as memory. That's what makes clearIf safe: a late 401 for an old token (OkHttp's thread)
 * can't wipe a fresh sign-in's token (main thread), in memory or on disk.
 */
class PersistentTokenStore(
    initial: String?,
    /** The new token, or null to remove it. Called under the lock; must only enqueue. */
    private val persist: (String?) -> Unit
) : TokenStore {
    private val lock = Any()
    private var token: String? = initial

    override fun load(): String? = synchronized(lock) { token }

    override fun save(token: String) = synchronized(lock) {
        this.token = token
        persist(token)
    }

    override fun clear() = synchronized(lock) {
        token = null
        persist(null)
    }

    override fun clearIf(expected: String): Boolean = synchronized(lock) {
        if (token != expected) return false
        token = null
        persist(null)
        true
    }
}
