package dev.kiora.a2a

import dev.kiora.core.ProgressReportingAgentTool
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolOutput
import dev.kiora.core.ToolProgressSink
import dev.kiora.core.ToolArgumentPrevalidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One Agent Card skill exposed as a model tool. Android file access is supplied by the host. */
class A2aSkillTool(
    peer: A2aPeer,
    agentName: String,
    val skill: A2aSkill,
    toolName: String,
    inputModes: List<String> = skill.inputModes,
    private val fileLoader: (suspend (List<String>) -> List<A2aInlineFile>)? = null,
    private val messageSender: (suspend (A2aUserMessage, String?) -> String)? = null,
    private val resultSender: (suspend (A2aUserMessage, String?) -> ToolOutput)? = null,
    private val fileIdValidator: (suspend (List<String>) -> String?)? = null,
    private val argumentValidator: (suspend (JsonObject) -> String?)? = null,
    private val requireFileSource: Boolean = false,
) : ProgressReportingAgentTool, ToolArgumentPrevalidator {
    init { require(messageSender == null || resultSender == null) { "Choose one A2A sender" } }
    private val delegate = A2aDelegateTool(peer)
    private val acceptedModes = inputModes.map(String::lowercase)
    val acceptsForm: Boolean = acceptedModes.any { it == "application/json" || it.endsWith("+json") }
    val acceptsFiles: Boolean = fileLoader != null && acceptedModes.any(::isFileMode)
    private val needsFileSource = requireFileSource && acceptsFiles

    override val definition = ToolDefinition(
        name = toolName,
        description = buildString {
            append("Use the remote A2A agent")
            if (agentName.isNotBlank()) append(" '$agentName'")
            append(" for its '${skill.name}' capability (skill ID: ${skill.id}). ")
            append(skill.description.take(1_000))
            if (skill.examples.isNotEmpty()) {
                append(" Example requests: ")
                append(skill.examples.take(2).joinToString("; "))
                append(". ")
            }
            append("This tool contacts the remote agent; do not invoke it through a local shell. " +
                "Set instruction to the task for the remote agent and omit local routing phrases. " +
                "For a file already at the remote agent, preserve references to earlier remote work. " +
                "For a file on this device, include its ID in files and refer to the attachment.")
            if (acceptsForm) append(" Optional form fields are sent as an A2A JSON data part.")
            if (acceptsFiles) append(" Use files only when the user asks this skill to process host-approved files. " +
                "A file is transferred only when its host-provided ID appears in the files array; " +
                "mentioning a filename in instruction does not attach it. " +
                "File identifiers are defined and validated by the host. Accepted file MIME types: " +
                acceptedModes.filter(::isFileMode).joinToString(", ") + ".")
            if (needsFileSource) append(" Set fileSource to local when this task needs a file from " +
                "the current device and include its ID in files; use remote when referring to a " +
                "file already available to the remote agent, or none when no file is involved.")
        },
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(buildMap {
                put("instruction", property("string", "Complete task for this Agent Card skill"))
                put("taskId", property("string", "Existing A2A task ID when answering a follow-up"))
                if (acceptsForm) put("form", property("object", "Structured form fields described by this skill"))
                if (acceptsFiles) put("files", JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "description" to JsonPrimitive("Host-provided IDs of files to send with this skill"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                )))
                if (needsFileSource) put("fileSource", JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Where the file needed by this task exists: " +
                        "local (this device, requires files), remote (already at the A2A agent), or none"),
                    "enum" to JsonArray(listOf("local", "remote", "none").map(::JsonPrimitive)),
                )))
            }),
            "required" to JsonArray((listOf("instruction") +
                if (needsFileSource) listOf("fileSource") else emptyList()).map(::JsonPrimitive)),
            "additionalProperties" to JsonPrimitive(false),
        )),
        outputSchema = if (messageSender == null) taskOutputSchema else null,
    )

    override suspend fun execute(arguments: JsonObject): String = executeResultInternal(arguments, null).text

    override suspend fun validateArguments(arguments: JsonObject): String? {
        val ids = arguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        return fileSourceError(arguments, ids) ?: argumentValidator?.invoke(arguments)
            ?: if (ids.isEmpty()) null else fileIdValidator?.invoke(ids)
    }

    override suspend fun executeResult(arguments: JsonObject): ToolOutput = executeResultInternal(arguments, null)

    override suspend fun executeResult(arguments: JsonObject, progress: ToolProgressSink): ToolOutput =
        executeResultInternal(arguments, progress)

    private suspend fun executeResultInternal(arguments: JsonObject, progress: ToolProgressSink?): ToolOutput {
        val instruction = arguments["instruction"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(instruction.isNotBlank()) { "instruction is required" }
        val form = arguments["form"]?.jsonObject
        require(form == null || acceptsForm) { "This skill does not advertise JSON form input" }
        val ids = arguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        fileSourceError(arguments, ids)?.let { error(it) }
        require(ids.isEmpty() || acceptsFiles) { "This skill does not advertise file input" }
        require(ids.size <= 10 && ids.distinct().size == ids.size) { "Choose at most 10 distinct files" }
        val files = if (ids.isEmpty()) emptyList() else requireNotNull(fileLoader)(ids)
        files.forEach { file ->
            require(acceptedModes.any { acceptsMime(it, file.mediaType) }) {
                "${file.filename} (${file.mediaType}) is not accepted by this skill"
            }
        }
        val message = A2aUserMessage(instruction, files, form)
        val taskId = arguments["taskId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        return resultSender?.invoke(message, taskId)
            ?: messageSender?.let { ToolOutput(it(message, taskId)) }
            ?: delegate.executeMessageResult(message, taskId, progress)
    }

    private fun fileSourceError(arguments: JsonObject, ids: List<String>): String? {
        if (!needsFileSource) return null
        return when (arguments["fileSource"]?.jsonPrimitive?.content) {
            "local" -> if (ids.isEmpty()) "本机文件必须通过 files 参数附上，不能只写文件名" else null
            "remote", "none" -> if (ids.isNotEmpty()) "已附上的本机文件需要将 fileSource 设为 local" else null
            else -> "请判断文件来源：fileSource 为 local、remote 或 none"
        }
    }

    private fun property(type: String, description: String) = JsonObject(mapOf(
        "type" to JsonPrimitive(type), "description" to JsonPrimitive(description),
    ))

    private fun isFileMode(mode: String): Boolean =
        mode != "text/plain" && mode != "application/json" && !mode.endsWith("+json")

    private fun acceptsMime(mode: String, mime: String): Boolean =
        mode == "*/*" || mode.equals(mime, ignoreCase = true) ||
            (mode.endsWith("/*") && mime.startsWith(mode.removeSuffix("*"), ignoreCase = true))
}
