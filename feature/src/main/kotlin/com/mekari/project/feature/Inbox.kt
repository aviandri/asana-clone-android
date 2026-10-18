package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.InboxNotification
import com.mekari.project.core.InboxPage
import com.mekari.project.core.MekariApi
import com.mekari.project.core.SearchResults
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The inbox: the current workspace's notifications, newest first. */
class InboxStore internal constructor(private val api: MekariApi) {
    data class State(
        val notifications: List<InboxNotification> = emptyList(),
        val unreadCount: Int = 0,
        val phase: LoadState = LoadState.Idle,
        val lastError: ApiError? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Every load and local change gets a number. A response is applied unless something newer (a later
     * load that already finished, or a local change) has been applied since: so an older response never
     * overwrites newer state, yet a caller awaiting load() still gets its data when nothing newer landed.
     */
    private var version = 0
    private var applied = 0

    suspend fun load() {
        if (_state.value.notifications.isEmpty()) _state.update { it.copy(phase = LoadState.Loading) }
        version += 1
        val mine = version
        attempt(
            onError = { e ->
                if (mine >
                    applied
                ) {
                    _state.update { it.copy(phase = LoadState.Failed(e), lastError = e) }
                }
            },
            onCancel = { _state.update { if (it.phase == LoadState.Loading) it.copy(phase = LoadState.Idle) else it } }
        ) {
            val page = api.notifications(unreadOnly = false, limit = PAGE_SIZE)
            if (mine <= applied) return // something newer is already showing
            applied = mine
            _state.update {
                it.copy(notifications = page.data, unreadCount = page.unreadCount, phase = LoadState.Loaded)
            }
        }
    }

    suspend fun markRead(id: String) {
        val current = _state.value.notifications.firstOrNull { it.id == id } ?: return
        if (current.read) return
        _state.update { s ->
            s.copy(
                notifications = s.notifications.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(read = true)
                    } else {
                        it
                    }
                },
                unreadCount = maxOf(0, s.unreadCount - 1)
            )
        }
        version += 1
        applied = version
        val mine = version
        attempt(onError = { e ->
            // Revert only if nothing newer (a reload, another change) replaced this state meanwhile.
            if (mine == applied) {
                _state.update { s ->
                    s.copy(
                        notifications = s.notifications.map {
                            if (it.id ==
                                id
                            ) {
                                it.copy(read = false)
                            } else {
                                it
                            }
                        },
                        unreadCount = s.unreadCount + 1
                    )
                }
            }
            _state.update { it.copy(lastError = e) }
        }) { api.markRead(id) }
    }

    suspend fun markAllRead() {
        val previous = _state.value
        _state.update { s -> s.copy(notifications = s.notifications.map { it.copy(read = true) }, unreadCount = 0) }
        version += 1
        applied = version
        val mine = version
        attempt(onError = { e ->
            if (mine ==
                applied
            ) {
                _state.update {
                    it.copy(notifications = previous.notifications, unreadCount = previous.unreadCount)
                }
            }
            _state.update { it.copy(lastError = e) }
        }) { api.markAllRead() }
    }

    /** Marks the notification read and returns where to go (null when it has no task). */
    suspend fun open(id: String): NavigationTarget? {
        val notification = _state.value.notifications.firstOrNull { it.id == id } ?: return null
        markRead(id)
        return notification.task?.let { NavigationTarget.Task(it.id, it.projectId) }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    companion object {
        const val PAGE_SIZE = 50
    }
}

/** One local notification: "Alice Johnson commented on Fix login". */
data class LocalNotice(val id: String, val title: String, val taskId: String?)

/** Shared by the foreground poller and the app's background WorkManager job. */
object NotificationDiff {
    /** Unread notifications not in [previousIds] (nothing on the first check, when it's null). */
    fun newUnread(previousIds: Set<String>?, page: InboxPage): List<LocalNotice> {
        previousIds ?: return emptyList()
        return page.data.filter {
            !it.read && it.id !in previousIds
        }.map { LocalNotice(it.id, it.summary, it.task?.id) }
    }
}

/** Polls the inbox every 60 s while started (the foreground) and reports new unread notifications. */
class NotificationPoller internal constructor(
    private val inbox: InboxStore,
    private val scope: CoroutineScope,
    private val interval: Duration = INTERVAL
) {
    private val _newItems = MutableSharedFlow<List<LocalNotice>>(extraBufferCapacity = 8)

    /** New unread since the last poll (never on the first poll). */
    val newItems: SharedFlow<List<LocalNotice>> = _newItems.asSharedFlow()

    private var loop: Job? = null
    private var seen: Set<String>? = null

    val isRunning: Boolean get() = loop?.isActive == true

    /** Polls now, then every [interval]; does nothing if already running. */
    fun start() {
        if (isRunning) return
        loop = scope.launch {
            while (isActive) {
                poll()
                delay(interval)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** One poll: refreshes the inbox and reports unread notifications not seen before. */
    internal suspend fun poll() {
        inbox.load()
        val state = inbox.state.value
        if (state.phase != LoadState.Loaded) return
        val fresh = NotificationDiff.newUnread(seen, InboxPage(state.notifications, state.unreadCount))
        if (fresh.isNotEmpty()) _newItems.tryEmit(fresh)
        seen = (seen ?: emptySet()) + state.notifications.map { it.id }
    }

    companion object {
        val INTERVAL = 60.seconds
    }
}

/** Search: tasks, projects and people. At least 2 characters, debounced 250 ms; stale responses dropped. */
class SearchStore internal constructor(private val api: MekariApi, scope: CoroutineScope) {
    data class State(
        val query: String = "",
        val results: SearchResults? = null,
        val isSearching: Boolean = false,
        val lastError: ApiError? = null
    ) {
        /** A finished search with nothing in it ("No results"). */
        val isEmpty: Boolean get() = results?.isEmpty == true && !isSearching
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val debouncer = Debouncer(scope, 250.milliseconds)
    private var generation = 0

    /** The UI only sends the query (never binds a TextField to state.query). Whitespace-only edits don't search again. */
    fun setQuery(query: String) {
        val previous = _state.value.query.trim()
        _state.update { it.copy(query = query) }
        val q = query.trim()
        if (q == previous) return
        generation += 1
        val current = generation
        if (q.length < MINIMUM_LENGTH) {
            debouncer.cancel()
            _state.update { it.copy(results = null, isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        debouncer.schedule {
            // A newer query is on its way when the generation moved on: drop this answer.
            val found = attempt(onError = { e -> if (current == generation) searchFailed(e) }) { api.search(q) }
            if (found != null && current == generation) {
                _state.update { it.copy(results = found, lastError = null, isSearching = false) }
            }
        }
    }

    private fun searchFailed(error: ApiError) = _state.update { it.copy(lastError = error, isSearching = false) }

    /** Projects open as Project targets; people open mailto: in the app. */
    fun target(taskId: String): NavigationTarget =
        NavigationTarget.Task(taskId, _state.value.results?.tasks?.firstOrNull { it.id == taskId }?.projectId)

    companion object {
        const val MINIMUM_LENGTH = 2
    }
}
