package com.mekari.project.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * [LiveApi] against a real, fully seeded backend (CI `contract` job). Skipped unless MP_BASE_URL is
 * set, e.g. MP_BASE_URL=http://127.0.0.1:3000 (the server URL, without /api/v1).
 */
class HealthAndLoginContractTest {
    private lateinit var server: String

    @Before
    fun requireBackend() {
        val raw = System.getenv("MP_BASE_URL").orEmpty()
        assumeTrue("MP_BASE_URL not set", raw.isNotBlank())
        server = ServerUrl.normalize(raw)!!
    }

    @Test
    fun healthIsOk() = runTest {
        LiveApi(server, InMemoryTokenStore()).health()
    }

    @Test
    fun loginCookieAuthenticatesAuthMe() = runTest {
        val tokens = InMemoryTokenStore()
        val api = LiveApi(server, tokens)
        val me = api.login("alice@example.com", "password123")
        assertEquals("alice@example.com", me.user.email)
        assertNotNull("login stores the sid cookie", tokens.load())
        assertEquals("alice@example.com", api.me().user.email)

        val anonymous = LiveApi(server, InMemoryTokenStore())
        try {
            anonymous.me()
            fail("expected 401")
        } catch (e: ApiError) {
            assertEquals(401, e.status)
        }
        assertNull(anonymous.hasSession.takeIf { it })
    }
}
