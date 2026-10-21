package dev.kiora.a2a

import dev.kiora.core.ProgressReportingAgentTool
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolOutput
import dev.kiora.core.ToolAttention
import dev.kiora.core.ToolProgressSink
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Transport-neutral contract; bind it to an A2A v1 SDK in the host application. */
interface A2aPeer {
    suspend fun send(instruction: String): RemoteTask
    suspend fun continueTask(taskId: String, instruction: String): RemoteTask
    suspend fun send(message: A2aUserMessage): RemoteTask {
        require(message.files.isEmpty()) { "This A2A peer does not support inline files" }
        return send(message.text)
    }
    suspend fun continueTask(taskId: String, message: A2aUserMessage): RemoteTask {
        require(message.files.isEmpty()) { "This A2A peer does not support inline files" }
        return continueTask(taskId, message.text)
    }
    suspend fun getTask(taskId: String): RemoteTask
    suspend fun cancelTask(taskId: String)
}

enum class RemoteTaskState {
    SUBMITTED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED, COMPLETED, FAILED, CANCELED, REJECTED,
}

data class RemoteTask(
    val id: String,
    val state: RemoteTaskState,
    val output: String? = null,
)

/** The agent may delegate a task to a peer while keeping its own local session. */
class A2aDelegateTool(
    private val peer: A2aPeer,
    private val pollIntervalMillis: Long = 500,
    private val maxPolls: Int = 120,
) : ProgressReportingAgentTool {
    init {
        require(pollIntervalMillis > 0 && maxPolls > 0)
    }

    override val definition = ToolDefinition(
        name = "delegate_to_desktop",
        description = "Ask the desktop agent to perform a task. If it requests input, call again with taskId.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "instruction" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "taskId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("instruction"))),
        )),
        outputSchema = taskOutputSchema,
    )

    override suspend fun execute(arguments: JsonObject): String = executeArgumentsResult(arguments, null).text

    override suspend fun executeResult(arguments: JsonObject): ToolOutput = executeArgumentsResult(arguments, null)

    override suspend fun executeResult(arguments: JsonObject, progress: ToolProgressSink): ToolOutput =
        executeArgumentsResult(arguments, progress)

    private suspend fun executeArgumentsResult(arguments: JsonObject, progress: ToolProgressSink?): ToolOutput {
        val instruction = arguments["instruction"]?.jsonPrimitive?.content?.trim()
        require(!instruction.isNullOrEmpty()) { "instruction is required" }
        val priorTaskId = arguments["taskId"]?.jsonPrimitive?.content
        return executeMessageResult(A2aUserMessage(instruction), priorTaskId, progress)
    }

    suspend fun executeMessage(message: A2aUserMessage, priorTaskId: String? = null): String =
        executeMessageResult(message, priorTaskId, null).text

    suspend fun executeMessage(message: A2aUserMessage, priorTaskId: String?, progress: ToolProgressSink?): String {
        return executeMessageResult(message, priorTaskId, progress).text
    }

    suspend fun executeMessageResult(message: A2aUserMessage, priorTaskId: String? = null,
                                     progress: ToolProgressSink? = null): ToolOutput {
        var task = if (priorTaskId == null) peer.send(message) else peer.continueTask(priorTaskId, message)
        var lastReportedState: RemoteTaskState? = null
        repeat(maxPolls + 1) { poll ->
            if (task.state != lastReportedState &&
                (task.state == RemoteTaskState.SUBMITTED || task.state == RemoteTaskState.WORKING)) {
                progress?.report("Task ${task.id} ${task.state.name.lowercase()}")
                lastReportedState = task.state
            }
            when (task.state) {
                RemoteTaskState.COMPLETED -> return taskOutput(task, "completed")
                RemoteTaskState.INPUT_REQUIRED -> return taskOutput(task, "needs input", ToolAttention.INPUT_REQUIRED)
                RemoteTaskState.AUTH_REQUIRED -> return taskOutput(task, "needs authorization", ToolAttention.AUTH_REQUIRED)
                RemoteTaskState.FAILED, RemoteTaskState.CANCELED, RemoteTaskState.REJECTED -> error(
                    "Task ${task.id} ${task.state.name.lowercase()}: ${task.output.orEmpty()}",
                )
                RemoteTaskState.SUBMITTED, RemoteTaskState.WORKING -> {
                    if (poll < maxPolls) {
                        delay(pollIntervalMillis)
                        task = peer.getTask(task.id)
                    }
                }
            }
        }
        error("Task ${task.id} did not finish within the polling limit")
    }

    private fun taskOutput(task: RemoteTask, label: String, attention: ToolAttention? = null): ToolOutput =
        ToolOutput(
            "Task ${task.id} $label: ${task.output.orEmpty()}",
            structuredContent = JsonObject(mapOf(
                "taskId" to JsonPrimitive(task.id),
                "state" to JsonPrimitive(task.state.name),
                "output" to JsonPrimitive(task.output.orEmpty()),
            )),
            attention = attention,
        )
}

internal val taskOutputSchema = JsonObject(mapOf(
    "type" to JsonPrimitive("object"),
    "properties" to JsonObject(mapOf(
        "taskId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        "state" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        "output" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
    )),
    "required" to kotlinx.serialization.json.JsonArray(listOf(
        JsonPrimitive("taskId"), JsonPrimitive("state"), JsonPrimitive("output"))),
))
