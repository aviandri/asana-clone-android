package com.mekari.project.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every failure from `MekariApi` except cancellation: the BE error envelope
 * `{error:{code,message,details}}`, or a transport/decoding problem. The UI shows `message`.
 */
class ApiError(
    val code: Code,
    /** The HTTP status; null for TRANSPORT. */
    val status: Int? = null,
    override val message: String,
    /** `error.details` on VALIDATION (and NO_WORKSPACE on 403). */
    val issues: List<FieldIssue> = emptyList()
) : Exception(message) {
    /** Open: BE codes plus the client-side TRANSPORT / DECODING. */
    @JvmInline
    value class Code(val raw: String) {
        override fun toString() = raw

        companion object {
            val NOT_FOUND = Code("NOT_FOUND")
            val VALIDATION = Code("VALIDATION")
            val UNAUTHORIZED = Code("UNAUTHORIZED")
            val FORBIDDEN = Code("FORBIDDEN")
            val CONFLICT = Code("CONFLICT")
            val GONE = Code("GONE")
            val RATE_LIMITED = Code("RATE_LIMITED")
            val INTERNAL = Code("INTERNAL")

            /** No HTTP response: offline, DNS, TLS, timeout. */
            val TRANSPORT = Code("TRANSPORT")

            /** A 2xx body we couldn't decode; the message names the JSON path. */
            val DECODING = Code("DECODING")
        }
    }

    fun issue(field: String): FieldIssue? = issues.firstOrNull { it.path.firstOrNull() == field }

    /** 403 with `details: [{path:["workspace"], message:"NO_WORKSPACE"}]` (BE §12). */
    val isNoWorkspace: Boolean
        get() = code == Code.FORBIDDEN && issues.any { it.path == listOf("workspace") && it.message == "NO_WORKSPACE" }

    override fun equals(other: Any?) = other is ApiError &&
        code == other.code &&
        status == other.status &&
        message == other.message &&
        issues == other.issues

    override fun hashCode() = listOf(code, status, message, issues).hashCode()

    override fun toString() = "ApiError($code, $status, $message${if (issues.isEmpty()) "" else ", $issues"})"

    companion object {
        fun transport(message: String = "Can't reach the server") = ApiError(Code.TRANSPORT, null, message)

        fun decoding(detail: String) = ApiError(Code.DECODING, null, "Unexpected response from the server ($detail)")

        /** Decodes the BE envelope; anything else becomes `Code("HTTP <status>")`. */
        fun from(status: Int, body: String): ApiError {
            val error = runCatching { MekariJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
            val code = (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val message = (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (code == null || message == null) {
                return ApiError(Code("HTTP $status"), status, "The server returned an error (HTTP $status)")
            }
            return ApiError(Code(code), status, message, issues(error["details"]))
        }

        /** `details` is a zod-style issue array when present; any other shape is ignored. */
        private fun issues(details: Any?): List<FieldIssue> = (details as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val message = (obj["message"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            // zod paths may contain numbers (array indices); they become strings.
            val path = (obj["path"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            FieldIssue(path, message)
        }
    }
}

data class FieldIssue(val path: List<String>, val message: String)
