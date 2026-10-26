package com.mekari.project.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [MekariApi] over HTTP (SPEC-ANDROID §3). One `send()` applies every per-request rule:
 * - the session cookie is handled by hand (the client has no CookieJar): `sid` is read from the login
 *   response's `Set-Cookie`, kept in the [TokenStore], and sent as `Cookie: sid=<token>`;
 * - `X-Workspace-Id` goes on every request except `/auth/…`, `/health` and `/workspaces…`;
 * - a 401 for the token that is still stored clears it and emits [ApiEvent.Unauthorized]; a late 401
 *   for an older token is ignored; login and logout never emit;
 * - a 404 from a scoped collection endpoint while the header was set emits [ApiEvent.WorkspaceLost];
 * - redirects are never followed (OkHttp would carry the hand-set Cookie, and re-post a login, to another
 *   host): a 3xx is an error;
 * - `/auth/login` never carries a cookie, and logout sends exactly the session it ends.
 */
class LiveApi(
    serverUrl: String,
    private val tokens: TokenStore,
    client: OkHttpClient = defaultClient(),
    private val json: Json = MekariJson,
    /** Where response bodies are decoded (off the main thread). */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : MekariApi {
    private val server: HttpUrl = serverUrl.toHttpUrl()

    /** Whatever client is passed, it never follows a redirect. */
    private val client: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * Uploads and downloads: the server may sit quiet for a while after the last byte (storing, thumbnails), so a
     * longer read timeout; never retried by OkHttp (a retried upload would be a duplicate).
     */
    private val transferClient: OkHttpClient = this.client.newBuilder()
        .readTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile private var workspaceId: String? = null
    private val eventChannel = Channel<ApiEvent>(Channel.UNLIMITED)

    override val events: Flow<ApiEvent> = eventChannel.receiveAsFlow()

    override suspend fun setWorkspace(id: String?) {
        workspaceId = id
    }

    override val hasSession: Boolean get() = tokens.load() != null

    // Health & auth

    override suspend fun health() {
        val url = server.newBuilder().addPathSegment("health").build()
        val raw = perform(Request.Builder().url(url).get().build())
        if (raw.code !in 200..299) {
            throw ApiError(ApiError.Code("HTTP ${raw.code}"), raw.code, "Not a Mekari Project server")
        }
    }

    override suspend fun login(email: String, password: String): Me {
        val raw = send(
            Method.POST,
            "/auth/login",
            body = buildJsonObject {
                put("email", email)
                put("password", password)
            },
            // A stored session (another account's, maybe another server's) has no business here.
            token = { null }
        )
        val token =
            SessionCookie.token(raw.headers) ?: throw ApiError.decoding("no session cookie in the login response")
        // Decoded first: a response we can't read must not leave a half session behind.
        val me = decode(Me.serializer(), raw, "Me")
        tokens.save(token)
        return me
    }

    /**
     * Ends the stored session locally FIRST, then tells the server with that captured token. A sign-in that
     * lands while this request hangs (up to the timeouts) keeps its new token, and this request never carries
     * the next session's cookie.
     */
    override suspend fun logout() {
        val ending = tokens.load() ?: return
        tokens.clear()
        try {
            send(Method.POST, "/auth/logout", token = { ending })
        } catch (_: ApiError) {
            // Best effort: the session is gone here either way.
        }
    }

    override suspend fun me(): Me = get("/auth/me", Me.serializer())

    // Workspaces & users

    override suspend fun switchWorkspace(id: String): Workspace =
        call(Method.POST, "/workspaces/${id(id)}/switch", Workspace.serializer())

    override suspend fun users(query: String?): List<PickableUser> =
        list("/users", PickableUser.serializer(), mapOf("q" to query))

    // Projects & sections

    override suspend fun projects(archived: Boolean): List<Project> =
        list("/projects", Project.serializer(), mapOf("archived" to archived.toString()))

    override suspend fun project(id: String): ProjectDetail = get("/projects/${id(id)}", ProjectDetail.serializer())

    override suspend fun projectTasks(projectId: String, completed: Boolean?): List<TaskItem> =
        list("/projects/${id(projectId)}/tasks", TaskItem.serializer(), mapOf("completed" to completed?.toString()))

    override suspend fun createSection(projectId: String, name: String, position: Double?): Section = call(
        Method.POST,
        "/projects/${id(projectId)}/sections",
        Section.serializer(),
        buildJsonObject {
            put("name", name)
            position?.let { put("position", it) }
        }
    )

    override suspend fun updateSection(id: String, changes: SectionChanges): Section =
        call(Method.PATCH, "/sections/${id(id)}", Section.serializer(), json.encodeToJsonElement(changes))

    override suspend fun deleteSection(id: String) {
        send(Method.DELETE, "/sections/${id(id)}")
    }

    // Tasks

    override suspend fun myTasks(includeCompleted: Boolean): List<TaskItem> =
        list("/me/tasks", TaskItem.serializer(), mapOf("completed" to if (includeCompleted) null else "false"))

    override suspend fun task(id: String): TaskDetail = get("/tasks/${id(id)}", TaskDetail.serializer())

    override suspend fun createTask(task: NewTask): TaskDetail =
        call(Method.POST, "/tasks", TaskDetail.serializer(), json.encodeToJsonElement(task))

    override suspend fun updateTask(id: String, changes: TaskChanges): TaskDetail =
        call(Method.PATCH, "/tasks/${id(id)}", TaskDetail.serializer(), changes.toJson(json))

    override suspend fun deleteTask(id: String) {
        send(Method.DELETE, "/tasks/${id(id)}")
    }

    override suspend fun addFollower(taskId: String, userId: String): TaskDetail = call(
        Method.POST,
        "/tasks/${id(taskId)}/followers",
        TaskDetail.serializer(),
        buildJsonObject { put("userId", userId) }
    )

    override suspend fun removeFollower(taskId: String, userId: String): TaskDetail =
        call(Method.DELETE, "/tasks/${id(taskId)}/followers/${id(userId)}", TaskDetail.serializer())

    // Stories

    override suspend fun stories(taskId: String): List<Story> {
        val raw = send(Method.GET, "/tasks/${id(taskId)}/stories")
        return decodeWith(raw, "Story feed") { decodeStoryFeed(json, json.parseToJsonElement(it)) }
    }

    override suspend fun addComment(taskId: String, body: String, attachmentIds: List<String>): Comment = call(
        Method.POST,
        "/tasks/${id(taskId)}/comments",
        CommentSerializer,
        buildJsonObject {
            put("body", body)
            // Without attachments the body is the v1 shape, so older servers keep working.
            if (attachmentIds.isNotEmpty()) put("attachmentIds", JsonArray(attachmentIds.map(::JsonPrimitive)))
        }
    )

    override suspend fun editComment(id: String, body: String): Comment =
        call(Method.PATCH, "/comments/${id(id)}", CommentSerializer, buildJsonObject { put("body", body) })

    override suspend fun deleteComment(id: String) {
        send(Method.DELETE, "/comments/${id(id)}")
    }

    // Inbox & search

    override suspend fun notifications(unreadOnly: Boolean, limit: Int): InboxPage = get(
        "/notifications",
        InboxPage.serializer(),
        mapOf("unread" to if (unreadOnly) "true" else null, "limit" to limit.toString())
    )

    override suspend fun markRead(notificationId: String) {
        send(Method.POST, "/notifications/${id(notificationId)}/read")
    }

    override suspend fun markAllRead() {
        send(Method.POST, "/notifications/read-all")
    }

    override suspend fun search(query: String): SearchResults =
        get("/search", SearchResults.serializer(), mapOf("q" to query))

    // Attachments & links

    override suspend fun uploadAttachment(
        taskId: String,
        source: UploadSource,
        draft: Boolean,
        progress: (TransferProgress) -> Unit
    ): Attachment {
        val size = source.size
        if (size != null && size > AttachmentLimits.MAX_UPLOAD_BYTES) throw AttachmentLimits.tooLarge()
        // Readable at all? (A revoked Uri grant fails here, as a validation error rather than "offline".)
        withContext(Dispatchers.IO) {
            try {
                source.open().close()
            } catch (_: Exception) {
                throw ApiError(ApiError.Code.VALIDATION, null, "Can't read that file")
            }
        }
        val body = SourceBody(source, size, progress)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // OkHttp quotes the name as browsers do: UTF-8, with `"`, CR and LF escaped (%22, %0D, %0A). The BE
            // reads `filename` as UTF-8 and ignores `filename*`.
            .addFormDataPart("file", source.name, body)
            .build()
        val raw = try {
            send(
                Method.POST,
                "/tasks/${id(taskId)}/attachments",
                mapOf("draft" to if (draft) "true" else null),
                requestBody = multipart,
                http = transferClient
            )
        } catch (e: ApiError) {
            // The stream turned out longer than allowed: the body stopped it, which surfaces as a transport error.
            if (body.tooLarge) throw AttachmentLimits.tooLarge()
            throw e
        }
        return decode(Attachment.serializer(), raw, "Attachment")
    }

    override suspend fun addLink(taskId: String, url: String, title: String?, draft: Boolean): Attachment = call(
        Method.POST,
        "/tasks/${id(taskId)}/attachments/link",
        Attachment.serializer(),
        buildJsonObject {
            put("url", url)
            title?.let { put("title", it) }
            put("draft", draft)
        }
    )

    override suspend fun attachment(id: String): Attachment = get("/attachments/${id(id)}", Attachment.serializer())

    override suspend fun refreshLinkPreview(id: String): Attachment =
        call(Method.POST, "/attachments/${id(id)}/refresh-preview", Attachment.serializer())

    override suspend fun deleteAttachment(id: String) {
        send(Method.DELETE, "/attachments/${id(id)}")
    }

    override suspend fun download(
        path: String,
        destination: File,
        etag: String?,
        progress: (TransferProgress) -> Unit
    ): DownloadOutcome {
        val (request, sentToken) = bytesRequest(path, etag)
        currentCoroutineContext().ensureActive()
        val raw = try {
            transferClient.newCall(request).awaitDownload(destination, progress)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw ApiError.transport()
        }
        if (raw.code == 304) return DownloadOutcome.NotModified
        check(raw, Method.GET, path, sentToken, sentWorkspace = null)
        return DownloadOutcome.Downloaded(raw.headers["ETag"])
    }

    override suspend fun attachmentData(path: String): ByteArray {
        val (request, sentToken) = bytesRequest(path, null)
        currentCoroutineContext().ensureActive()
        val (raw, bytes) = try {
            client.newCall(request).awaitBytes()
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw ApiError.transport()
        }
        check(raw, Method.GET, path, sentToken, sentWorkspace = null)
        return bytes
    }

    /** A GET of a server-given attachment path: the cookie, no workspace header (ID-addressed, BE §13). */
    private fun bytesRequest(path: String, etag: String?): Pair<Request, String?> {
        val url =
            (server.toString().trimEnd('/') + path).takeIf { AttachmentLinks.isAttachmentPath(path) }?.toHttpUrlOrNull()
                ?: throw ApiError(ApiError.Code.VALIDATION, null, "Not an attachment address")
        val builder = Request.Builder().url(url).get().header("Accept", "*/*")
        etag?.let { builder.header("If-None-Match", it) }
        val sentToken = tokens.load()
        if (sentToken != null) builder.header("Cookie", "${SessionCookie.NAME}=$sentToken")
        return builder.build() to sentToken
    }

    // Plumbing

    internal enum class Method { GET, POST, PATCH, DELETE }

    internal class Raw(val code: Int, val headers: Headers, val body: String)

    private suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap()
    ): T =
        decode(deserializer, send(Method.GET, path, query), deserializer.descriptor.serialName.substringAfterLast('.'))

    private suspend fun <T> list(
        path: String,
        item: KSerializer<T>,
        query: Map<String, String?> = emptyMap()
    ): List<T> = decode(
        ListResponse.serializer(item),
        send(Method.GET, path, query),
        "list of ${item.descriptor.serialName.substringAfterLast('.')}"
    ).data

    private suspend fun <T> call(
        method: Method,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonElement? = null
    ): T = decode(
        deserializer,
        send(method, path, body = body),
        deserializer.descriptor.serialName.substringAfterLast('.')
    )

    private suspend fun send(
        method: Method,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
        /** The session to send: the stored one, read now, unless the caller pins it. */
        token: () -> String? = tokens::load,
        /** A non-JSON body (the multipart upload); wins over [body]. */
        requestBody: RequestBody? = null,
        http: OkHttpClient = client
    ): Raw {
        val url = server.newBuilder().addPathSegments("api/v1").apply {
            path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            query.toSortedMap().forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")

        @Suppress("NAME_SHADOWING")
        val requestBody = requestBody ?: body?.toString()?.toRequestBody(JSON_MEDIA)
        when (method) {
            Method.GET -> builder.get()
            Method.POST -> builder.post(requestBody ?: EMPTY_BODY)
            Method.PATCH -> builder.patch(requestBody ?: EMPTY_BODY)
            Method.DELETE -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
        }
        val sentToken = token()
        if (sentToken != null) builder.header("Cookie", "${SessionCookie.NAME}=$sentToken")
        val sentWorkspace = if (sendsWorkspaceHeader(path)) workspaceId else null
        if (sentWorkspace != null) builder.header(WORKSPACE_HEADER, sentWorkspace)

        val raw = perform(builder.build(), http)
        check(raw, method, path, sentToken, sentWorkspace)
        return raw
    }

    /**
     * Throws for redirects and errors: a 401 for the token still stored ends the session, a scoped collection's
     * 404 reports a lost workspace.
     */
    private fun check(raw: Raw, method: Method, path: String, sentToken: String?, sentWorkspace: String?) {
        if (raw.code in 200..299) return
        if (raw.code in 300..399) {
            throw ApiError(
                ApiError.Code("REDIRECT"),
                raw.code,
                "The server answered with a redirect (HTTP ${raw.code}). Check the server address, including https://."
            )
        }
        val error = ApiError.from(raw.code, raw.body)
        if (raw.code == 401) {
            // Only a 401 for the session we still hold ends it: a late answer to a request sent with an
            // older token (the poller, a request from before a re-login) must not sign out a fresh session.
            if (sentToken != null && path != "/auth/login" && path != "/auth/logout" && tokens.clearIf(sentToken)) {
                eventChannel.trySend(ApiEvent.Unauthorized)
            }
        } else if (raw.code == 404 && sentWorkspace != null && isScopedCollection(method, path)) {
            eventChannel.trySend(ApiEvent.WorkspaceLost(sentWorkspace))
        }
        throw error
    }

    /** Transport failures become [ApiError.transport]; cancellation stays `CancellationException`. */
    private suspend fun perform(request: Request, http: OkHttpClient = client): Raw {
        currentCoroutineContext().ensureActive()
        return try {
            http.newCall(request).await()
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw ApiError.transport()
        }
    }

    private suspend fun <T> decode(deserializer: DeserializationStrategy<T>, raw: Raw, what: String): T =
        decodeWith(raw, what) { json.decodeFromString(deserializer, it) }

    private suspend fun <T> decodeWith(raw: Raw, what: String, decode: (String) -> T): T =
        withContext(decodeDispatcher) {
            try {
                decode(raw.body)
            } catch (e: SerializationException) {
                throw ApiError.decoding("$what: ${describe(e)}")
            } catch (e: IllegalArgumentException) {
                throw ApiError.decoding("$what: ${describe(e)}")
            }
        }

    /** The failure and where (kotlinx's "at path: $.data[0].dueDate"), without echoing the JSON input. */
    private fun describe(e: Exception): String = e.message.orEmpty().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("JSON input") && !it.startsWith("Use 'ignoreUnknownKeys'") }
        .joinToString(" ")

    companion object {
        const val WORKSPACE_HEADER = "X-Workspace-Id"

        /** Idle timeout for uploads and downloads (other requests keep the client's 30 s). */
        const val TRANSFER_TIMEOUT_SECONDS = 300L
        private val JSON_MEDIA = "application/json".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)

        /** The BE issues UUIDs; nothing else reaches a request path. */
        private val SAFE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** No CookieJar (the default): the session cookie is handled by hand. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Not on `/auth/…` and `/workspaces…` (`/health` is outside `/api/v1` anyway). */
        internal fun sendsWorkspaceHeader(path: String): Boolean =
            !(path.startsWith("/auth/") || path == "/workspaces" || path.startsWith("/workspaces/"))

        /**
         * BE §12 scoped collection endpoints: a 404 there means the header's workspace is gone for us.
         * ID-addressed endpoints 404 for their own resource instead.
         */
        internal fun isScopedCollection(method: Method, path: String): Boolean = when (path) {
            "/users", "/projects", "/me/tasks", "/notifications", "/notifications/read-all", "/search" -> true
            "/tasks" -> method == Method.POST
            else -> false
        }

        /** Ids go into URL paths: anything but a UUID (e.g. `..` or `a/b`) is a 404 without a request. */
        private fun id(value: String): String {
            if (!SAFE_ID.matches(value)) throw ApiError(ApiError.Code.NOT_FOUND, null, "Not found")
            return value
        }
    }
}

/**
 * Awaits an OkHttp call, cancellation-safe both ways: cancelling the coroutine cancels the call, and a
 * callback that arrives after cancellation never resumes the continuation. The body is read on
 * OkHttp's thread, never the caller's.
 */
internal suspend fun Call.await(): LiveApi.Raw = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { LiveApi.Raw(it.code, it.headers, it.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return
                }
                if (continuation.isActive) continuation.resume(raw)
            }
        }
    )
}

/** Reads the BE session cookie (`sid`) from `Set-Cookie`. */
object SessionCookie {
    const val NAME = "sid"

    /** The `sid` value from a response's headers, or null. */
    fun token(headers: Headers): String? = headers.values("Set-Cookie").firstNotNullOfOrNull(::token)

    /**
     * The `sid` value from one (possibly folded) `Set-Cookie` header value: several cookies joined with
     * ", ", attributes in any order, and the comma inside an `Expires=` date.
     */
    fun token(header: String): String? {
        for (piece in header.split(',')) {
            val firstPair = piece.substringBefore(';').trim()
            if (!firstPair.startsWith("$NAME=")) continue
            // An empty value (a clearing cookie) is not a session; a later sid may be.
            val value = firstPair.removePrefix("$NAME=")
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
