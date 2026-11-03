package dev.kiora.core

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ToolVerificationTest {
    @TempDir lateinit var directory: Path
    private fun args(vararg fields: Pair<String, String>) =
        JsonObject(fields.associate { it.first to JsonPrimitive(it.second) })

    private suspend fun run(tool: AgentTool, arguments: JsonObject = JsonObject(emptyMap())): AgentMessage.ToolResult {
        val store = JsonlSessionStore(directory.toFile())
        var modelCalls = 0
        var modelResult: AgentMessage.ToolResult? = null
        var eventResult: AgentMessage.ToolResult? = null
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                modelCalls++
                return if (messages.last() is AgentMessage.User)
                    AgentMessage.Assistant("", listOf(ToolCall("c", tool.definition.name, arguments)))
                else {
                    modelResult = messages.last() as AgentMessage.ToolResult
                    AgentMessage.Assistant("observed")
                }
            }
        }
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed },
            events = EventSink { if (it is AgentEvent.ToolCompleted) eventResult = it.result })
        assertIs<RunResult.Completed>(runtime.run("s", "perform the action"))
        val persisted = JsonlSessionStore(directory.toFile()).load("s")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals(modelResult, persisted)
        assertEquals(eventResult, persisted)
        assertEquals(2, modelCalls)
        return persisted
    }

    private fun action(verify: suspend () -> ToolVerification) = object : AgentTool, ToolEffectVerifier {
        var calls = 0
        override val definition = ToolDefinition("action", "Perform an action", JsonObject(emptyMap()))
        override suspend fun execute(arguments: JsonObject): String { calls++; return "request accepted" }
        override suspend fun verifyEffect(arguments: JsonObject, output: ToolOutput) = verify()
    }

    @Test fun `silent failure becomes a durable model visible error without reexecution`() = runBlocking {
        val tool = action { ToolVerification(ToolVerificationStatus.FAILED, "Target state was not observed") }
        val result = run(tool)
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.VERIFICATION_FAILED, result.failureKind)
        assertContains(result.text, "Target state was not observed")
        assertEquals(ToolVerificationStatus.FAILED, result.verification!!.status)
        assertEquals(1, tool.calls)
    }

    @Test fun `observer exception leaves action unverified and does not leak exception details`() = runBlocking {
        val tool = action { error("secret provider internals") }
        val result = run(tool)
        assertFalse(result.isError)
        assertEquals(ToolVerificationStatus.UNVERIFIED, result.verification!!.status)
        assertContains(result.text, "Do not assume completion")
        assertFalse(result.text.contains("secret"))
        assertEquals(1, tool.calls)
    }

    @Test fun `verification cancellation propagates and records unknown execution state`() = runBlocking {
        val tool = action { throw CancellationException("cancel observer") }
        assertFailsWith<CancellationException> { run(tool) }
        val result = JsonlSessionStore(directory.toFile()).load("s")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals(ToolFailureKind.INTERRUPTED, result.failureKind)
        assertContains(result.text, "unknown")
        assertEquals(1, tool.calls)
    }

    @Test fun `observer timeout does not turn an accepted action into a false failure`() = runBlocking {
        val tool = action { awaitCancellation() }
        val result = run(tool)
        assertFalse(result.isError)
        assertEquals(ToolVerificationStatus.UNVERIFIED, result.verification!!.status)
        assertContains(result.text, "timed out")
        assertEquals(1, tool.calls)
    }

    @Test fun `legacy tool return does not claim verified completion`() = runBlocking {
        val result = run(object : AgentTool {
            override val definition = ToolDefinition("legacy", "legacy", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "ok"
        })
        assertNull(result.verification)
        assertEquals("ok", result.text)
    }

    @Test fun `explicit execution error skips observer`() = runBlocking {
        val tool = object : AgentTool, ToolEffectVerifier {
            override val definition = ToolDefinition("failed", "failed", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "failed"
            override suspend fun executeResult(arguments: JsonObject) = ToolOutput("failed", isError = true)
            override suspend fun verifyEffect(arguments: JsonObject, output: ToolOutput): ToolVerification = error("Must not observe")
        }
        assertEquals(ToolFailureKind.EXECUTION_FAILED, run(tool).failureKind)
    }

    @Test fun `workspace write and edit read back full unicode and empty content`() = runBlocking {
        val tools = WorkspaceTools(directory.resolve("work").toFile()).all().associateBy { it.definition.name }
        val write = run(tools.getValue("write"), args("path" to "note.txt", "content" to "第一行\nhello"))
        assertEquals(ToolVerificationStatus.VERIFIED, write.verification!!.status)
        // Each helper run needs a separate session store directory.
        val edit = tools.getValue("edit").executeResult(args("path" to "note.txt", "oldText" to "hello", "newText" to ""))
        assertEquals(ToolVerificationStatus.VERIFIED, edit.verification!!.status)
        assertEquals("第一行\n", directory.resolve("work/note.txt").toFile().readText())
        val empty = args("path" to "empty.txt", "content" to "")
        val tool = tools.getValue("write")
        val output = tool.executeResult(empty)
        assertEquals(ToolVerificationStatus.VERIFIED, (tool as ToolEffectVerifier).verifyEffect(empty, output).status)
    }

    @Test fun `write observer detects a changed file instead of trusting write return`() = runBlocking {
        val tool = WorkspaceTools(directory.toFile()).all().first { it.definition.name == "write" }
        val arguments = args("path" to "note", "content" to "expected")
        val output = tool.executeResult(arguments)
        directory.resolve("note").toFile().writeText("different")
        assertEquals(ToolVerificationStatus.FAILED, (tool as ToolEffectVerifier).verifyEffect(arguments, output).status)
    }

    @Test fun `nonzero shell exit is an error in model events and persisted history`() = runBlocking {
        val result = run(shell(), args("command" to if (windows()) "Write-Output denied; exit 7" else "echo denied; exit 7"))
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.EXECUTION_FAILED, result.failureKind)
        assertEquals(7, result.structuredContent!!.jsonObject.getValue("exitCode").jsonPrimitive.int)
        assertContains(result.text, "denied")
    }

    @Test fun `zero shell exit does not prove a downstream effect`() = runBlocking {
        val result = run(shell(), args("command" to "exit 0"))
        assertFalse(result.isError)
        assertEquals(ToolVerificationStatus.UNVERIFIED, result.verification!!.status)
        assertContains(result.text, "not independently checked")
    }

    private fun windows() = System.getProperty("os.name").startsWith("Windows")

    @Test fun `old default ToolOutput constructor remains callable by compiled Android adapters`() {
        val constructor = ToolOutput::class.java.getConstructor(String::class.java, List::class.java,
            kotlinx.serialization.json.JsonElement::class.java, Boolean::class.javaPrimitiveType,
            ToolAttention::class.java, Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
        val output = constructor.newInstance("legacy", null, null, false, null, 30, null) as ToolOutput
        assertEquals("legacy", output.text)
        assertEquals(emptyList(), output.images)
        assertNull(output.verification)
    }

    private fun shell() = WorkspaceTools(directory.resolve("work").toFile(),
        if (windows()) File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe").path
        else "/bin/sh").all().first { it.definition.name == "shell" }
}
