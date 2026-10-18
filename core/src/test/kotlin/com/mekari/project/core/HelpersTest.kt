package com.mekari.project.core

import java.time.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    @Test
    fun sessionCookieFromRealisticHeaders() {
        val expires = "Expires=Wed, 21 Oct 2026 07:28:00 GMT"
        assertEquals(
            "abc.def",
            SessionCookie.token(headersOf("Set-Cookie", "sid=abc.def; Path=/; HttpOnly; SameSite=Lax"))
        )
        // Several Set-Cookie headers, sid not first.
        assertEquals(
            "tok",
            SessionCookie.token(
                headersOf("Set-Cookie", "theme=dark; Path=/", "Set-Cookie", "sid=tok; $expires; HttpOnly")
            )
        )
        // Folded into one header, with the comma inside Expires.
        assertEquals("tok", SessionCookie.token("theme=dark; $expires, sid=tok; Path=/"))
        // Attributes first is not a cookie name.
        assertNull(SessionCookie.token("Path=/; sid2=x"))
        // A clearing cookie is not a session, but a later sid in the same header is.
        assertNull(SessionCookie.token("sid=; Max-Age=0"))
        assertEquals("fresh", SessionCookie.token("sid=; Max-Age=0, sid=fresh; Path=/"))
        assertNull(SessionCookie.token(headersOf()))
    }

    @Test
    fun serverUrls() {
        assertEquals("https://project.mekari.io", ServerUrl.normalize("  project.mekari.io/ "))
        assertEquals("http://127.0.0.1:3000", ServerUrl.normalize("http://127.0.0.1:3000/"))
        assertEquals("https://host/sub/path", ServerUrl.normalize("HTTPS://host/sub/path//"))
        assertEquals("https://host:8443", ServerUrl.normalize("host:8443"))
        for (bad in listOf("", "   ", "ftp://host", "http://", "host name", "https://host?x=1", "https://host#frag")) {
            assertNull("accepted \"$bad\"", ServerUrl.normalize(bad))
        }
    }

    @Test
    fun deepLinksAcceptOnlyOneUuidSegment() {
        val id = "50000000-0000-4000-8000-000000000001"
        assertEquals(DeepLink.Task(id), DeepLink.parse("mekariproject://task/$id"))
        assertEquals(DeepLink.Project(id), DeepLink.parse("MekariProject://PROJECT/${id.uppercase()}"))
        assertEquals("mekariproject://task/$id", DeepLink.Task(id).uri)
        for (bad in listOf(
            "mekariproject://task/..%2Fusers",
            "mekariproject://task/$id/extra",
            "mekariproject://task/not-a-uuid",
            "mekariproject://user/$id",
            "https://task/$id",
            "mekariproject://task/$id?x=1",
            "mekariproject://task",
            "::not a uri"
        )) {
            assertNull("accepted $bad", DeepLink.parse(bad))
        }
    }

    @Test
    fun webLinksCarryTheWorkspace() {
        val now = Instant.EPOCH
        val creator = UserSummary("u1", "A", "a@x", "#000")
        val projectTask =
            TaskItem(
                id = "t1",
                workspaceId = "w1",
                projectId = "p1",
                title = "x",
                creator = creator,
                createdAt = now,
                updatedAt = now
            )
        assertEquals("https://h/projects/p1?task=t1&workspace=w1", WebLinks.task(projectTask, "https://h", "w1"))
        assertEquals(
            "https://h/my-tasks?task=t1&workspace=w1",
            WebLinks.task(projectTask.copy(projectId = null), "https://h", "w1")
        )
        assertEquals("https://h/sub/projects/p1?workspace=w1", WebLinks.project("p1", "https://h/sub", "w1"))
        assertEquals("https://h/workspace/settings?workspace=w1", WebLinks.workspaceSettings("https://h", "w1"))
        assertEquals(
            "https://h/inbox?filter=a%20b&workspace=w1",
            WebLinks.url("https://h", "/inbox?filter=a%20b", "w1")
        )
        assertEquals("https://h/login", WebLinks.acceptInvite("https://h"))
    }

    @Test
    fun reorder() {
        assertEquals(1000.0, Reorder.position(null, null), 0.0)
        assertEquals(4000.0, Reorder.position(3000.0, null), 0.0)
        assertEquals(500.0, Reorder.position(null, 1000.0), 0.0)
        assertEquals(-1000.0, Reorder.position(null, 0.0), 0.0)
        assertEquals(1500.0, Reorder.position(1000.0, 2000.0), 0.0)
        assertEquals(1500.0, Reorder.position(insertingAt = 1, positions = listOf(1000.0, 2000.0)), 0.0)
        assertEquals(3000.0, Reorder.position(insertingAt = 2, positions = listOf(1000.0, 2000.0)), 0.0)
        assertTrue(Reorder.needsRenumber(1.0, 1.0 + 1e-7))
        assertFalse(Reorder.needsRenumber(1.0, 2.0))
        assertTrue(Reorder.needsRenumber(null, 1e-7))
        assertFalse(Reorder.needsRenumber(5.0, null))
        assertEquals(listOf(1000.0, 2000.0, 3000.0), Reorder.renumbered(3))
    }

    @Test
    fun mentions() {
        val bob = UserSummary("u2", "Bob [Ops] Smith", "b@x", "#000")
        assertEquals("@[Bob Ops Smith](u2)", Mentions.token(bob))
        assertEquals(
            listOf(
                Mentions.Segment.Mention("A", "1"),
                Mentions.Segment.Text(" and "),
                Mentions.Segment.Mention("B", "2")
            ),
            Mentions.parse("@[A](1) and @[B](2)")
        )
        // Malformed tokens stay text.
        assertEquals(
            listOf(Mentions.Segment.Text("@[A](has space) @[](x) @[B](")),
            Mentions.parse("@[A](has space) @[](x) @[B](")
        )
        assertEquals(emptyList<Mentions.Segment>(), Mentions.parse(""))
    }
}
