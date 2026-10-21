package dev.kiora.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Pi-style text file tools and an application-UID shell for a host-owned workspace. */
class WorkspaceTools(
    directory: File,
    private val shellExecutable: String = "sh",
    private val shellTimeoutMillis: Long = 15_000,
) {
    val root: File = directory.apply { mkdirs() }.canonicalFile

    init {
        require(root.isDirectory) { "Workspace directory is unavailable" }
        require(shellTimeoutMillis in 1..60_000) { "Shell timeout must be 1–60000 ms" }
    }

    fun all(): List<AgentTool> = listOf(Read(), Write(), Edit(), Shell())

    private fun file(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Use a relative workspace path" }
        val resolved = File(root, path).canonicalFile
        require(resolved != root && resolved.path.startsWith(root.path + File.separator)) {
            "Path is outside the agent workspace"
        }
        return resolved
    }

    private fun text(file: File): String {
        require(file.isFile) { "File does not exist: ${file.name}" }
        require(file.length() <= MAX_FILE_BYTES) { "File exceeds the 1 MiB text limit" }
        val bytes = file.readBytes()
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun write(file: File, content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Content exceeds the 1 MiB text limit" }
        require(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "Cannot create parent directory" }
        require(file.canonicalPath.startsWith(root.path + File.separator)) { "Path is outside the agent workspace" }
        require(!file.isDirectory) { "Path is a directory" }
        file.writeText(content, Charsets.UTF_8)
    }

    private inner class Read : AgentTool {
        override val definition = ToolDefinition(
            "read", "Read a UTF-8 text file in the app's agent workspace. Use a relative path; images and files outside the workspace are unavailable.",
            parameters("path" to stringProperty("Relative file path"), required = listOf("path")),
        )

        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
            val path = arguments.string("path")
            val content = text(file(path))
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_RESULT_BYTES) content else {
                content.take(MAX_RESULT_BYTES) + "\n[Output truncated at 32 KiB]"
            }
        }
    }

    private inner class Write : AgentTool {
        override val definition = ToolDefinition(
            "write", "Create or overwrite a UTF-8 text file in the app's agent workspace. Use a relative path.",
            parameters(
                "path" to stringProperty("Relative file path"),
                "content" to stringProperty("Complete replacement text"),
                required = listOf("path", "content"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
            val path = arguments.string("path")
            write(file(path), arguments.string("content", allowBlank = true))
            "Wrote $path"
        }
    }

    private inner class Edit : AgentTool {
        override val definition = ToolDefinition(
            "edit", "Replace one exact, unique text occurrence in a UTF-8 file in the app's agent workspace. Use read first if needed.",
            parameters(
                "path" to stringProperty("Relative file path"),
                "oldText" to stringProperty("Exact text that occurs once"),
                "newText" to stringProperty("Replacement text"),
                required = listOf("path", "oldText", "newText"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
            val path = arguments.string("path")
            val oldText = arguments.string("oldText")
            val newText = arguments.string("newText", allowBlank = true)
            val target = file(path)
            val original = text(target)
            val first = original.indexOf(oldText)
            require(first >= 0) { "oldText was not found" }
            require(original.indexOf(oldText, first + oldText.length) < 0) { "oldText occurs more than once" }
            write(target, original.replaceRange(first, first + oldText.length, newText))
            "Edited $path"
        }
    }

    private inner class Shell : AgentTool {
        // A shell can exercise all permissions of the Android app UID, including network access.
        override val definition = ToolDefinition(
            "shell", "Run one shell command as this app's Android UID, starting in the agent workspace. The command is not confined to the workspace; Android app permissions still apply. Output and runtime are limited.",
            parameters("command" to stringProperty("Shell command to run"), required = listOf("command")),
        )

        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
            val command = arguments.string("command")
            require(command.length <= 4096) { "Command exceeds 4096 characters" }
            val process = ProcessBuilder(shellExecutable, "-c", command)
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/system/bin:/system/xbin:/usr/bin:/bin"
                    environment()["HOME"] = root.path
                }
                .start()
            val output = ByteArrayOutputStream()
            var truncated = false
            val reader = Thread {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = MAX_RESULT_BYTES - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                            if (count > remaining) truncated = true
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                if (!process.waitFor(shellTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    error("Shell command timed out after ${shellTimeoutMillis} ms")
                }
                reader.join(1_000)
                val captured = synchronized(output) { output.toByteArray().toString(Charsets.UTF_8) }
                "exit=${process.exitValue()}\n$captured" + if (truncated) "\n[Output truncated at 32 KiB]" else ""
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun JsonObject.string(key: String, allowBlank: Boolean = false): String {
        val value = this[key]?.jsonPrimitive?.content ?: error("$key is required")
        require(allowBlank || value.isNotBlank()) { "$key must not be blank" }
        return value
    }

    private fun stringProperty(description: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("string"), "description" to JsonPrimitive(description),
    ))

    private fun parameters(vararg properties: Pair<String, JsonObject>, required: List<String>): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(properties.toMap()),
        "required" to JsonArray(required.map(::JsonPrimitive)),
    ))

    private companion object {
        const val MAX_FILE_BYTES = 1024 * 1024L
        const val MAX_RESULT_BYTES = 32 * 1024
    }
}
