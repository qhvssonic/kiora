package dev.kiora.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** A model response may contain text, tool calls, or both. */
sealed interface AgentMessage {
    data class User(val text: String, val images: List<ToolImage> = emptyList()) : AgentMessage
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : AgentMessage
    /** Model-view checkpoint derived from a durable SessionSummary, not a chat message. */
    data class ContextSummary(val text: String) : AgentMessage
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val text: String,
        val isError: Boolean,
        val images: List<ToolImage>,
        /** Host-facing failure category; null for success or older unclassified errors. */
        val failureKind: ToolFailureKind?,
        /** Programmatic result for the host; never included in model-visible context. */
        val structuredContent: JsonElement? = null,
        /** A tool that needs the user or host before work can continue. */
        val attention: ToolAttention? = null,
    ) : AgentMessage {
        constructor(callId: String, toolName: String, text: String, isError: Boolean,
                    images: List<ToolImage>, failureKind: ToolFailureKind?) :
            this(callId, toolName, text, isError, images, failureKind, null, null)

        /** Keep the previous Kotlin/JVM constructor for existing tool clients. */
        constructor(
            callId: String,
            toolName: String,
            text: String,
            isError: Boolean = false,
            images: List<ToolImage> = emptyList(),
        ) : this(callId, toolName, text, isError, images, null)

        constructor(
            callId: String,
            toolName: String,
            text: String,
            isError: Boolean,
            failureKind: ToolFailureKind,
        ) : this(callId, toolName, text, isError, emptyList(), failureKind)

        init {
            require(failureKind == null || isError)
            require(attention == null || !isError)
        }
    }
}

enum class ToolFailureKind {
    UNKNOWN_OR_INACTIVE_TOOL,
    INVALID_ARGUMENTS,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_FAILED,
    EXECUTION_FAILED,
    LIMIT_REACHED,
    INTERRUPTED,
}

/** Base64-encoded image content carried by a tool result to a vision-capable model. */
data class ToolImage(val mimeType: String, val data: String) {
    init {
        require(mimeType == "image/jpeg" || mimeType == "image/png" || mimeType == "image/webp")
        require(data.isNotBlank())
    }
}

enum class ToolAttention { INPUT_REQUIRED, AUTH_REQUIRED }

data class ToolOutput(
    val text: String,
    val images: List<ToolImage> = emptyList(),
    val structuredContent: JsonElement? = null,
    val isError: Boolean = false,
    val attention: ToolAttention? = null,
) {
    constructor(text: String, images: List<ToolImage>) : this(text, images, null, false, null)

    init { require(attention == null || !isError) }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    /** Parsing failures are returned to the model as tool errors, not run failures. */
    val argumentsError: String? = null,
) {
    /** Keep the three-argument JVM constructor for existing model adapters. */
    constructor(id: String, name: String, arguments: JsonObject) : this(id, name, arguments, null)
}

data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the tool arguments, supplied to the model adapter. */
    val parameters: JsonObject,
    /** Optional schema for host-facing structuredContent; it is not sent to the model. */
    val outputSchema: JsonObject? = null,
) {
    constructor(name: String, description: String, parameters: JsonObject) :
        this(name, description, parameters, null)
}

interface ModelPort {
    suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant

    /** Providers without streaming can use the final-response fallback. */
    suspend fun respondStreaming(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit,
    ): AgentMessage.Assistant = respond(messages, tools)
}

/** Provider-reported token counts. Null fields mean the provider did not report them. */
data class ModelUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
) {
    init {
        require(listOf(inputTokens, outputTokens, totalTokens, cachedInputTokens).any { it != null })
        require(listOf(inputTokens, outputTokens, totalTokens, cachedInputTokens).all { it == null || it >= 0 })
    }
}

/** Identity of the model that actually produced one response. The provider may be host-specific. */
data class ModelIdentity(val modelId: String, val providerId: String? = null) {
    init { require(modelId.isNotBlank() && (providerId == null || providerId.isNotBlank())) }
}

data class ModelResponse(
    val assistant: AgentMessage.Assistant,
    val usage: ModelUsage? = null,
    val model: ModelIdentity? = null,
) {
    constructor(assistant: AgentMessage.Assistant, usage: ModelUsage?) : this(assistant, usage, null)
}

/** Optional provider metadata without changing existing ModelPort implementations. */
interface UsageReportingModelPort : ModelPort {
    suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>): ModelResponse

    suspend fun respondStreamingWithUsage(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = respondWithUsage(messages, tools)
}

/** Shape model-visible history without changing the durable session tree. */
fun interface ModelContextTransformer {
    suspend fun transform(messages: List<AgentMessage>): List<AgentMessage>
}

/** The host chooses how to summarize a completed prefix, including which model to use. */
fun interface SessionSummarizer {
    suspend fun summarize(messages: List<AgentMessage>): String
}

/** Optional automatic checkpoint before a model request exceeds the host's history budget. */
data class AutoCompaction(
    val triggerTokens: Int,
    val summarizer: SessionSummarizer,
    val keepRecentTurns: Int = 1,
) {
    init {
        require(triggerTokens > 0)
        require(keepRecentTurns >= 1)
    }
}

interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(arguments: JsonObject): String
    suspend fun executeResult(arguments: JsonObject): ToolOutput = ToolOutput(execute(arguments))
}

/** Host-specific argument checks that must pass before asking for tool authorization. */
interface ToolArgumentPrevalidator {
    /** Return a model-visible error for invalid arguments, or null when they are valid. */
    suspend fun validateArguments(arguments: JsonObject): String?
}

/** Optional live updates from a tool invocation; updates are events, not session messages. */
fun interface ToolProgressSink {
    suspend fun report(message: String)
}

/** Tools with long-running work can opt in without changing existing AgentTool implementations. */
interface ProgressReportingAgentTool : AgentTool {
    suspend fun executeResult(arguments: JsonObject, progress: ToolProgressSink): ToolOutput
}

/** Select the registered tools visible to the model for each model turn. */
fun interface ActiveToolSelector {
    suspend fun select(sessionId: String, availableToolNames: Set<String>): Set<String>
}

/** A validated call to a tool selected for this model turn. */
data class ToolAuthorizationRequest(
    val sessionId: String,
    val definition: ToolDefinition,
    val call: ToolCall,
)

/** The host application decides whether to allow a call, and may suspend to ask the user. */
fun interface ToolAuthorizer {
    suspend fun authorize(request: ToolAuthorizationRequest): ToolAuthorization
}

sealed interface ToolAuthorization {
    data object Allowed : ToolAuthorization
    data class Denied(val reason: String) : ToolAuthorization
}

data class RunLimits(
    val maxModelTurns: Int = 8,
    val maxToolCalls: Int = 20,
    val timeoutMillis: Long = 120_000,
) {
    init {
        require(maxModelTurns > 0)
        require(maxToolCalls >= 0)
        require(timeoutMillis > 0)
    }
}

sealed interface AgentEvent {
    data class RunStarted(val sessionId: String) : AgentEvent
    data class AssistantTextDelta(val sessionId: String, val delta: String) : AgentEvent
    data class MessageAdded(val entry: SessionEntry) : AgentEvent
    data class ModelUsageReported(val sessionId: String, val record: SessionUsage) : AgentEvent
    data class ModelInvocationRecorded(val sessionId: String, val record: SessionModelInvocation) : AgentEvent
    data class ToolRequested(val call: ToolCall) : AgentEvent
    data class ToolProgress(val sessionId: String, val call: ToolCall, val message: String) : AgentEvent
    data class ToolCompleted(val result: AgentMessage.ToolResult) : AgentEvent
    data class RunFinished(val result: RunResult) : AgentEvent
}

fun interface EventSink {
    suspend fun emit(event: AgentEvent)
}

sealed interface RunResult {
    data class Completed(val text: String, val sessionId: String) : RunResult
    data class NeedsAttention(val sessionId: String, val toolResult: AgentMessage.ToolResult) : RunResult
    data class LimitReached(val reason: String, val sessionId: String) : RunResult
    data class Failed(val reason: String, val sessionId: String) : RunResult
}
