@file:UseSerializers(LocalDateSerializer::class)

package com.mekari.project.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Tri-state for PATCH bodies: a null property is omitted; `Patch.Clear` → JSON null; `Patch.Set(v)` → v. */
sealed interface Patch<out T> {
    data class Set<T>(val value: T) : Patch<T>
    data object Clear : Patch<Nothing>
}

/** The value a patch sets, or null when it clears. */
val <T> Patch<T>.value: T? get() = (this as? Patch.Set)?.value

/** `POST /tasks`. Null properties are omitted. */
@Serializable
data class NewTask(
    val title: String,
    val projectId: String? = null,
    val sectionId: String? = null,
    val parentId: String? = null,
    val description: String? = null,
    val assigneeId: String? = null,
    val dueDate: LocalDate? = null,
    val startDate: LocalDate? = null,
    val priority: Priority? = null,
    val position: Double? = null
)

/** `PATCH /tasks/:id`; null = untouched. */
@Serializable(with = TaskChanges.Serializer::class)
data class TaskChanges(
    val title: String? = null,
    val description: Patch<String>? = null,
    val projectId: Patch<String>? = null,
    val sectionId: Patch<String>? = null,
    val assigneeId: Patch<String>? = null,
    val dueDate: Patch<LocalDate>? = null,
    val startDate: Patch<LocalDate>? = null,
    val priority: Priority? = null,
    val completed: Boolean? = null,
    val position: Double? = null
) {
    /** The PATCH body. */
    fun toJson(json: Json = MekariJson): JsonObject = buildJsonObject {
        title?.let { put("title", JsonPrimitive(it)) }
        description?.let { put("description", it.json { s -> JsonPrimitive(s) }) }
        projectId?.let { put("projectId", it.json { s -> JsonPrimitive(s) }) }
        sectionId?.let { put("sectionId", it.json { s -> JsonPrimitive(s) }) }
        assigneeId?.let { put("assigneeId", it.json { s -> JsonPrimitive(s) }) }
        dueDate?.let { put("dueDate", it.json { d -> JsonPrimitive(d.toString()) }) }
        startDate?.let { put("startDate", it.json { d -> JsonPrimitive(d.toString()) }) }
        priority?.let { put("priority", json.encodeToJsonElement(it)) }
        completed?.let { put("completed", JsonPrimitive(it)) }
        position?.let { put("position", JsonPrimitive(it)) }
    }

    /**
     * Applies these changes to [task] locally (optimistic updates). The patch carries only an assignee
     * id, so the caller resolves the person: [assignee] is shown when `assigneeId` is set.
     */
    fun applied(task: TaskItem, assignee: Patch<UserSummary>? = null, now: Instant = Instant.now()): TaskItem {
        var t = task
        title?.let { t = t.copy(title = it) }
        description?.let { t = t.copy(description = it.value) }
        projectId?.let { t = t.copy(projectId = it.value) }
        sectionId?.let { t = t.copy(sectionId = it.value) }
        if (assigneeId != null && assignee != null) t = t.copy(assignee = assignee.value)
        dueDate?.let { t = t.copy(dueDate = it.value) }
        startDate?.let { t = t.copy(startDate = it.value) }
        priority?.let { t = t.copy(priority = it) }
        completed?.let { t = t.copy(completed = it, completedAt = if (it) t.completedAt ?: now else null) }
        position?.let { t = t.copy(position = it) }
        return t
    }

    /** Whether this edit changes nothing (an empty PATCH). */
    val isEmpty: Boolean get() = this == TaskChanges()

    internal object Serializer : FlatSerializer<TaskChanges>("TaskChanges") {
        override fun read(json: Json, obj: JsonObject): TaskChanges = throw UnsupportedOperationException("write-only")
        override fun write(json: Json, value: TaskChanges) = value.toJson(json)
    }
}

private fun <T> Patch<T>.json(encode: (T) -> JsonElement): JsonElement = when (this) {
    is Patch.Set -> encode(value)
    Patch.Clear -> JsonNull
}

/** `PATCH /sections/:id`. Null properties are omitted. */
@Serializable
data class SectionChanges(val name: String? = null, val position: Double? = null)
