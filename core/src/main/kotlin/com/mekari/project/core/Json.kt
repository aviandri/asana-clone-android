package com.mekari.project.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The BE's JSON: camelCase, unknown keys ignored, absent nullable fields are null and nulls are omitted. */
val MekariJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** ISO-8601 timestamps, with or without fractional seconds, `Z` or an offset. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("com.mekari.project.core.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                throw SerializationException("Expected an ISO-8601 timestamp, got \"$text\"")
            }
        }
    }
}

/** `"YYYY-MM-DD"`: a calendar day, never a UTC-midnight instant. */
object LocalDateSerializer : KSerializer<LocalDate> {
    private val pattern = Regex("""\d{4}-\d{2}-\d{2}""")
    override val descriptor = PrimitiveSerialDescriptor("com.mekari.project.core.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate {
        val text = decoder.decodeString()
        if (!pattern.matches(text)) throw SerializationException("Expected YYYY-MM-DD, got \"$text\"")
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            throw SerializationException("Expected a valid YYYY-MM-DD date, got \"$text\"")
        }
    }
}

/**
 * The BE's intersection types (`Task & {...}`) are one flat JSON object; the Kotlin type nests the
 * shared part. JSON only.
 */
internal abstract class FlatSerializer<T>(name: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.mekari.project.core.$name")

    abstract fun read(json: Json, obj: JsonObject): T
    abstract fun write(json: Json, value: T): JsonObject

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: throw SerializationException("${descriptor.serialName} is JSON-only")
        return read(input.json, input.decodeJsonElement().jsonObject)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("${descriptor.serialName} is JSON-only")
        output.encodeJsonElement(write(output.json, value))
    }

    protected fun merge(vararg parts: JsonObject): JsonObject = JsonObject(parts.fold(emptyMap()) { acc, part -> acc + part })
}
