package com.mekari.project.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * BE enums are OPEN: an unknown value decodes as itself instead of failing a whole list. Compare with
 * the constants; every `when` needs an `else` branch. `known` lists the constants, for pickers.
 */
interface OpenEnum {
    val raw: String
}

/** Serializes an open enum as its raw string. */
open class OpenEnumSerializer<T : OpenEnum>(name: String, private val make: (String) -> T) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor("com.mekari.project.core.$name", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.raw)
    override fun deserialize(decoder: Decoder): T = make(decoder.decodeString())
}

/** An unknown role is neither admin nor guest. */
@JvmInline
@Serializable(with = WorkspaceRole.Serializer::class)
value class WorkspaceRole(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<WorkspaceRole>("WorkspaceRole", ::WorkspaceRole)

    companion object {
        val ADMIN = WorkspaceRole("ADMIN")
        val MEMBER = WorkspaceRole("MEMBER")
        val GUEST = WorkspaceRole("GUEST")
        val known = listOf(ADMIN, MEMBER, GUEST)
    }
}

@JvmInline
@Serializable(with = ProjectRole.Serializer::class)
value class ProjectRole(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<ProjectRole>("ProjectRole", ::ProjectRole)

    companion object {
        val OWNER = ProjectRole("OWNER")
        val EDITOR = ProjectRole("EDITOR")
        val COMMENTER = ProjectRole("COMMENTER")
        val known = listOf(OWNER, EDITOR, COMMENTER)
    }
}

@JvmInline
@Serializable(with = ProjectPrivacy.Serializer::class)
value class ProjectPrivacy(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<ProjectPrivacy>("ProjectPrivacy", ::ProjectPrivacy)

    companion object {
        val PUBLIC = ProjectPrivacy("PUBLIC")
        val TEAM = ProjectPrivacy("TEAM")
        val PRIVATE = ProjectPrivacy("PRIVATE")
        val known = listOf(PUBLIC, TEAM, PRIVATE)
    }
}

/** An unknown view opens as a list. */
@JvmInline
@Serializable(with = ProjectView.Serializer::class)
value class ProjectView(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<ProjectView>("ProjectView", ::ProjectView)

    companion object {
        val LIST = ProjectView("LIST")
        val BOARD = ProjectView("BOARD")
        val CALENDAR = ProjectView("CALENDAR")
        val known = listOf(LIST, BOARD, CALENDAR)
    }
}

@JvmInline
@Serializable(with = Priority.Serializer::class)
value class Priority(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<Priority>("Priority", ::Priority)

    companion object {
        val NONE = Priority("NONE")
        val LOW = Priority("LOW")
        val MEDIUM = Priority("MEDIUM")
        val HIGH = Priority("HIGH")
        val known = listOf(NONE, LOW, MEDIUM, HIGH)
    }
}

@JvmInline
@Serializable(with = NotificationType.Serializer::class)
value class NotificationType(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<NotificationType>("NotificationType", ::NotificationType)

    companion object {
        val ASSIGNED = NotificationType("ASSIGNED")
        val COMMENTED = NotificationType("COMMENTED")
        val COMPLETED = NotificationType("COMPLETED")
        val MENTIONED = NotificationType("MENTIONED")
        val known = listOf(ASSIGNED, COMMENTED, COMPLETED, MENTIONED)
    }
}

@JvmInline
@Serializable(with = ActivityType.Serializer::class)
value class ActivityType(override val raw: String) : OpenEnum {
    override fun toString() = raw

    object Serializer : OpenEnumSerializer<ActivityType>("ActivityType", ::ActivityType)

    companion object {
        val CREATED = ActivityType("CREATED")
        val COMPLETED = ActivityType("COMPLETED")
        val REOPENED = ActivityType("REOPENED")
        val ASSIGNED = ActivityType("ASSIGNED")
        val UNASSIGNED = ActivityType("UNASSIGNED")
        val DUE_DATE_CHANGED = ActivityType("DUE_DATE_CHANGED")
        val MOVED_SECTION = ActivityType("MOVED_SECTION")
        val RENAMED = ActivityType("RENAMED")
        val PRIORITY_CHANGED = ActivityType("PRIORITY_CHANGED")
        val DESCRIPTION_CHANGED = ActivityType("DESCRIPTION_CHANGED")

        /** BE §13, task-level only. `data`: `{attachmentId, kind, name}`. */
        val ATTACHMENT_ADDED = ActivityType("ATTACHMENT_ADDED")

        /** `data`: `{kind, name}`. */
        val ATTACHMENT_REMOVED = ActivityType("ATTACHMENT_REMOVED")
        val known = listOf(
            CREATED, COMPLETED, REOPENED, ASSIGNED, UNASSIGNED, DUE_DATE_CHANGED, MOVED_SECTION, RENAMED,
            PRIORITY_CHANGED, DESCRIPTION_CHANGED, ATTACHMENT_ADDED, ATTACHMENT_REMOVED
        )
    }
}
