package dev.kiora.model

import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelPort
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ModelResponse
import dev.kiora.core.ToolDefinition
import dev.kiora.core.UsageReportingModelPort

/** One profile for an OpenAI Chat Completions compatible endpoint. Credentials stay with the host. */
data class CompatibleProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val model: String,
    val supportsStreaming: Boolean = true,
    val requestStreamingUsage: Boolean = false,
) {
    constructor(id: String, displayName: String, endpoint: String, model: String,
                supportsStreaming: Boolean) :
        this(id, displayName, endpoint, model, supportsStreaming, false)

    init {
        require(id.isNotBlank() && displayName.isNotBlank() && model.isNotBlank())
        resolveChatCompletionsEndpoint(endpoint)
    }
}

/** Switches between compatible providers without recreating the agent runtime or its session. */
class CompatibleModelProviders(
    profiles: List<CompatibleProvider>,
    private val apiKeyFor: suspend (providerId: String) -> String?,
    private val transport: ModelHttpTransport = UrlConnectionModelTransport(),
    private val systemPrompt: String = DEFAULT_AGENT_SYSTEM_PROMPT,
) : UsageReportingModelPort {
    private val profilesById = profiles.associateBy { it.id }
    @Volatile private var selectedId: String = profiles.firstOrNull()?.id.orEmpty()

    init {
        require(profiles.isNotEmpty())
        require(profilesById.size == profiles.size) { "Duplicate provider ID" }
    }

    val available: List<CompatibleProvider> get() = profilesById.values.toList()
    val activeId: String get() = selectedId

    fun select(id: String) {
        require(id in profilesById) { "Unknown provider: $id" }
        selectedId = id
    }

    override suspend fun respond(
        messages: List<AgentMessage>, tools: List<ToolDefinition>,
    ): AgentMessage.Assistant = respondWithUsage(messages, tools).assistant

    override suspend fun respondWithUsage(
        messages: List<AgentMessage>, tools: List<ToolDefinition>,
    ): ModelResponse {
        val profile = profilesById.getValue(selectedId)
        val response = portFor(profile).respondWithUsage(messages, tools)
        return response.copy(model = ModelIdentity(response.model?.modelId ?: profile.model, profile.id))
    }

    override suspend fun respondStreaming(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): AgentMessage.Assistant = respondStreamingWithUsage(messages, tools, onTextDelta).assistant

    override suspend fun respondStreamingWithUsage(
        messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val profile = profilesById.getValue(selectedId)
        val response = portFor(profile).respondStreamingWithUsage(messages, tools, onTextDelta)
        return response.copy(model = ModelIdentity(response.model?.modelId ?: profile.model, profile.id))
    }

    private fun portFor(profile: CompatibleProvider): ChatCompletionModelPort = ChatCompletionModelPort(
            profile.endpoint, profile.model, { apiKeyFor(profile.id) }, transport, systemPrompt,
            profile.supportsStreaming, profile.requestStreamingUsage,
        )
}
