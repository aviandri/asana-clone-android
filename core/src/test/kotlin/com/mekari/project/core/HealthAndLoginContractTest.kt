package com.mekari.project.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Talks to a real, fully seeded backend (CI `contract` job). Skipped unless MP_BASE_URL is set,
 * e.g. MP_BASE_URL=http://127.0.0.1:3000 (the server URL, without /api/v1). The session cookie is
 * handled by hand, as SPEC-ANDROID §3 requires: no CookieJar, `sid` from Set-Cookie, sent as Cookie.
 */
class HealthAndLoginContractTest {
    private lateinit var config: ServerConfiguration
    private val client = OkHttpClient()

    @Before
    fun requireBackend() {
        val raw = System.getenv("MP_BASE_URL").orEmpty()
        assumeTrue("MP_BASE_URL not set", raw.isNotBlank())
        config = ServerConfiguration.normalize(raw)!!
    }

    @Test
    fun healthIsOk() {
        client.newCall(Request.Builder().url(config.healthUrl).build()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun loginCookieAuthenticatesAuthMe() {
        val body = buildJsonObject {
            put("email", "alice@example.com")
            put("password", "password123")
        }.toString().toRequestBody("application/json".toMediaType())
        val sid = client.newCall(Request.Builder().url(config.apiBaseUrl + "auth/login").post(body).build())
            .execute().use { response ->
                assertEquals(200, response.code)
                response.headers("Set-Cookie")
                    .firstNotNullOfOrNull { Regex("""(?:^|;\s*)sid=([^;]+)""").find(it)?.groupValues?.get(1) }
            }
        assertNotNull("login response sets the sid cookie", sid)

        val me = Request.Builder().url(config.apiBaseUrl + "auth/me").header("Cookie", "sid=$sid").build()
        client.newCall(me).execute().use { response ->
            assertEquals(200, response.code)
            // BE SPEC §5: Me = User & { currentWorkspace, workspaces }, so email is top-level.
            val me = Json.parseToJsonElement(response.body!!.string()).jsonObject
            assertEquals("alice@example.com", me["email"]?.jsonPrimitive?.content)
        }

        client.newCall(Request.Builder().url(config.apiBaseUrl + "auth/me").build()).execute().use {
            assertEquals(401, it.code)
        }
    }
}
