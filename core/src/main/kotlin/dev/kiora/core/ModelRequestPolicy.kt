package dev.kiora.core

/** Checked immediately before inference, including compaction and context-window retries. */
data class ModelRequest(
    val sessionId: String,
    val messages: List<AgentMessage>,
    val purpose: ModelRequestPurpose,
)

enum class ModelRequestPurpose { RESPONSE, SUMMARY }

/** A policy rejection must not be treated as a recoverable summarization/model error. */
class ModelRequestRejectedException(message: String) : IllegalStateException(message)

/** Host policy may reject a request before any model callback or network operation starts. */
fun interface ModelRequestPolicy {
    suspend fun check(request: ModelRequest)
}
