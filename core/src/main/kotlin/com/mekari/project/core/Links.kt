package com.mekari.project.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URISyntaxException

/** Web app URLs for "Open in browser" (Custom Tabs). Every link carries `?workspace=<id>`. */
object WebLinks {
    fun project(id: String, server: String, workspace: String): String =
        url(server, listOf("projects", id), listOf("workspace" to workspace))

    fun task(task: TaskItem, server: String, workspace: String): String = task.projectId?.let {
        url(server, listOf("projects", it), listOf("task" to task.id, "workspace" to workspace))
    } ?: url(server, listOf("my-tasks"), listOf("task" to task.id, "workspace" to workspace))

    fun workspaceSettings(server: String, workspace: String): String =
        url(server, listOf("workspace", "settings"), listOf("workspace" to workspace))

    /** Invites are accepted on the web in v1. */
    fun acceptInvite(server: String): String = url(server, listOf("login"), emptyList())

    /** `server` + `path` (which may carry its own query) + `?workspace=` when given. */
    fun url(server: String, path: String, workspace: String?): String {
        val parsed = "http://x/${path.trimStart('/')}".toHttpUrl()
        val query = (0 until parsed.querySize).map { parsed.queryParameterName(it) to (parsed.queryParameterValue(it) ?: "") }
        return url(server, parsed.pathSegments.filter { it.isNotEmpty() }, query + listOfNotNull(workspace?.let { "workspace" to it }))
    }

    private fun url(server: String, segments: List<String>, query: List<Pair<String, String>>): String {
        val builder: HttpUrl.Builder = server.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }
}

/**
 * `mekariproject://task/<uuid>` and `mekariproject://project/<uuid>`. The id must be exactly one
 * UUID segment (as the BE issues them), so nothing else (e.g. `..%2Fusers`) can reach a request path.
 */
sealed interface DeepLink {
    data class Task(val id: String) : DeepLink
    data class Project(val id: String) : DeepLink

    val uri: String
        get() = when (this) {
            is Task -> "$SCHEME://task/$id"
            is Project -> "$SCHEME://project/$id"
        }

    companion object {
        const val SCHEME = "mekariproject"
        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun parse(uri: String): DeepLink? {
            val parsed = try {
                URI(uri)
            } catch (_: URISyntaxException) {
                return null
            }
            if (parsed.scheme?.lowercase() != SCHEME || parsed.rawQuery != null || parsed.rawFragment != null) return null
            val segments = (parsed.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            val id = segments.singleOrNull()?.takeIf { uuid.matches(it) }?.lowercase() ?: return null
            return when (parsed.host?.lowercase()) {
                "task" -> Task(id)
                "project" -> Project(id)
                else -> null
            }
        }
    }
}

/**
 * Float positions for ordering tasks and sections (BE §2): between neighbours `(a+b)/2`, after the
 * last `last+1000`, before the first `first/2`, `1000` in an empty list.
 */
object Reorder {
    const val STEP = 1000.0

    /** Below this gap between neighbours, the list is renumbered first. */
    const val MINIMUM_GAP = 1e-6

    fun position(before: Double?, after: Double?): Double = when {
        before == null && after == null -> STEP
        after == null -> before!! + STEP
        before == null -> if (after > 0) after / 2 else after - STEP
        else -> (before + after) / 2
    }

    /** The position for an item inserted at [index] in [positions] (the destination without the moved item). */
    fun position(insertingAt: Int, positions: List<Double>): Double =
        position(positions.getOrNull(insertingAt - 1), positions.getOrNull(insertingAt))

    /** Whether a position between [before] and [after] would be too close to a neighbour. */
    fun needsRenumber(before: Double?, after: Double?): Boolean = when {
        after == null -> false
        before == null -> after > 0 && after / 2 < MINIMUM_GAP
        else -> after - before < MINIMUM_GAP * 2
    }

    /** Even positions (`STEP`, `2*STEP`, …) for [count] items. */
    fun renumbered(count: Int): List<Double> = (1..count).map { it * STEP }
}
