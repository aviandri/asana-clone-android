package com.mekari.project.core

import app.cash.turbine.test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** LiveApi's per-request rules over a real OkHttp stack (MockWebServer). */
class LiveApiTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val tokens = InMemoryTokenStore()
    private lateinit var api: LiveApi

    private val user = """{"id":"u1","name":"Alice","email":"alice@example.com","avatarColor":"#e8384f"}"""
    private val me = """{"id":"u1","name":"Alice","email":"alice@example.com","avatarColor":"#e8384f","role":"MEMBER",
        "isActive":true,"createdAt":"2026-10-01T02:00:00Z","currentWorkspace":null,"workspaces":[]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api =
            LiveApi(server.url("/").toString().trimEnd('/'), tokens, client, decodeDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, errorCode: String = "X") =
        json("""{"error":{"code":"$errorCode","message":"m"}}""", code)

    @Test
    fun loginStoresTheSidCookieAndLaterRequestsSendIt() = runBlocking {
        server.enqueue(
            json(
                me
            ).addHeader(
                "Set-Cookie",
                "theme=dark; Path=/"
            ).addHeader("Set-Cookie", "sid=tok.123; Path=/; HttpOnly; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
        )
        server.enqueue(json(me))
        api.login("alice@example.com", "pw")
        assertEquals("tok.123", tokens.load())
        val login = server.takeRequest()
        assertEquals("/api/v1/auth/login", login.path)
        assertEquals("""{"email":"alice@example.com","password":"pw"}""", login.body.readUtf8())
        assertNull("no CookieJar, no stray cookies", login.getHeader("Cookie"))

        api.me()
        assertEquals("sid=tok.123", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun loginWithoutACookieIsAnError() = runBlocking {
        server.enqueue(json(me))
        try {
            api.login("a", "b")
            fail("expected an error")
        } catch (e: ApiError) {
            assertEquals(ApiError.Code.DECODING, e.code)
        }
        assertNull(tokens.load())
    }

    @Test
    fun theWorkspaceHeaderSkipsAuthAndWorkspaces() = runBlocking {
        repeat(4) { server.enqueue(json("""{"data":[]}""")) }
        server.enqueue(json(me))
        server.enqueue(
            json(
                """{"id":"w2","name":"W","isOrganization":false,"color":"#000","myRole":"MEMBER","memberCount":1,"createdAt":"2026-10-01T02:00:00Z"}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200))
        api.setWorkspace("w1")
        api.projects()
        api.myTasks(includeCompleted = false)
        api.users("al")
        api.search("fix & ship c++")
        api.me()
        api.switchWorkspace("w2")
        api.health()

        val projects = server.takeRequest()
        assertEquals("w1", projects.getHeader(LiveApi.WORKSPACE_HEADER))
        assertEquals("/api/v1/projects?archived=false", projects.path)
        assertEquals("/api/v1/me/tasks?completed=false", server.takeRequest().path)
        assertEquals("/api/v1/users?q=al", server.takeRequest().path)
        assertEquals("/api/v1/search?q=fix%20%26%20ship%20c%2B%2B", server.takeRequest().path)
        val meRequest = server.takeRequest()
        assertEquals("/api/v1/auth/me", meRequest.path)
        assertNull(meRequest.getHeader(LiveApi.WORKSPACE_HEADER))
        val switch = server.takeRequest()
        assertEquals("/api/v1/workspaces/w2/switch", switch.path)
        assertNull(switch.getHeader(LiveApi.WORKSPACE_HEADER))
        assertEquals("/health", server.takeRequest().path)
    }

    @Test
    fun a401ForTheStoredTokenEndsTheSession() = runBlocking {
        tokens.save("current")
        server.enqueue(error(401, "UNAUTHORIZED"))
        api.events.test {
            expectApiError(401) { api.me() }
            assertEquals(ApiEvent.Unauthorized, awaitItem())
        }
        assertNull(tokens.load())
    }

    @Test
    fun aLate401ForAnOlderTokenIsIgnored() = runBlocking {
        // Sent with "old"; by the time the 401 arrives, a new sign-in stored "new".
        val swapping = object : TokenStore {
            var reads = 0
            var token: String? = "old"
            override fun load(): String? = token.also { if (++reads == 1) token = "new" }
            override fun save(token: String) {
                this.token = token
            }
            override fun clear() {
                token = null
            }
        }
        val late = LiveApi(server.url("/").toString(), swapping, client, decodeDispatcher = Dispatchers.Unconfined)
        server.enqueue(error(401, "UNAUTHORIZED"))
        late.events.test {
            expectApiError(401) { late.notifications() }
            expectNoEvents()
        }
        assertEquals("sid=old", server.takeRequest().getHeader("Cookie"))
        assertEquals("new", swapping.token)
    }

    @Test
    fun loginAndLogoutNeverEmitUnauthorized() = runBlocking {
        tokens.save("t")
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(error(401, "UNAUTHORIZED"))
        api.events.test {
            expectApiError(401) { api.login("a", "wrong") }
            api.logout()
            expectNoEvents()
        }
        assertNull("logout always clears the token", tokens.load())
    }

    @Test
    fun scopedCollection404sReportLostAccessOthersDoNot() = runBlocking {
        api.setWorkspace("w1")
        repeat(4) { server.enqueue(error(404, "NOT_FOUND")) }
        api.events.test {
            expectApiError(404) { api.projects() }
            assertEquals(ApiEvent.WorkspaceLost("w1"), awaitItem())
            expectApiError(404) { api.createTask(NewTask(title = "x")) }
            assertEquals(ApiEvent.WorkspaceLost("w1"), awaitItem())
            expectApiError(404) { api.task("t1") } // an id-addressed 404 is about the task
            expectApiError(404) { api.me() } // no header on /auth
            expectNoEvents()
        }
        api.setWorkspace(null)
        server.enqueue(error(404, "NOT_FOUND"))
        api.events.test {
            expectApiError(404) { api.projects() } // no header, nothing lost
            expectNoEvents()
        }
    }

    @Test
    fun errorsAndDecodingFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val gateway = expectApiError(502) { api.me() }
        assertEquals(ApiError.Code("HTTP 502"), gateway.code)

        server.enqueue(
            json(
                """{"data":[{"id":"t1","workspaceId":"w1","title":"x","creator":$user,"dueDate":"2026-10-18T00:00:00Z",
            "createdAt":"2026-10-01T02:00:00Z","updatedAt":"2026-10-01T02:00:00Z"}]}"""
            )
        )
        val badDate = expectApiError(null) { api.myTasks(includeCompleted = true) }
        assertEquals(ApiError.Code.DECODING, badDate.code)
        assertTrue(badDate.message, badDate.message.contains("Expected YYYY-MM-DD, got \"2026-10-18T00:00:00Z\""))

        // kotlinx's own errors carry the JSON path.
        server.enqueue(
            json(
                """{"data":[{"id":"t1","workspaceId":"w1","title":"x","creator":$user,"position":"high",
            "createdAt":"2026-10-01T02:00:00Z","updatedAt":"2026-10-01T02:00:00Z"}]}"""
            )
        )
        val badType = expectApiError(null) { api.myTasks(includeCompleted = true) }
        assertTrue(badType.message, badType.message.contains("$.data[0].position"))
        assertTrue("no JSON input echoed", !badType.message.contains("JSON input"))

        // Nothing listening: a transport error, not a crash or a decoding error.
        server.shutdown()
        val down = expectApiError(null) { api.me() }
        assertEquals(down.message, ApiError.Code.TRANSPORT, down.code)
    }

    @Test
    fun unsafeIdsNeverReachAPath() = runBlocking {
        for (id in listOf("..", "a/b", "x y", "")) {
            assertEquals(ApiError.Code.NOT_FOUND, expectApiError(null) { api.task(id) }.code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { api.me() }
        server.takeRequest(5, TimeUnit.SECONDS) ?: fail("request never arrived")
        call.cancel()
        try {
            call.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        withTimeout(5_000) {
            while (client.dispatcher.runningCallsCount() > 0) delay(10)
        }
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test
    fun aCallbackAfterCancellationNeverResumes() = runBlocking {
        val call = ManualCall()
        val waiting = async(Dispatchers.Unconfined) { call.await() }
        assertTrue(call.callback != null)
        waiting.cancel()
        assertTrue("cancelling the coroutine cancels the call", call.isCanceled())
        // OkHttp may still deliver a response (or a failure) afterwards: both must be ignored.
        call.callback!!.onResponse(call, call.response())
        call.callback!!.onFailure(call, IOException("Canceled"))
        try {
            waiting.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue("the late response was closed", call.bodyClosed)
    }

    private suspend fun expectApiError(status: Int?, block: suspend () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiError) {
            if (status != null) assertEquals(status, e.status)
            return e
        }
        fail("expected an ApiError")
        throw AssertionError()
    }

    /** A Call whose callback the test fires by hand. */
    private class ManualCall : Call {
        private val request = Request.Builder().url("http://localhost/").build()
        var callback: Callback? = null
        private var canceled = false
        var bodyClosed = false

        fun response(): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    object : okhttp3.ResponseBody() {
                        private val delegate = "{}".toResponseBody()
                        override fun contentType() = delegate.contentType()
                        override fun contentLength() = delegate.contentLength()
                        override fun source() = delegate.source()
                        override fun close() {
                            bodyClosed = true
                            delegate.close()
                        }
                    }
                )
                .build()

        override fun request() = request
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun cancel() {
            canceled = true
        }
        override fun isExecuted() = callback != null
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ManualCall()
    }
}
