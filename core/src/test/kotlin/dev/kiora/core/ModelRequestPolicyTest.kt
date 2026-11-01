package dev.kiora.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class ModelRequestPolicyTest {
    @Test fun `egress rejection inside a tool stops the run and does not ask model to work around it`() = runBlocking {
        var calls = 0
        var sideEffects = 0
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                calls++
                return AgentMessage.Assistant("", listOf(
                    ToolCall("remote", "delegate", JsonObject(emptyMap())),
                    ToolCall("later", "write", JsonObject(emptyMap()))))
            }
        }
        val delegate = object : AgentTool {
            override val definition = ToolDefinition("delegate", "send", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String = throw ModelRequestRejectedException("denied")
        }
        val write = object : AgentTool {
            override val definition = ToolDefinition("write", "write", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String { sideEffects++; return "written" }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(delegate, write), store, { ToolAuthorization.Allowed })
        assertIs<RunResult.Failed>(runtime.run("s", "send"))
        assertEquals(1, calls)
        assertEquals(0, sideEffects)
        assertEquals(listOf(ToolFailureKind.AUTHORIZATION_DENIED, ToolFailureKind.AUTHORIZATION_DENIED),
            store.load("s")!!.activeMessages().filterIsInstance<AgentMessage.ToolResult>().map { it.failureKind })
    }
    @Test fun `denied tool output never reaches next model request`() = runBlocking {
        var calls = 0
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                calls++
                check(messages.none { it is AgentMessage.ToolResult }) { "private content leaked" }
                return AgentMessage.Assistant("", listOf(ToolCall("one", "read", JsonObject(emptyMap()))))
            }
        }
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read", "read", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "private document"
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed },
            modelRequestPolicy = ModelRequestPolicy { request ->
                if (request.messages.any { it is AgentMessage.ToolResult })
                    throw ModelRequestRejectedException("local only")
            })
        assertIs<RunResult.Failed>(runtime.run("private", "Read"))
        assertEquals(1, calls)
        assertEquals("private document", store.load("private")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single().text)
    }

    @Test fun `compaction refusal prevents summary inference and fallback request`() = runBlocking {
        var calls = 0
        var summaries = 0
        val store = InMemorySessionStore()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("answer").also { calls++ }
        }
        val initial = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        initial.run("s", "old question")
        val guarded = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed },
            autoCompaction = AutoCompaction(1, SessionSummarizer { summaries++; "summary" }),
            modelRequestPolicy = ModelRequestPolicy {
                if (it.purpose == ModelRequestPurpose.SUMMARY) throw ModelRequestRejectedException("denied")
            })
        assertIs<RunResult.Failed>(guarded.run("s", "next question"))
        assertEquals(0, summaries)
        assertEquals(1, calls)
    }
}
