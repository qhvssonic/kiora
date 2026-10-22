package dev.kiora.model.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelResponse
import dev.kiora.core.ModelIdentity
import dev.kiora.core.UsageReportingModelPort
import dev.kiora.core.ToolCall
import dev.kiora.core.ToolDefinition
import java.io.File
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class LiteRtBackend { CPU, GPU }

/** ModelPort over a cached LiteRT-LM engine. Kiora owns the agent loop and tool approval. */
class LiteRtModelPort(
    private val modelFile: File,
    private val backend: LiteRtBackend = LiteRtBackend.CPU,
    private val cacheDirectory: File? = null,
    private val systemPrompt: String = "You are Kiora, a mobile agent. Call a provided tool when it can answer the user's request. Do not invent tool results. A shell tool runs on this Android phone; an A2A tool contacts a remote agent. Never run a remote agent's name as a phone shell command. To send a local file to a remote agent, use its A2A tool's files argument when available.",
    private val enableVision: Boolean = false,
    private val imageProvider: suspend () -> List<ByteArray> = { emptyList() },
) : UsageReportingModelPort {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var closed = false
    private class Pending(val conversation: Conversation)
    private val pendingByCallId = mutableMapOf<String, Pending>()

    init {
        require(modelFile.isFile && modelFile.extension.equals("litertlm", ignoreCase = true)) {
            "LiteRT-LM model must be an existing .litertlm file"
        }
    }

    /** Warm up model weights before the first agent run. */
    suspend fun prepare() { lock.withLock { ensureEngine() } }

    override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant =
        infer(messages, tools, null)

    override suspend fun respondStreaming(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): AgentMessage.Assistant = infer(messages, tools, onTextDelta)

    override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>): ModelResponse =
        ModelResponse(infer(messages, tools, null), model = ModelIdentity(modelFile.name, "litert-local"))

    override suspend fun respondStreamingWithUsage(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = ModelResponse(infer(messages, tools, onTextDelta),
        model = ModelIdentity(modelFile.name, "litert-local"))

    private suspend fun infer(
        messages: List<AgentMessage>, definitions: List<ToolDefinition>, onTextDelta: (suspend (String) -> Unit)?,
    ): AgentMessage.Assistant = lock.withLock {
        require(messages.isNotEmpty()) { "Model history is empty" }
        val runtime = ensureEngine()
        val gson = Gson()
        fun toMessage(message: AgentMessage): Message = when (message) {
            is AgentMessage.User -> Message.user(message.text)
            is AgentMessage.ContextSummary -> Message.user("Earlier conversation summary:\n${message.text}")
            is AgentMessage.Assistant -> Message.model(Contents.of(message.text),
                message.toolCalls.map { call ->
                    @Suppress("UNCHECKED_CAST")
                    val arguments = gson.fromJson(call.arguments.toString(), Map::class.java) as Map<String, Any>
                    com.google.ai.edge.litertlm.ToolCall(call.name, arguments)
                })
            is AgentMessage.ToolResult -> Message.tool(Contents.of(Content.ToolResponse(
                message.toolName, toolResponseValue(message, gson),
            )))
        }
        val last = messages.last()
        val recentResults = messages.takeLastWhile { it is AgentMessage.ToolResult }
            .filterIsInstance<AgentMessage.ToolResult>()
        val toolImages = recentResults.flatMap { it.images }
        require(toolImages.size <= 1) { "This model accepts one image per turn" }
        if (toolImages.isNotEmpty()) {
            val pending = recentResults.mapNotNull { pendingByCallId[it.callId] }.toSet()
            pendingByCallId.entries.removeAll { it.value in pending }
            pending.forEach { it.conversation.close() }
        }
        val continuation = if (toolImages.isEmpty())
            (last as? AgentMessage.ToolResult)?.let { pendingByCallId[it.callId] } else null
        if (continuation != null) pendingByCallId.entries.removeAll { it.value === continuation }
        val summary = messages.filterIsInstance<AgentMessage.ContextSummary>().lastOrNull()
        val history = (if (toolImages.isEmpty()) messages.dropLast(1) else messages)
            .filterNot { it is AgentMessage.ContextSummary }.map(::toMessage)
        val availableTools = definitions.map { definition ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = JsonObject(mapOf(
                    "name" to JsonPrimitive(definition.name),
                    "description" to JsonPrimitive(definition.description),
                    "parameters" to definition.parameters,
                )).toString()

                override fun execute(paramsJsonString: String): String =
                    error("Kiora AgentRuntime must authorize and execute tools")
            })
        }
        val conversation = continuation?.conversation ?: runtime.createConversation(ConversationConfig(
            systemInstruction = Contents.of(if (summary == null) systemPrompt
                else "$systemPrompt\n\nEarlier conversation summary:\n${summary.text}"),
            initialMessages = history,
            tools = availableTools,
            automaticToolCalling = false,
        ))
        var keepConversation = false
        try {
            val request = if (continuation != null) {
                val results = messages.takeLastWhile { it is AgentMessage.ToolResult }
                    .map { toMessage(it).contents.contents.single() }
                Message.tool(Contents.of(results))
            } else if (toolImages.isNotEmpty()) {
                require(enableVision) { "Current model does not support images" }
                Message.user(Contents.of(listOf(
                    Content.ImageBytes(Base64.getDecoder().decode(toolImages.single().data)),
                    Content.Text("Attached image from the preceding read tool result. Answer the user's question about it."),
                )))
            } else if (last is AgentMessage.User && last.images.isNotEmpty()) {
                require(enableVision) { "Current model does not support images" }
                val images = last.images.map { Base64.getDecoder().decode(it.data) }
                require(images.size <= 1) { "This model accepts one image per turn" }
                Message.user(Contents.of(images.map { Content.ImageBytes(it) } + Content.Text(last.text)))
            } else if (last is AgentMessage.User && enableVision) {
                val images = imageProvider()
                require(images.size <= 1) { "This model accepts one image per turn" }
                if (images.isEmpty()) toMessage(last) else Message.user(Contents.of(
                    images.map { Content.ImageBytes(it) } + Content.Text(last.text),
                ))
            } else {
                toMessage(last)
            }
            val text = StringBuilder()
            val requested = mutableListOf<ToolCall>()
            fun consume(result: Message): String {
                requested += result.toolCalls.map { call ->
                    ToolCall(UUID.randomUUID().toString(), call.name,
                        Json.parseToJsonElement(gson.toJson(call.arguments)) as JsonObject)
                }
                return result.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
            if (onTextDelta == null) {
                text.append(consume(withContext(Dispatchers.Default) { conversation.sendMessage(request) }))
            } else {
                try {
                    withContext(Dispatchers.Default) {
                        conversation.sendMessageAsync(request).collect { chunk ->
                            val delta = consume(chunk)
                            if (delta.isNotEmpty()) {
                                text.append(delta)
                                onTextDelta(delta)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    conversation.cancelProcess()
                    throw cancelled
                }
            }
            if (requested.isNotEmpty()) {
                val pending = Pending(conversation)
                requested.forEach { pendingByCallId[it.id] = pending }
                keepConversation = true
            }
            AgentMessage.Assistant(text.toString(), requested)
        } finally {
            if (!keepConversation) conversation.close()
        }
    }

    private fun toolResponseValue(message: AgentMessage.ToolResult, gson: Gson): Any? {
        if (message.isError) return mapOf("error" to message.text)
        return runCatching { gson.fromJson(message.text, Any::class.java) }
            .getOrElse { message.text }
    }

    private suspend fun ensureEngine(): Engine {
        check(!closed) { "LiteRT-LM model is closed" }
        engine?.let { return it }
        val cachePath = cacheDirectory?.let { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Cannot create LiteRT-LM cache directory: ${directory.absolutePath}"
            }
            directory.absolutePath
        }
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = if (backend == LiteRtBackend.GPU) Backend.GPU() else Backend.CPU(),
            visionBackend = if (enableVision) Backend.CPU() else null,
            maxNumImages = if (enableVision) 1 else null,
            cacheDir = cachePath,
        )
        return Engine(config).also { candidate ->
            try {
                withContext(Dispatchers.Default) { candidate.initialize() }
                engine = candidate
            } catch (error: Throwable) {
                candidate.close()
                throw error
            }
        }
    }

    /** Waits for any active inference before releasing the native engine. */
    suspend fun close() = withContext(NonCancellable) {
        lock.withLock {
            closed = true
            val pending = pendingByCallId.values.toSet()
            pendingByCallId.clear()
            val active = engine
            engine = null
            try {
                pending.forEach { it.conversation.close() }
            } finally {
                if (active != null) withContext(Dispatchers.Default) { active.close() }
            }
        }
    }
}
