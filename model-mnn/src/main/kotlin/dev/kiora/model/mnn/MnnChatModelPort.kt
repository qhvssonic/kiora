package dev.kiora.model.mnn

import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelResponse
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ToolDefinition
import dev.kiora.core.UsageReportingModelPort
import dev.kiora.model.ChatCompletionModelPort

/** Connects to MNN Chat's separately running on-device OpenAI-compatible API service. */
class MnnChatModelPort(
    private val model: String,
    endpoint: String = "http://127.0.0.1:8080/v1/chat/completions",
    apiToken: suspend () -> String? = { null },
    streamingEnabled: Boolean = false,
) : UsageReportingModelPort {
    private val delegate = ChatCompletionModelPort(
        endpoint = endpoint,
        model = model,
        apiKey = apiToken,
        streamingEnabled = streamingEnabled,
    )

    override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant =
        delegate.respond(messages, tools)

    override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>): ModelResponse =
        delegate.respondWithUsage(messages, tools).let { response ->
            response.copy(model = ModelIdentity(response.model?.modelId ?: model, "mnn-local"))
        }

    override suspend fun respondStreaming(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit,
    ): AgentMessage.Assistant = delegate.respondStreaming(messages, tools, onTextDelta)

    override suspend fun respondStreamingWithUsage(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = delegate.respondStreamingWithUsage(messages, tools, onTextDelta).let { response ->
        response.copy(model = ModelIdentity(response.model?.modelId ?: model, "mnn-local"))
    }
}
