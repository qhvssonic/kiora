package dev.kiora.speech.tools

import dev.kiora.core.ProgressReportingAgentTool
import dev.kiora.core.ToolArgumentPrevalidator
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolOutput
import dev.kiora.core.ToolProgressSink
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.SpeechSynthesisResult
import kotlinx.serialization.json.*

/** The host supplies bounded synthesis AND playback. PCM never enters agent history. */
class SpeakTextTool(
    private val speak: suspend (SpeechSynthesisRequest) -> SpeechSynthesisResult,
) : ProgressReportingAgentTool, ToolArgumentPrevalidator {
    override val definition = ToolDefinition(
        NAME,
        "Read text aloud on this device with an offline voice. Use when the user requests spoken " +
            "output. Supply the actual text, not a file path; read authorized source text first. " +
            "This plays audio locally and does not send it to another agent. Returns after playback finishes.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string"); put("description", "Text to read aloud, at most 4000 characters") }
                putJsonObject("speed") { put("type", "number"); put("description", "Speaking speed, 0.5 to 2; default 1") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("text"))))
            put("additionalProperties", false)
        },
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("status") { put("type", "string") }
                putJsonObject("durationMillis") { put("type", "integer") }
            }
            put("required", JsonArray(listOf("status", "durationMillis").map(::JsonPrimitive)))
        },
    )

    private fun request(arguments: JsonObject): SpeechSynthesisRequest {
        val text = (arguments["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("text must be a string")
        val speed = if ("speed" in arguments) {
            (arguments["speed"] as? JsonPrimitive)?.floatOrNull
                ?: throw IllegalArgumentException("speed must be a number")
        } else 1f
        return SpeechSynthesisRequest(text, speed)
    }

    override suspend fun validateArguments(arguments: JsonObject): String? =
        try { request(arguments); null } catch (error: IllegalArgumentException) { error.message }

    override suspend fun execute(arguments: JsonObject): String = executeResult(arguments).text
    override suspend fun executeResult(arguments: JsonObject): ToolOutput =
        executeResult(arguments, ToolProgressSink {})

    override suspend fun executeResult(arguments: JsonObject, progress: ToolProgressSink): ToolOutput {
        val request = request(arguments)
        progress.report("正在离线朗读…")
        val result = speak(request)
        return ToolOutput("Offline speech playback completed.", structuredContent = buildJsonObject {
            put("status", "played")
            put("durationMillis", result.durationMillis)
        })
    }

    companion object { const val NAME = "speak_text" }
}
