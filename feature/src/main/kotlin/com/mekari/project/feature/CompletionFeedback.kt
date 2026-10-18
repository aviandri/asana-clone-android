package com.mekari.project.feature

import kotlin.time.Duration
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
import kotlinx.coroutines.launch

/** What a completion cleared, for the occasional celebration. */
sealed interface Celebration {
    /** The last open top-level task in a project section (null = "No section"). */
    data class SectionCleared(val projectId: String, val sectionId: String?) : Celebration

    /** The last open top-level task in the project (anyone's). */
    data class ProjectCleared(val projectId: String) : Celebration

    /** My last open top-level task due today. */
    data object DueTodayCleared : Celebration
}

/** App settings the feature layer honours. The app persists them (DataStore); default on. */
interface FeaturePrefs {
    var celebrationsEnabled: Boolean
}

class InMemoryFeaturePrefs(override var celebrationsEnabled: Boolean = true) : FeaturePrefs

/**
 * Decides when a completion celebrates (the Mac 0.1.1 rules). [TaskCache] asks at the optimistic apply
 * (judged against what's showing then) and reports back once the server confirms; the rule is judged
 * again then, and only if it still holds is [lastEvent] set. So an un-complete, a completion the server
 * rejected, or one whose "last" stopped being true meanwhile (a sibling's failed completion reopened
 * it, a refresh brought in a new task) never celebrates.
 *
 * Rules: subtasks never celebrate; project > section > due today; the project and section rules need
 * that project's ProjectStore loaded, and the due-today rule needs My Tasks loaded. At most one event
 * per [CelebrationCooldown.INTERVAL], app-wide, and none while celebrations are off.
 */
class CompletionFeedback internal constructor(
    private val prefs: FeaturePrefs,
    private val cooldown: CelebrationCooldown,
    /** The owning scope was closed: a late confirm must not celebrate or start the app-wide cooldown. */
    private val isClosed: () -> Boolean = { false }
) {
    /** `id` increments per event, so the same celebration twice is still a change. */
    data class Event(val id: Int, val taskId: String, val celebration: Celebration)

    private val _lastEvent = MutableStateFlow<Event?>(null)
    private val _rolledBack = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private var nextId = 0

    /** `LaunchedEffect(event?.id)` plays the burst. */
    val lastEvent: StateFlow<Event?> = _lastEvent.asStateFlow()

    /** Task id of a completion the server rejected (TalkBack: "Couldn't complete X"). */
    val rolledBack: SharedFlow<String> = _rolledBack.asSharedFlow()

    var celebrationsEnabled: Boolean
        get() = prefs.celebrationsEnabled
        set(value) {
            prefs.celebrationsEnabled = value
        }

    // Wired by WorkspaceScope.
    internal var tasks: TaskCache? = null
    internal var myTasks: MyTasksStore? = null
    internal var meId: String? = null

    /** A project's store, only if one exists already (never creates one). */
    internal var projectStore: (String) -> ProjectStore? = { null }

    /** What completing [forCompleting] clears, judged against what's showing now (the task itself excluded). */
    internal fun celebration(forCompleting: String): Celebration? {
        val task = tasks?.get(forCompleting) ?: return null
        if (task.parentId != null) return null
        val projectId = task.projectId
        val project = projectId?.let(projectStore)
        if (project != null && project.state.value.phase == LoadState.Loaded) {
            val others = project.openTopLevelTasks().filter { it.id != forCompleting }
            if (others.isEmpty()) return Celebration.ProjectCleared(projectId)
            if (others.none {
                    it.sectionId == task.sectionId
                }
            ) {
                return Celebration.SectionCleared(projectId, task.sectionId)
            }
        }
        val myTasks = myTasks
        if (meId != null &&
            task.assignee?.id == meId &&
            myTasks != null &&
            myTasks.state.value.phase == LoadState.Loaded &&
            task.dueDate != null &&
            task.dueDate == myTasks.today &&
            myTasks.openTasksDueToday().none { it.id != forCompleting }
        ) {
            return Celebration.DueTodayCleared
        }
        return null
    }

    /**
     * The server confirmed completing [taskId], which celebrated when it was tapped: publishes what it
     * clears now, unless it was un-completed meanwhile, it no longer clears anything, celebrations are
     * off, or the last event was under the cooldown ago.
     */
    internal fun confirm(taskId: String) {
        if (isClosed() || tasks?.get(taskId)?.completed != true || !celebrationsEnabled || cooldown.isActive) return
        val celebration = celebration(forCompleting = taskId) ?: return
        nextId += 1
        _lastEvent.value = Event(nextId, taskId, celebration)
        cooldown.start()
    }

    internal fun rolledBack(taskId: String) {
        _rolledBack.tryEmit(taskId)
    }
}

/** The rate-limit window, owned by AppSession so it survives workspace switches. */
internal class CelebrationCooldown(private val scope: CoroutineScope, private val interval: Duration = INTERVAL) {
    var isActive = false
        private set
    private var job: Job? = null

    fun start() {
        job?.cancel()
        isActive = true
        job = scope.launch {
            delay(interval)
            isActive = false
        }
    }

    companion object {
        val INTERVAL = 30.seconds
    }
}
