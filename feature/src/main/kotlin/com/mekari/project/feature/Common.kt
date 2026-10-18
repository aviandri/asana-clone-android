package com.mekari.project.feature

import com.mekari.project.core.ApiError
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val error: ApiError) : LoadState
}

/** Where the app should go: a task (its detail) or a project. */
sealed interface NavigationTarget {
    data class Task(val id: String, val projectId: String?) : NavigationTarget
    data class Project(val id: String) : NavigationTarget
}

sealed interface DeepLinkResult {
    data class Open(val target: NavigationTarget) : DeepLinkResult
    data object NoAccess : DeepLinkResult
}

/** Every `MekariApi` failure is an [ApiError]; anything else (a bug) is wrapped so the UI can show it. */
internal fun Throwable.asApiError(): ApiError =
    this as? ApiError ?: ApiError(ApiError.Code("CLIENT"), null, message ?: toString())

/**
 * Runs [block]; a failure becomes [onError]'s business and the result is null. Cancellation is not an
 * error: [onCancel] runs and the `CancellationException` propagates.
 */
internal inline fun <T> attempt(onError: (ApiError) -> Unit, onCancel: () -> Unit = {}, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    onCancel()
    throw e
} catch (e: Exception) {
    onError(e.asApiError())
    null
}

/** Runs the latest scheduled action after a quiet period; earlier ones are cancelled. */
internal class Debouncer(private val scope: CoroutineScope, private val delay: Duration) {
    private var job: Job? = null

    fun schedule(action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delay)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** Synchronous change notifications between main-confined holders (no flow hop, so state is never stale). */
internal class Listeners {
    private val listeners = mutableListOf<() -> Unit>()

    fun add(listener: () -> Unit) {
        listeners += listener
    }

    fun fire() = listeners.toList().forEach { it() }
}
