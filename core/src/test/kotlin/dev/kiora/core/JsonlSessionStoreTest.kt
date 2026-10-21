package dev.kiora.core

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsonlSessionStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `session and cursor survive reopening`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("one")
        val first = SessionEntry("a", null, 1, AgentMessage.User("first"))
        val second = SessionEntry("b", "a", 2, AgentMessage.Assistant("old answer"))
        store.append("one", first)
        store.append("one", second)
        store.moveLeaf("one", "a")
        store.append("one", SessionEntry("c", "a", 3, AgentMessage.User("new branch")))

        val reopened = JsonlSessionStore(directory.toFile()).load("one")!!
        assertEquals(3, reopened.allEntries().size)
        assertEquals(listOf("a", "c"), reopened.activeEntries().map { it.id })
        assertEquals("new branch", assertIs<AgentMessage.User>(reopened.activeMessages().last()).text)
    }

    @Test
    fun `incomplete final record is ignored and next append repairs the file`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("interrupted")
        store.append("interrupted", SessionEntry("a", null, 1, AgentMessage.User("first")))
        directory.resolve("interrupted.jsonl").toFile().appendText("{\"type\":\"message\"")

        assertEquals(1, store.load("interrupted")!!.allEntries().size)
        store.append("interrupted", SessionEntry("b", "a", 2, AgentMessage.Assistant("recovered")))

        assertEquals(listOf("a", "b"), JsonlSessionStore(directory.toFile()).load("interrupted")!!
            .activeEntries().map { it.id })
    }

    @Test
    fun `image tool result survives reopening`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("images")
        val result = AgentMessage.ToolResult("call", "read", "Photo",
            images = listOf(ToolImage("image/jpeg", "YQ==")))
        store.append("images", SessionEntry("a", null, 1, result))
        val reopened = JsonlSessionStore(directory.toFile()).load("images")!!
        assertEquals(result, reopened.activeMessages().single())
    }

    @Test
    fun `selected user image survives reopening`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("selected")
        val user = AgentMessage.User("describe", listOf(ToolImage("image/jpeg", "YQ==")))
        store.append("selected", SessionEntry("a", null, 1, user))
        assertEquals(user, JsonlSessionStore(directory.toFile()).load("selected")!!
            .activeMessages().single())
    }

    @Test
    fun `failure category survives reopening and old errors remain readable`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("failures")
        val classified = AgentMessage.ToolResult("one", "read", "Denied", true,
            failureKind = ToolFailureKind.AUTHORIZATION_DENIED)
        val legacy = AgentMessage.ToolResult("two", "read", "Old error", true)
        store.append("failures", SessionEntry("a", null, 1, classified))
        store.append("failures", SessionEntry("b", "a", 2, legacy))

        val results = JsonlSessionStore(directory.toFile()).load("failures")!!.activeMessages()
        assertEquals(listOf(classified, legacy), results)
    }

    @Test
    fun `summary survives reopening and follows only its branch`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("summary")
        store.append("summary", SessionEntry("u1", null, 1, AgentMessage.User("one")))
        store.append("summary", SessionEntry("a1", "u1", 2, AgentMessage.Assistant("answer")))
        store.appendSummary("summary", SessionSummary("s1", "a1", 3, "Earlier context"))
        store.append("summary", SessionEntry("u2", "a1", 4, AgentMessage.User("two")))

        val reopened = JsonlSessionStore(directory.toFile()).load("summary")!!
        assertEquals(listOf(AgentMessage.ContextSummary("Earlier context"), AgentMessage.User("two")),
            reopened.modelMessagesThrough())
        assertEquals(3, reopened.activeMessages().size)
        reopened.branchTo("u1")
        assertEquals(listOf(AgentMessage.User("one")), reopened.modelMessagesThrough())
    }

    @Test
    fun `usage metadata survives reopening without entering conversation messages`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("usage")
        store.append("usage", SessionEntry("u", null, 1, AgentMessage.User("hi")))
        store.append("usage", SessionEntry("a", "u", 2, AgentMessage.Assistant("hello")))
        val counts = SessionUsage("tokens", "a", 3, ModelUsage(10, 2, 12, 4))
        store.appendUsage("usage", counts)

        val reopened = JsonlSessionStore(directory.toFile()).load("usage")!!
        assertEquals(listOf(counts), reopened.activeUsages())
        assertEquals(2, reopened.activeMessages().size)
    }

    @Test
    fun `model identity and structured tool attention survive reopening`() = runBlocking {
        val store = JsonlSessionStore(directory.toFile())
        store.create("metadata")
        val call = ToolCall("c", "remote", kotlinx.serialization.json.JsonObject(emptyMap()))
        store.append("metadata", SessionEntry("u", null, 1, AgentMessage.User("work")))
        store.append("metadata", SessionEntry("a", "u", 2, AgentMessage.Assistant("", listOf(call))))
        val invocation = SessionModelInvocation("m", "a", 3, ModelIdentity("actual", "provider"))
        store.appendModelInvocation("metadata", invocation)
        val tool = AgentMessage.ToolResult("c", "remote", "needs input", false, emptyList(), null,
            kotlinx.serialization.json.JsonObject(mapOf("taskId" to kotlinx.serialization.json.JsonPrimitive("t"))),
            ToolAttention.INPUT_REQUIRED)
        store.append("metadata", SessionEntry("t", "a", 4, tool))

        val reopened = JsonlSessionStore(directory.toFile()).load("metadata")!!
        assertEquals(listOf(invocation), reopened.activeModelInvocations())
        assertEquals(tool, reopened.activeMessages().last())
    }
}
