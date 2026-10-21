package dev.kiora.core

import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionQueueTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `steering queued during a tool call is applied before the next model turn`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val tool = object : AgentTool {
            override val definition = ToolDefinition("wait", "Wait", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String {
                started.complete(Unit)
                resume.await()
                return "ready"
            }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if ((messages.last() as? AgentMessage.User)?.text == "change") AgentMessage.Assistant("changed")
                else AgentMessage.Assistant("", listOf(ToolCall("call", "wait", JsonObject(emptyMap()))))
        }
        val store = JsonlSessionStore(directory.toFile())
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed })
        val running = async { runtime.run("steering", "start") }
        started.await()
        val queued = runtime.queueSteering("steering", "change")
        resume.complete(Unit)

        assertEquals("changed", assertIs<RunResult.Completed>(running.await()).text)
        val session = JsonlSessionStore(directory.toFile()).load("steering")!!
        assertEquals(listOf("start", "change"), session.activeMessages()
            .filterIsInstance<AgentMessage.User>().map { it.text })
        assertTrue(session.pendingQueuedInputs().isEmpty())
        assertEquals(setOf(queued.id), session.consumedQueuedInputIds())
    }

    @Test
    fun `follow-up waits for a completed answer and receives its own model turn`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var turns = 0
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                turns++
                return if (turns == 1) {
                    started.complete(Unit)
                    resume.await()
                    AgentMessage.Assistant("first answer")
                } else {
                    assertEquals("next question", (messages.last() as AgentMessage.User).text)
                    AgentMessage.Assistant("second answer")
                }
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        val running = async { runtime.run("followup", "first question") }
        started.await()
        runtime.queueFollowUp("followup", "next question")
        resume.complete(Unit)

        assertEquals("second answer", assertIs<RunResult.Completed>(running.await()).text)
        assertEquals(2, turns)
        assertTrue(store.load("followup")!!.pendingQueuedInputs().isEmpty())
    }

    @Test
    fun `JSONL queued input is consumed by the same durable message record`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("queue")
        store.append("queue", SessionEntry("u", null, 1, AgentMessage.User("first")))
        store.append("queue", SessionEntry("a", "u", 2, AgentMessage.Assistant("answered")))
        val queued = QueuedUserInput("q", QueuedInputKind.FOLLOW_UP, "later", "a", 3)
        store.enqueueInput("queue", queued)
        assertEquals(listOf(queued), JsonlSessionStore(directory.toFile()).load("queue")!!.pendingQueuedInputs())
        store.appendQueuedMessage("queue", SessionEntry("u2", "a", 4, AgentMessage.User("later")), "q")

        val reopened = JsonlSessionStore(directory.toFile()).load("queue")!!
        assertTrue(reopened.pendingQueuedInputs().isEmpty())
        assertEquals(setOf("q"), reopened.consumedQueuedInputIds())
        assertEquals(listOf("first", "later"), reopened.activeMessages()
            .filterIsInstance<AgentMessage.User>().map { it.text })
    }

    @Test
    fun `queued input stays with its branch`() {
        val session = SessionTree.create("branch-queue")
        session.append(AgentMessage.User("one"), "u1", 1)
        session.append(AgentMessage.Assistant("one answer"), "a1", 2)
        session.append(AgentMessage.User("two"), "u2", 3)
        session.append(AgentMessage.Assistant("two answer"), "a2", 4)
        val input = QueuedUserInput("q", QueuedInputKind.STEERING, "change two", "a2", 5)
        session.addQueuedInput(input)

        session.branchTo("a1")
        assertTrue(session.pendingQueuedInputs().isEmpty())
        session.branchTo("a2")
        assertEquals(listOf(input), session.pendingQueuedInputs())
    }

    @Test
    fun `queued follow-up remains pending when the model turn budget is exhausted`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                started.complete(Unit)
                resume.await()
                return AgentMessage.Assistant("answer")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed },
            limits = RunLimits(maxModelTurns = 1))
        val running = async { runtime.run("limited", "first") }
        started.await()
        val queued = runtime.queueFollowUp("limited", "later")
        resume.complete(Unit)

        assertIs<RunResult.Completed>(running.await())
        assertEquals(listOf(queued), store.load("limited")!!.pendingQueuedInputs())
    }

    @Test
    fun `a queued item can start a new run without appearing twice`() = runBlocking {
        val store = InMemorySessionStore()
        store.create("guided")
        store.append("guided", SessionEntry("first", null, 1, AgentMessage.User("first")))
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("answered ${(messages.last() as AgentMessage.User).text}")
        }
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        val queued = runtime.queueFollowUp("guided", "guide me")

        assertEquals("answered guide me", assertIs<RunResult.Completed>(
            runtime.runQueued("guided", queued.id)).text)
        val session = store.load("guided")!!
        assertEquals(listOf("first", "guide me"), session.activeMessages()
            .filterIsInstance<AgentMessage.User>().map { it.text })
        assertTrue(session.pendingQueuedInputs().isEmpty())
        assertEquals(setOf(queued.id), session.consumedQueuedInputIds())
    }

    @Test
    fun `discarded queued input cannot enter an active run`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                started.complete(Unit)
                resume.await()
                return AgentMessage.Assistant("answer")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        val running = async { runtime.run("discard", "first") }
        started.await()
        val queued = runtime.queueFollowUp("discard", "do not run")
        runtime.discardQueuedInput("discard", queued.id)
        resume.complete(Unit)

        assertEquals("answer", assertIs<RunResult.Completed>(running.await()).text)
        val session = store.load("discard")!!
        assertEquals(listOf("first"), session.activeMessages()
            .filterIsInstance<AgentMessage.User>().map { it.text })
        assertTrue(session.pendingQueuedInputs().isEmpty())
    }

    @Test
    fun `JSONL discard survives reopening`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("discarded")
        store.append("discarded", SessionEntry("u", null, 1, AgentMessage.User("first")))
        val runtime = AgentRuntime(object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("done")
        },
            emptyList(), store, { ToolAuthorization.Allowed })
        val queued = runtime.queueFollowUp("discarded", "remove")
        runtime.discardQueuedInput("discarded", queued.id)

        val reopened = JsonlSessionStore(directory.toFile()).load("discarded")!!
        assertTrue(reopened.pendingQueuedInputs().isEmpty())
        assertEquals(setOf(queued.id), reopened.consumedQueuedInputIds())
    }
}
