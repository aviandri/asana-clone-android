@file:UseSerializers(InstantSerializer::class)

package com.mekari.project.core

import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A task's comments and activity (`GET /tasks/:id/stories`), discriminated by `kind`. */
@kotlinx.serialization.Serializable(with = StorySerializer::class)
sealed interface Story {
    val id: String
    val createdAt: Instant

    /** Unique across both kinds, for list keys. */
    val key: String
}

@kotlinx.serialization.Serializable(with = CommentSerializer::class)
data class Comment(
    override val id: String,
    val author: UserSummary,
    /** Markdown; mentions in storage form `@[Name](userId)`. */
    val body: String,
    override val createdAt: Instant,
    val editedAt: Instant? = null
) : Story {
    override val key: String get() = "comment-$id"

    /** `body` split into text and mention tokens (markdown is rendered by the app). */
    val segments: List<Mentions.Segment> get() = Mentions.parse(body)
}

@kotlinx.serialization.Serializable(with = ActivitySerializer::class)
data class Activity(
    override val id: String,
    val actor: UserSummary,
    val type: ActivityType,
    val data: JsonElement = JsonNull,
    override val createdAt: Instant
) : Story {
    override val key: String get() = "activity-$id"
}

/** Thrown for a `kind` this build doesn't know; feeds skip such stories. */
class UnknownStoryKind(val kind: String) : SerializationException("unknown story kind \"$kind\"")

internal object StorySerializer : FlatSerializer<Story>("Story") {
    override fun read(json: Json, obj: JsonObject): Story = when (val kind = obj["kind"]?.jsonPrimitive?.content) {
        "comment" -> CommentSerializer.read(json, obj)
        "activity" -> ActivitySerializer.read(json, obj)
        else -> throw UnknownStoryKind(kind ?: "")
    }

    override fun write(json: Json, value: Story): JsonObject = when (value) {
        is Comment -> CommentSerializer.write(json, value)
        is Activity -> ActivitySerializer.write(json, value)
    }
}

/** A comment on its own (`POST /tasks/:id/comments`, `PATCH /comments/:id`); `kind` is optional here. */
internal object CommentSerializer : FlatSerializer<Comment>("Comment") {
    override fun read(json: Json, obj: JsonObject) = Comment(
        id = json.decodeFromJsonElement(obj.require("id")),
        author = json.decodeFromJsonElement(obj.require("author")),
        body = json.decodeFromJsonElement(obj.require("body")),
        createdAt = json.decodeFromJsonElement(InstantSerializer, obj.require("createdAt")),
        editedAt = obj["editedAt"]?.takeIf {
            it != JsonNull
        }?.let { json.decodeFromJsonElement(InstantSerializer, it) }
    )

    override fun write(json: Json, value: Comment) = buildJsonObject {
        put("kind", "comment")
        put("id", value.id)
        put("author", json.encodeToJsonElement(value.author))
        put("body", value.body)
        put("createdAt", json.encodeToJsonElement(InstantSerializer, value.createdAt))
        put("editedAt", value.editedAt?.let { json.encodeToJsonElement(InstantSerializer, it) } ?: JsonNull)
    }
}

internal object ActivitySerializer : FlatSerializer<Activity>("Activity") {
    override fun read(json: Json, obj: JsonObject) = Activity(
        id = json.decodeFromJsonElement(obj.require("id")),
        actor = json.decodeFromJsonElement(obj.require("actor")),
        type = json.decodeFromJsonElement(obj.require("type")),
        data = obj["data"] ?: JsonNull,
        createdAt = json.decodeFromJsonElement(InstantSerializer, obj.require("createdAt"))
    )

    override fun write(json: Json, value: Activity) = buildJsonObject {
        put("kind", "activity")
        put("id", value.id)
        put("actor", json.encodeToJsonElement(value.actor))
        put("type", json.encodeToJsonElement(value.type))
        put("data", value.data)
        put("createdAt", json.encodeToJsonElement(InstantSerializer, value.createdAt))
    }
}

private fun JsonObject.require(key: String): JsonElement =
    this[key] ?: throw SerializationException("missing key \"$key\"")

/**
 * `{ "data": [Story] }`, skipping stories of an unknown `kind` instead of failing the whole feed.
 * Malformed stories of a known kind still fail.
 */
internal fun decodeStoryFeed(json: Json, element: JsonElement): List<Story> =
    element.jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { entry ->
        try {
            json.decodeFromJsonElement(StorySerializer, entry)
        } catch (_: UnknownStoryKind) {
            null
        }
    }

/** Mentions in comment bodies: `@[Alice Johnson](userId)`. */
object Mentions {
    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Mention(val name: String, val userId: String) : Segment
    }

    /** Splits `body` into plain text and mention tokens. Malformed tokens stay text. */
    fun parse(body: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val text = StringBuilder()
        var i = 0
        while (true) {
            val at = body.indexOf("@[", i)
            if (at < 0) break
            text.append(body, i, at)
            val nameStart = at + 2
            val close = body.indexOf("](", nameStart)
            val end = if (close >= 0) body.indexOf(')', close + 2) else -1
            if (close >= 0 && end >= 0) {
                val name = body.substring(nameStart, close)
                val id = body.substring(close + 2, end)
                if (name.isNotEmpty() && ']' !in name && id.isNotEmpty() && id.none { it.isWhitespace() }) {
                    if (text.isNotEmpty()) {
                        segments += Segment.Text(text.toString())
                        text.clear()
                    }
                    segments += Segment.Mention(name, id)
                    i = end + 1
                    continue
                }
            }
            text.append("@[")
            i = nameStart
        }
        text.append(body, i, body.length)
        if (text.isNotEmpty()) segments += Segment.Text(text.toString())
        return segments
    }

    /** The storage form for a mention. Brackets in names would break the token, so they are dropped. */
    fun token(user: UserSummary): String = "@[${user.name.filter { it != '[' && it != ']' }}](${user.id})"
}
