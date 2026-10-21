package dev.kiora.a2a

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A2A 1.0 JSON-RPC text client for the task operations used by Kiora. */
class JsonRpcA2aPeer internal constructor(
    private val endpoint: String,
    private val version: String,
    private val tenant: String?,
    private val transport: A2aHttpTransport,
    private val bearerToken: suspend () -> String?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : A2aPeer {
    override suspend fun send(instruction: String): RemoteTask = send(A2aUserMessage(instruction))

    override suspend fun send(message: A2aUserMessage): RemoteTask = sendMessage(null, message)

    override suspend fun continueTask(taskId: String, instruction: String): RemoteTask =
        continueTask(taskId, A2aUserMessage(instruction))

    override suspend fun continueTask(taskId: String, message: A2aUserMessage): RemoteTask =
        sendMessage(taskId, message)

    override suspend fun getTask(taskId: String): RemoteTask = A2aTaskCodec.decode(
        call("GetTask", params(mapOf("id" to JsonPrimitive(taskId)))), newId,
    )

    override suspend fun cancelTask(taskId: String) {
        call("CancelTask", params(mapOf("id" to JsonPrimitive(taskId))))
    }

    private suspend fun sendMessage(taskId: String?, content: A2aUserMessage): RemoteTask {
        val message = mutableMapOf<String, JsonElement>(
            "messageId" to JsonPrimitive(newId()),
            "role" to JsonPrimitive("ROLE_USER"),
            "parts" to content.toParts(),
        )
        if (taskId != null) message["taskId"] = JsonPrimitive(taskId)
        content.contextId?.let { message["contextId"] = JsonPrimitive(it) }
        return A2aTaskCodec.decode(
            call("SendMessage", params(mapOf(
                "message" to JsonObject(message),
                // A2A servers may return a working Task immediately. The host can then poll
                // GetTask instead of holding one HTTP request open for the whole agent run.
                "configuration" to JsonObject(mapOf("blocking" to JsonPrimitive(false))),
            ))), newId,
        )
    }

    private fun params(fields: Map<String, JsonElement>): JsonObject = JsonObject(
        if (tenant == null) fields else fields + ("tenant" to JsonPrimitive(tenant)),
    )

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val requestId = newId()
        val body = JsonObject(mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to JsonPrimitive(requestId),
            "method" to JsonPrimitive(method),
            "params" to params,
        ))
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "A2A-Version" to version,
        )
        bearerToken()?.let { headers["Authorization"] = "Bearer $it" }
        val reply = transport.request("POST", endpoint, headers, body.toString())
        check(reply.status in 200..299) { "A2A HTTP ${reply.status}: ${reply.body.take(500)}" }
        val response = Json.parseToJsonElement(reply.body).jsonObject
        response["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.content ?: "Unknown JSON-RPC error"
            error("A2A $method: $message")
        }
        check(response["id"]?.jsonPrimitive?.content == requestId) { "A2A response ID mismatch" }
        return response.getValue("result").jsonObject
    }
}
