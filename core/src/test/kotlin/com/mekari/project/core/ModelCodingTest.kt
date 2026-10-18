package com.mekari.project.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Decoding BE JSON (recorded shapes, BE SPEC §5) and encoding request bodies. */
class ModelCodingTest {
    private val json = MekariJson

    private val user = """{"id":"u1","name":"Alice Johnson","email":"alice@example.com","avatarColor":"#e8384f"}"""
    private val workspace = """{"id":"w1","name":"Mekari","isOrganization":true,"domain":"example.com","color":"#4186e0",
        "myRole":"MEMBER","memberCount":3,"createdAt":"2026-10-01T02:00:00.000Z"}"""

    private fun task(id: String, extra: String = "") =
        """{"id":"$id","workspaceId":"w1","projectId":"p1","sectionId":"s1",
        "parentId":null,"title":"Fix login","description":null,"assignee":$user,"creator":$user,"dueDate":"2026-10-18",
        "startDate":null,"priority":"HIGH","completed":false,"completedAt":null,"position":1500.5,"subtaskCount":2,
        "completedSubtaskCount":1,"commentCount":3,"project":{"id":"p1","name":"Mobile","color":"#e0257f"},
        "createdAt":"2026-10-01T02:00:00Z","updatedAt":"2026-10-02T03:04:05.123Z"$extra}"""

    @Test
    fun meIsFlat() {
        val me = json.decodeFromString(
            Me.serializer(),
            """{"id":"u1","name":"Alice Johnson","email":"alice@example.com","avatarColor":"#e8384f","role":"MEMBER",
            "isActive":true,"createdAt":"2026-10-01T02:00:00.000Z","currentWorkspace":$workspace,"workspaces":[$workspace]}"""
        )
        assertEquals("alice@example.com", me.user.email)
        assertEquals("w1", me.currentWorkspace?.id)
        assertEquals(WorkspaceRole.MEMBER, me.workspaces.single().myRole)
        // Round trip stays flat.
        val encoded = json.parseToJsonElement(json.encodeToString(Me.serializer(), me)).jsonObject
        assertEquals("alice@example.com", encoded["email"]?.jsonPrimitive?.content)
        assertEquals(me, json.decodeFromString(Me.serializer(), encoded.toString()))
    }

    @Test
    fun meWithoutAWorkspace() {
        val me = json.decodeFromString(
            Me.serializer(),
            """{"id":"u1","name":"A","email":"a@x","avatarColor":"#000","role":"MEMBER","isActive":true,
            "createdAt":"2026-10-01T02:00:00Z","currentWorkspace":null,"workspaces":[]}"""
        )
        assertNull(me.currentWorkspace)
    }

    @Test
    fun taskDetailIsFlatAndDatesAreCalendarDays() {
        val detail = json.decodeFromString(
            TaskDetail.serializer(),
            task("t1", ""","followers":[$user],"subtasks":[${task("t2")}],"parent":null""")
        )
        assertEquals("t1", detail.id)
        assertEquals(LocalDate.of(2026, 10, 18), detail.task.dueDate)
        assertEquals(Priority.HIGH, detail.task.priority)
        assertEquals(1500.5, detail.task.position, 0.0)
        assertEquals(Instant.parse("2026-10-02T03:04:05.123Z"), detail.task.updatedAt)
        assertEquals("t2", detail.subtasks.single().id)
        assertEquals("Mobile", detail.task.project?.name)
    }

    @Test
    fun projectDetailSortsSectionsAndKeepsAnUnknownView() {
        val detail = json.decodeFromString(
            ProjectDetail.serializer(),
            """{"id":"p1","workspaceId":"w1","teamId":"t1","name":"Mobile","description":null,"color":"#e0257f",
            "privacy":"PUBLIC","archived":false,"defaultView":"TIMELINE","owner":$user,"myRole":"EDITOR",
            "createdAt":"2026-10-01T02:00:00Z","updatedAt":"2026-10-01T02:00:00Z",
            "sections":[{"id":"s2","projectId":"p1","name":"B","position":2000},{"id":"s1","projectId":"p1","name":"A","position":1000}],
            "members":[{"user":$user,"role":"OWNER"}],"team":{"id":"t1","name":"Engineering"}}"""
        )
        assertEquals(listOf("s1", "s2"), detail.sections.map { it.id })
        assertEquals(ProjectView("TIMELINE"), detail.project.defaultView)
        assertFalse(detail.project.defaultView in ProjectView.known)
        assertEquals(ProjectRole.EDITOR, detail.project.myRole)
    }

    @Test
    fun unknownEnumValuesDoNotFailAList() {
        val users = json.decodeFromString(
            ListSerializer(PickableUser.serializer()),
            """[{"id":"u1","name":"A","email":"a@x","avatarColor":"#000","role":"OBSERVER"},
            {"id":"u2","name":"G","email":"g@x","avatarColor":"#000","role":"GUEST"}]"""
        )
        assertEquals(WorkspaceRole("OBSERVER"), users[0].role)
        assertFalse(users[0].isGuest)
        assertTrue(users[1].isGuest)
        assertEquals("A", users[0].name)
        // Encoding writes the raw value back.
        assertTrue(json.encodeToString(PickableUser.serializer(), users[0]).contains("\"role\":\"OBSERVER\""))
    }

    @Test
    fun storyFeedSkipsUnknownKindsButNotMalformedKnownOnes() {
        val feed = """{"data":[
            {"kind":"activity","id":"a1","actor":$user,"type":"CREATED","data":null,"createdAt":"2026-10-01T02:00:00Z"},
            {"kind":"attachment","id":"x1","createdAt":"2026-10-01T02:01:00Z"},
            {"kind":"comment","id":"c1","author":$user,"body":"Hi @[Bob Smith](u2)!","createdAt":"2026-10-01T02:02:00Z","editedAt":null},
            {"kind":"activity","id":"a2","actor":$user,"type":"ARCHIVED","data":{"x":1},"createdAt":"2026-10-01T02:03:00Z"}
        ]}"""
        val stories = decodeStoryFeed(json, json.parseToJsonElement(feed))
        assertEquals(listOf("a1", "c1", "a2"), stories.map { it.id })
        assertEquals(listOf("activity-a1", "comment-c1", "activity-a2"), stories.map { it.key })
        assertEquals(JsonNull, (stories[0] as Activity).data)
        assertEquals(ActivityType("ARCHIVED"), (stories[2] as Activity).type)
        assertEquals(
            listOf(
                Mentions.Segment.Text("Hi "),
                Mentions.Segment.Mention("Bob Smith", "u2"),
                Mentions.Segment.Text("!")
            ),
            (stories[1] as Comment).segments
        )
        try {
            decodeStoryFeed(json, json.parseToJsonElement("""{"data":[{"kind":"comment","id":"c1"}]}"""))
            fail("a malformed comment must fail")
        } catch (_: SerializationException) {
        }
    }

    @Test
    fun aCommentDecodesWithoutKind() {
        val comment = json.decodeFromString(
            CommentSerializer,
            """{"id":"c1","author":$user,"body":"x","createdAt":"2026-10-01T02:02:00Z","editedAt":"2026-10-01T02:05:00+07:00"}"""
        )
        assertEquals(Instant.parse("2026-09-30T19:05:00Z"), comment.editedAt)
    }

    @Test
    fun datesAreStrict() {
        for (bad in listOf("\"2026-10-18T00:00:00Z\"", "\"2026-13-01\"", "\"18/10/2026\"")) {
            try {
                json.decodeFromString(LocalDateSerializer, bad)
                fail("accepted $bad")
            } catch (_: SerializationException) {
            }
        }
        assertEquals(
            Instant.parse("2026-10-01T02:00:00Z"),
            json.decodeFromString(InstantSerializer, "\"2026-10-01T02:00:00Z\"")
        )
        assertEquals(
            Instant.parse("2026-10-01T02:00:00.5Z"),
            json.decodeFromString(InstantSerializer, "\"2026-10-01T09:00:00.5+07:00\"")
        )
    }

    @Test
    fun taskChangesAreTriState() {
        val body = TaskChanges(
            title = "New",
            assigneeId = Patch.Clear,
            dueDate = Patch.Set(LocalDate.of(2026, 10, 19)),
            completed = true
        ).toJson()
        assertEquals("""{"title":"New","assigneeId":null,"dueDate":"2026-10-19","completed":true}""", body.toString())
        assertEquals("{}", TaskChanges().toJson().toString())
        assertTrue(TaskChanges().isEmpty)
        assertEquals(
            """{"sectionId":"s2","position":1250.0}""",
            TaskChanges(sectionId = Patch.Set("s2"), position = 1250.0).toJson().toString()
        )
    }

    @Test
    fun newTaskOmitsNulls() {
        val body = json.encodeToString(
            NewTask.serializer(),
            NewTask(title = "X", assigneeId = "u1", dueDate = LocalDate.of(2026, 10, 18))
        )
        assertEquals("""{"title":"X","assigneeId":"u1","dueDate":"2026-10-18"}""", body)
    }

    @Test
    fun appliedShowsAnEditLocally() {
        val base = json.decodeFromString(TaskItem.serializer(), task("t1"))
        val bob = UserSummary("u2", "Bob", "b@x", "#000")
        val now = Instant.parse("2026-10-18T05:00:00Z")
        val shown = TaskChanges(
            assigneeId = Patch.Set("u2"),
            completed = true,
            sectionId = Patch.Clear
        ).applied(base, Patch.Set(bob), now)
        assertEquals(bob, shown.assignee)
        assertTrue(shown.completed)
        assertEquals(now, shown.completedAt)
        assertNull(shown.sectionId)
        // Without a resolved person the row keeps the old assignee until the server answers.
        assertEquals(base.assignee, TaskChanges(assigneeId = Patch.Set("u2")).applied(base).assignee)
        // Re-opening clears completedAt.
        assertNull(TaskChanges(completed = false).applied(shown).completedAt)
    }

    @Test
    fun errorEnvelopes() {
        val validation = ApiError.from(
            400,
            """{"error":{"code":"VALIDATION","message":"Invalid input","details":[{"path":["assigneeId"],"message":"Not a member"},
            {"path":["items",0,"title"],"message":"Required"}]}}"""
        )
        assertEquals(ApiError.Code.VALIDATION, validation.code)
        assertEquals("Not a member", validation.issue("assigneeId")?.message)
        assertEquals(listOf("items", "0", "title"), validation.issues[1].path)

        val noWorkspace = ApiError.from(
            403,
            """{"error":{"code":"FORBIDDEN","message":"No workspace","details":[{"path":["workspace"],"message":"NO_WORKSPACE"}]}}"""
        )
        assertTrue(noWorkspace.isNoWorkspace)

        val other = ApiError.from(
            418,
            """{"error":{"code":"TEAPOT","message":"Short and stout","details":{"not":"an array"}}}"""
        )
        assertEquals(ApiError.Code("TEAPOT"), other.code)
        assertTrue(other.issues.isEmpty())

        val html = ApiError.from(502, "<html>Bad gateway</html>")
        assertEquals(ApiError.Code("HTTP 502"), html.code)
        assertEquals(502, html.status)
    }
}
