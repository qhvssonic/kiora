package dev.kiora.model

import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelResponse
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ModelUsage
import dev.kiora.core.ToolCall
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolImage
import dev.kiora.core.UsageReportingModelPort
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val DEFAULT_AGENT_SYSTEM_PROMPT = "You are Kiora, a mobile agent. When the user asks for something a tool provides, call the matching tool before answering. In particular, use an available A2A tool for organization-specific knowledge or live business data; do not guess those facts. Pass the user's complete question to the tool, then explain its result clearly."

data class ModelHttpReply(val status: Int, val body: String)

fun interface ModelHttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): ModelHttpReply
}

/** Receives complete SSE data payloads, excluding the data: prefix and blank separator. */
interface StreamingModelHttpTransport : ModelHttpTransport {
    suspend fun postStream(
        url: String, headers: Map<String, String>, body: String, onData: suspend (String) -> Unit,
    )
}

class UrlConnectionModelTransport : StreamingModelHttpTransport {
    override suspend fun post(
        url: String, headers: Map<String, String>, body: String,
    ): ModelHttpReply = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ModelHttpReply(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            cancellation.dispose()
            connection.disconnect()
        }
    }

    override suspend fun postStream(
        url: String, headers: Map<String, String>, body: String, onData: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                error("Model HTTP $status: ${error.take(500)}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) {
                        if (data.isNotEmpty()) {
                            onData(data.toString().trimEnd('\n'))
                            data.clear()
                        }
                    } else if (line.startsWith("data:")) {
                        data.append(line.removePrefix("data:").trimStart()).append('\n')
                    }
                }
                if (data.isNotEmpty()) onData(data.toString().trimEnd('\n'))
            }
        } finally {
            cancellation.dispose()
            connection.disconnect()
        }
    }
}

/** Text and function-tool subset of a Chat Completions compatible endpoint. */
class ChatCompletionModelPort(
    endpoint: String,
    private val model: String,
    private val apiKey: suspend () -> String?,
    private val transport: ModelHttpTransport = UrlConnectionModelTransport(),
    private val systemPrompt: String = DEFAULT_AGENT_SYSTEM_PROMPT,
    private val streamingEnabled: Boolean = true,
    private val requestStreamingUsage: Boolean = false,
) : UsageReportingModelPort {
    private val requestEndpoint = resolveChatCompletionsEndpoint(endpoint)

    init {
        require(model.isNotBlank())
    }

    override suspend fun respond(
        messages: List<AgentMessage>, tools: List<ToolDefinition>,
    ): AgentMessage.Assistant = respondWithUsage(messages, tools).assistant

    override suspend fun respondWithUsage(
        messages: List<AgentMessage>, tools: List<ToolDefinition>,
    ): ModelResponse {
        val reply = transport.post(requestEndpoint, headers(), requestBody(messages, tools, false))
        check(reply.status in 200..299) { "Model HTTP ${reply.status}: ${reply.body.take(500)}" }
        val root = Json.parseToJsonElement(reply.body).jsonObject
        val choice = root.getValue("choices").jsonArray.first().jsonObject
        val message = choice.getValue("message").jsonObject
        val truncated = choice["finish_reason"]?.let { it != JsonNull && it.jsonPrimitive.content == "length" } == true
        val calls = message["tool_calls"]?.let { if (it == JsonNull) emptyList() else it.jsonArray } ?: emptyList()
        val assistant = AgentMessage.Assistant(
            text = message["content"]?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }.orEmpty(),
            toolCalls = calls.map { element ->
                val call = element.jsonObject
                val function = call.getValue("function").jsonObject
                toolCall(
                    call.getValue("id").jsonPrimitive.content,
                    function.getValue("name").jsonPrimitive.content,
                    (function["arguments"] as? JsonPrimitive)?.content.orEmpty(),
                    truncated,
                )
            },
        )
        return ModelResponse(assistant, parseUsage(root), ModelIdentity(responseModel(root)))
    }

    override suspend fun respondStreaming(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): AgentMessage.Assistant = respondStreamingWithUsage(messages, tools, onTextDelta).assistant

    override suspend fun respondStreamingWithUsage(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val streaming = transport.takeIf { streamingEnabled } as? StreamingModelHttpTransport
            ?: return respondWithUsage(messages, tools)
        val text = StringBuilder()
        data class PendingCall(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
        val calls = sortedMapOf<Int, PendingCall>()
        var completed = false
        var truncated = false
        var usage: ModelUsage? = null
        var responseModelId: String? = null
        streaming.postStream(requestEndpoint, headers(), requestBody(messages, tools, true)) { payload ->
            if (payload == "[DONE]") {
                completed = true
            } else if (!completed) {
                val root = Json.parseToJsonElement(payload).jsonObject
                usage = parseUsage(root) ?: usage
                responseModelId = root["model"]?.takeIf { it != JsonNull }
                    ?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?: responseModelId
                val choice = root["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject ?: return@postStream
                if (choice["finish_reason"]?.let { it != JsonNull && it.jsonPrimitive.content == "length" } == true) {
                    truncated = true
                }
                val delta = choice["delta"]?.let { it as? JsonObject } ?: return@postStream
                delta["content"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.let { part ->
                    text.append(part)
                    onTextDelta(part)
                }
                delta["tool_calls"]?.jsonArray?.forEach { element ->
                    val item = element.jsonObject
                    val index = item.getValue("index").jsonPrimitive.content.toInt()
                    val pending = calls.getOrPut(index) { PendingCall() }
                    item["id"]?.jsonPrimitive?.content?.let(pending.id::append)
                    item["function"]?.jsonObject?.let { function ->
                        function["name"]?.jsonPrimitive?.content?.let(pending.name::append)
                        function["arguments"]?.jsonPrimitive?.content?.let(pending.args::append)
                    }
                }
            }
        }
        check(completed) { "Model stream ended before [DONE]" }
        val assistant = AgentMessage.Assistant(text.toString(), calls.values.map { pending ->
            toolCall(
                pending.id.toString().also { require(it.isNotBlank()) },
                pending.name.toString().also { require(it.isNotBlank()) },
                pending.args.toString(),
                truncated,
            )
        })
        return ModelResponse(assistant, usage, ModelIdentity(responseModelId ?: model))
    }

    private fun responseModel(root: JsonObject): String =
        root["model"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: model

    private fun parseUsage(root: JsonObject): ModelUsage? {
        val counts = root["usage"] as? JsonObject ?: return null
        fun JsonObject.count(key: String): Int? = (get(key) as? JsonPrimitive)?.content?.toIntOrNull()
        val input = counts.count("prompt_tokens")
        val output = counts.count("completion_tokens")
        val total = counts.count("total_tokens")
        val cached = (counts["prompt_tokens_details"] as? JsonObject)?.count("cached_tokens")
        if (listOf(input, output, total, cached).all { it == null }) return null
        return ModelUsage(input, output, total, cached)
    }

    private fun toolCall(id: String, name: String, rawArguments: String, truncated: Boolean = false): ToolCall {
        val arguments = runCatching { Json.parseToJsonElement(rawArguments) as JsonObject }.getOrNull()
        return ToolCall(
            id, name, arguments ?: JsonObject(emptyMap()),
            when {
                truncated -> "Tool call was truncated by the model output limit; retry with complete arguments"
                arguments == null -> "Tool arguments must be a valid JSON object"
                else -> null
            },
        )
    }

    private suspend fun headers(): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        apiKey()?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
    }

    private fun requestBody(messages: List<AgentMessage>, tools: List<ToolDefinition>, stream: Boolean): String {
        val requestMessages = buildList {
            add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(systemPrompt))))
            val pendingImages = mutableListOf<ToolImage>()
            fun flushImages() {
                if (pendingImages.isEmpty()) return
                add(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "content" to imageContent("Attached image(s) from the preceding tool result:", pendingImages),
                )))
                pendingImages.clear()
            }
            messages.forEach { message ->
                if (message !is AgentMessage.ToolResult) flushImages()
                add(encodeMessage(message))
                if (message is AgentMessage.ToolResult) pendingImages.addAll(message.images)
            }
            flushImages()
        }
        val body = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(requestMessages),
        )
        if (stream) {
            body["stream"] = JsonPrimitive(true)
            if (requestStreamingUsage) body["stream_options"] = JsonObject(mapOf(
                "include_usage" to JsonPrimitive(true),
            ))
        }
        if (tools.isNotEmpty()) {
            body["tools"] = JsonArray(tools.map { tool ->
                JsonObject(mapOf(
                    "type" to JsonPrimitive("function"),
                    "function" to JsonObject(mapOf(
                        "name" to JsonPrimitive(tool.name),
                        "description" to JsonPrimitive(tool.description),
                        "parameters" to tool.parameters,
                    )),
                ))
            })
        }
        return JsonObject(body).toString()
    }

    private fun encodeMessage(message: AgentMessage): JsonObject = when (message) {
        is AgentMessage.User -> JsonObject(mapOf(
            "role" to JsonPrimitive("user"),
            "content" to if (message.images.isEmpty()) JsonPrimitive(message.text)
                else imageContent(message.text, message.images),
        ))
        is AgentMessage.ContextSummary -> JsonObject(mapOf(
            "role" to JsonPrimitive("system"),
            "content" to JsonPrimitive("Earlier conversation summary:\n${message.text}"),
        ))
        is AgentMessage.Assistant -> {
            val fields = mutableMapOf<String, JsonElement>(
                "role" to JsonPrimitive("assistant"),
                "content" to JsonPrimitive(message.text),
            )
            if (message.toolCalls.isNotEmpty()) {
                fields["tool_calls"] = JsonArray(message.toolCalls.map { call ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(call.id),
                        "type" to JsonPrimitive("function"),
                        "function" to JsonObject(mapOf(
                            "name" to JsonPrimitive(call.name),
                            "arguments" to JsonPrimitive(call.arguments.toString()),
                        )),
                    ))
                })
            }
            JsonObject(fields)
        }
        is AgentMessage.ToolResult -> JsonObject(mapOf(
            "role" to JsonPrimitive("tool"),
            "tool_call_id" to JsonPrimitive(message.callId),
            "content" to JsonPrimitive(message.text),
        ))
    }

    private fun imageContent(text: String, images: List<ToolImage>): JsonArray = JsonArray(listOf(
        JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))),
    ) + images.map { image -> JsonObject(mapOf(
        "type" to JsonPrimitive("image_url"),
        "image_url" to JsonObject(mapOf(
            "url" to JsonPrimitive("data:${image.mimeType};base64,${image.data}"),
        )),
    )) })
}
