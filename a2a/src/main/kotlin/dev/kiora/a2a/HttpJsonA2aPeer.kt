package dev.kiora.a2a

import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HttpReply(val status: Int, val body: String)

/** A small HTTP seam that can be replaced with OkHttp or another Android transport. */
fun interface A2aHttpTransport {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply
}

class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 90_000,
) : A2aHttpTransport {
    override suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: String?,
    ): HttpReply = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpReply(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/** A2A 1.0 HTTP+JSON text client: discovery, send, follow-up, get and cancel. */
class HttpJsonA2aPeer private constructor(
    private val baseUrl: String,
    private val version: String,
    private val tenant: String?,
    private val transport: A2aHttpTransport,
    private val bearerToken: suspend () -> String?,
    private val newId: () -> String,
) : A2aPeer {
    override suspend fun send(instruction: String): RemoteTask = send(A2aUserMessage(instruction))

    override suspend fun send(message: A2aUserMessage): RemoteTask = sendMessage(null, message)

    override suspend fun continueTask(taskId: String, instruction: String): RemoteTask =
        continueTask(taskId, A2aUserMessage(instruction))

    override suspend fun continueTask(taskId: String, message: A2aUserMessage): RemoteTask =
        sendMessage(taskId, message)

    override suspend fun getTask(taskId: String): RemoteTask {
        val tenantQuery = tenant?.let { "?tenant=${encodeSegment(it)}" }.orEmpty()
        return A2aTaskCodec.decode(request("GET", "tasks/${encodeSegment(taskId)}$tenantQuery", null), newId)
    }

    override suspend fun cancelTask(taskId: String) {
        val body = tenant?.let { JsonObject(mapOf("tenant" to JsonPrimitive(it))) } ?: JsonObject(emptyMap())
        request("POST", "tasks/${encodeSegment(taskId)}:cancel", body)
    }

    private suspend fun sendMessage(taskId: String?, content: A2aUserMessage): RemoteTask {
        val message = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "messageId" to JsonPrimitive(newId()),
            "role" to JsonPrimitive("ROLE_USER"),
            "parts" to content.toParts(),
        )
        if (taskId != null) message["taskId"] = JsonPrimitive(taskId)
        val body = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "message" to JsonObject(message),
        )
        if (tenant != null) body["tenant"] = JsonPrimitive(tenant)
        return A2aTaskCodec.decode(request("POST", "message:send", JsonObject(body)), newId)
    }

    private suspend fun request(method: String, path: String, body: JsonObject?): JsonObject {
        val headers = mutableMapOf(
            "Accept" to "application/a2a+json",
            "A2A-Version" to version,
        )
        if (body != null) headers["Content-Type"] = "application/a2a+json"
        bearerToken()?.let { headers["Authorization"] = "Bearer $it" }
        val reply = transport.request(method, "$baseUrl/$path", headers, body?.toString())
        check(reply.status in 200..299) { "A2A HTTP ${reply.status}: ${reply.body.take(500)}" }
        return Json.parseToJsonElement(reply.body).jsonObject
    }

    companion object {
        internal fun fromInterface(
            baseUrl: String,
            version: String,
            tenant: String?,
            transport: A2aHttpTransport,
            bearerToken: suspend () -> String?,
            newId: () -> String,
        ): HttpJsonA2aPeer = HttpJsonA2aPeer(baseUrl.trimEnd('/'), version, tenant, transport, bearerToken, newId)

        suspend fun discover(
            agentCardUrl: String,
            transport: A2aHttpTransport = UrlConnectionTransport(),
            bearerToken: suspend () -> String? = { null },
            newId: () -> String = { UUID.randomUUID().toString() },
        ): HttpJsonA2aPeer {
            val token = bearerToken()
            val headers = mutableMapOf("Accept" to "application/json")
            if (token != null) headers["Authorization"] = "Bearer $token"
            val reply = transport.request("GET", agentCardUrl, headers, null)
            check(reply.status in 200..299) { "Agent Card HTTP ${reply.status}: ${reply.body.take(500)}" }
            val card = Json.parseToJsonElement(reply.body).jsonObject
            val selected = card.getValue("supportedInterfaces").jsonArray
                .map { it.jsonObject }
                .firstOrNull { it["protocolBinding"]?.jsonPrimitive?.content == "HTTP+JSON" }
                ?: error("Agent Card has no HTTP+JSON interface")
            val baseUrl = selected.getValue("url").jsonPrimitive.content.trimEnd('/')
            require(URI(baseUrl).scheme in listOf("http", "https")) { "Unsupported A2A URL scheme" }
            return HttpJsonA2aPeer(
                baseUrl,
                selected.getValue("protocolVersion").jsonPrimitive.content,
                selected["tenant"]?.jsonPrimitive?.content,
                transport,
                bearerToken,
                newId,
            )
        }

        private fun encodeSegment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
    }
}
