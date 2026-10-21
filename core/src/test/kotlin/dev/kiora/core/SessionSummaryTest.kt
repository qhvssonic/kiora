package dev.kiora.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionSummaryTest {
    @Test
    fun `summary follows its branch and raw messages remain available`() {
        val session = SessionTree.create("branches")
        session.append(AgentMessage.User("one"), "u1", 1)
        session.append(AgentMessage.Assistant("answer one"), "a1", 2)
        session.append(AgentMessage.User("two"), "u2", 3)
        session.append(AgentMessage.Assistant("answer two"), "a2", 4)
        session.addSummary(SessionSummary("s1", "a1", 5, "first turn"))
        session.addSummary(SessionSummary("s2", "a2", 6, "first two turns"))

        assertEquals(listOf(AgentMessage.ContextSummary("first two turns")), session.modelMessagesThrough())
        assertEquals(4, session.activeMessages().size)
        session.branchTo("u2")
        assertEquals(listOf(AgentMessage.ContextSummary("first turn"), AgentMessage.User("two")),
            session.modelMessagesThrough())
        session.branchTo("u1")
        assertNull(session.activeSummary())
        assertEquals(listOf(AgentMessage.User("one")), session.modelMessagesThrough())
    }

    @Test
    fun `runtime compacts completed older turns and preserves the current turn`() = runBlocking {
        val store = InMemorySessionStore()
        val seen = mutableListOf<List<AgentMessage>>()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                seen += messages
                return AgentMessage.Assistant("answer")
            }
        }
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        runtime.run("compact", "one")
        runtime.run("compact", "two")
        runtime.run("compact", "three")
        val summarized = mutableListOf<AgentMessage>()
        val summary = runtime.compact("compact", keepRecentTurns = 1) { messages ->
            summarized += messages
            "Key fact from earlier turns"
        }

        assertEquals(4, summarized.size)
        assertEquals("Key fact from earlier turns", summary!!.text)
        assertEquals(6, runtime.session("compact")!!.activeMessages().size)
        assertNull(runtime.compact("compact", keepRecentTurns = 1) { error("Must not summarize twice") })
        runtime.run("compact", "four")
        assertEquals(AgentMessage.ContextSummary("Key fact from earlier turns"), seen.last().first())
        assertEquals(listOf("three", "four"), seen.last().filterIsInstance<AgentMessage.User>().map { it.text })
        assertIs<AgentMessage.Assistant>(runtime.session("compact")!!.activeMessages().last())
    }

    @Test
    fun `provider usage is tied to the assistant entry and follows the branch`() = runBlocking {
        val store = InMemorySessionStore()
        val events = mutableListOf<AgentEvent.ModelUsageReported>()
        val model = object : UsageReportingModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                respondWithUsage(messages, tools).assistant
            override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                ModelResponse(AgentMessage.Assistant("done"), ModelUsage(12, 3, 15))
        }
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed },
            events = { if (it is AgentEvent.ModelUsageReported) events += it })
        runtime.run("usage", "one")
        val beforeBranch = runtime.session("usage")!!
        val usage = beforeBranch.activeUsages().single()
        assertEquals(beforeBranch.activeEntries().last().id, usage.assistantEntryId)
        assertEquals(ModelUsage(12, 3, 15), usage.usage)
        assertEquals(usage, events.single().record)

        runtime.branch("usage", beforeBranch.activeEntries().first().id)
        assertEquals(emptyList(), runtime.session("usage")!!.activeUsages())
        assertEquals(1, runtime.session("usage")!!.allUsages().size)
    }

    @Test
    fun `actual model identity is recorded even without usage and follows its branch`() = runBlocking {
        val store = InMemorySessionStore()
        val events = mutableListOf<AgentEvent.ModelInvocationRecorded>()
        val model = object : UsageReportingModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                respondWithUsage(messages, tools).assistant
            override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                ModelResponse(AgentMessage.Assistant("done"), model = ModelIdentity("physical-model", "cloud-a"))
        }
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed },
            events = { if (it is AgentEvent.ModelInvocationRecorded) events += it })
        runtime.run("models", "one")
        val beforeBranch = runtime.session("models")!!
        val invocation = beforeBranch.activeModelInvocations().single()
        assertEquals(ModelIdentity("physical-model", "cloud-a"), invocation.model)
        assertEquals(beforeBranch.activeEntries().last().id, invocation.assistantEntryId)
        assertEquals(invocation, events.single().record)
        assertEquals(emptyList(), beforeBranch.activeUsages())
        runtime.branch("models", beforeBranch.activeEntries().first().id)
        assertEquals(emptyList(), runtime.session("models")!!.activeModelInvocations())
        assertEquals(1, runtime.session("models")!!.allModelInvocations().size)
    }

    @Test
    fun `later compaction receives the previous summary plus newer messages`() = runBlocking {
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("answer")
        }
        val runtime = AgentRuntime(model, emptyList(), InMemorySessionStore(), { ToolAuthorization.Allowed })
        runtime.run("iterative", "one")
        runtime.run("iterative", "two")
        runtime.compact("iterative", keepRecentTurns = 1) { "First summary" }
        runtime.run("iterative", "three")
        val seen = mutableListOf<AgentMessage>()
        val second = runtime.compact("iterative", keepRecentTurns = 1) { context ->
            seen += context
            "Updated summary"
        }

        assertEquals(AgentMessage.ContextSummary("First summary"), seen.first())
        assertEquals(listOf("two"), seen.filterIsInstance<AgentMessage.User>().map { it.text })
        assertEquals("Updated summary", second!!.text)
        assertEquals(AgentMessage.ContextSummary("Updated summary"),
            runtime.session("iterative")!!.modelMessagesThrough().first())
    }
}
