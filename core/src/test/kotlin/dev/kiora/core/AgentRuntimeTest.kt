package dev.kiora.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRuntimeTest {
    private val call = ToolCall("call-1", "read_note", JsonObject(emptyMap()))

    @Test
    fun `host file id preflight rejects invalid arguments before authorization`() = runBlocking {
        var authorizationCalls = 0
        var executions = 0
        val tool = object : AgentTool, ToolArgumentPrevalidator {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun validateArguments(arguments: JsonObject) =
                "selected file ID must be selected:0"
            override suspend fun execute(arguments: JsonObject): String {
                executions++
                return "unexpected"
            }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("Please choose selected:0")
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, {
            authorizationCalls++
            ToolAuthorization.Allowed
        })
        assertIs<RunResult.Completed>(runtime.run("preflight", "Send selected file"))
        val result = store.load("preflight")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals(ToolFailureKind.INVALID_ARGUMENTS, result.failureKind)
        assertEquals("selected file ID must be selected:0", result.text)
        assertEquals(0, authorizationCalls)
        assertEquals(0, executions)
    }

    @Test
    fun `tool result returns to model and is stored in session`() = runBlocking {
        var executed = 0
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String {
                executed++
                return "note contents"
            }
        }
        val model = object : ModelPort {
            override suspend fun respond(
                messages: List<AgentMessage>, tools: List<ToolDefinition>,
            ): AgentMessage.Assistant = when (messages.last()) {
                is AgentMessage.User -> AgentMessage.Assistant("", listOf(call))
                is AgentMessage.ToolResult -> AgentMessage.Assistant("The note says: note contents")
                else -> error("Unexpected context")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed })

        val result = runtime.run("session-1", "Read my note")

        assertEquals("The note says: note contents", assertIs<RunResult.Completed>(result).text)
        assertEquals(1, executed)
        assertEquals(4, store.load("session-1")!!.activeMessages().size)
    }

    @Test
    fun `tool attention ends run with a durable continuation result`() = runBlocking {
        var modelCalls = 0
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Remote task", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "needs input"
            override suspend fun executeResult(arguments: JsonObject) = ToolOutput(
                "Which account?", structuredContent = JsonObject(mapOf("taskId" to JsonPrimitive("t1"))),
                attention = ToolAttention.INPUT_REQUIRED)
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                modelCalls++
                return if ((messages.last() as AgentMessage.User).text == "start")
                    AgentMessage.Assistant("", listOf(call)) else AgentMessage.Assistant("continued")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed })
        val waiting = assertIs<RunResult.NeedsAttention>(runtime.run("attention", "start"))
        assertEquals(1, modelCalls)
        assertEquals(ToolAttention.INPUT_REQUIRED, waiting.toolResult.attention)
        assertEquals(waiting.toolResult, store.load("attention")!!.activeMessages().last())
        assertEquals("continued", assertIs<RunResult.Completed>(runtime.run("attention", "account A")).text)
    }

    @Test
    fun `declared structured output schema rejects an invalid tool result`() = runBlocking {
        val requiredTaskId = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "required" to JsonArray(listOf(JsonPrimitive("taskId"))),
        ))
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Remote task", JsonObject(emptyMap()),
                requiredTaskId)
            override suspend fun execute(arguments: JsonObject) = "bad"
            override suspend fun executeResult(arguments: JsonObject) = ToolOutput("bad", structuredContent =
                JsonObject(emptyMap()))
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("handled")
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed })
        assertIs<RunResult.Completed>(runtime.run("schema", "start"))
        val result = store.load("schema")!!.activeMessages().filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals(ToolFailureKind.EXECUTION_FAILED, result.failureKind)
        assertTrue(result.text.contains("result.taskId is required"))
    }

    @Test
    fun `tool progress is emitted after authorization but not stored as a message`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val store = InMemorySessionStore()
        var savedProgress: ToolProgressSink? = null
        val tool = object : ProgressReportingAgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "note"
            override suspend fun executeResult(arguments: JsonObject, progress: ToolProgressSink): ToolOutput {
                savedProgress = progress
                progress.report("opening")
                progress.report("reading")
                return ToolOutput("note")
            }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("done")
        }
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed },
            events = { events += it })

        assertIs<RunResult.Completed>(runtime.run("progress", "Read my note"))
        val toolEvents = events.filter {
            it is AgentEvent.ToolRequested || it is AgentEvent.ToolProgress || it is AgentEvent.ToolCompleted
        }
        assertEquals(listOf("ToolRequested", "ToolProgress", "ToolProgress", "ToolCompleted"),
            toolEvents.map { it.javaClass.simpleName })
        assertEquals(listOf("opening", "reading"),
            toolEvents.filterIsInstance<AgentEvent.ToolProgress>().map { it.message })
        assertTrue(toolEvents.filterIsInstance<AgentEvent.ToolProgress>().all { it.sessionId == "progress" && it.call == call })
        assertEquals(4, store.load("progress")!!.activeMessages().size)
        savedProgress!!.report("too late")
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolProgress>().size)
    }

    @Test
    fun `image tool result reaches model and session`() = runBlocking {
        val image = ToolImage("image/jpeg", "YQ==")
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read image", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "image"
            override suspend fun executeResult(arguments: JsonObject) = ToolOutput("image", listOf(image))
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else {
                    assertEquals(listOf(image), (messages.last() as AgentMessage.ToolResult).images)
                    AgentMessage.Assistant("seen")
                }
        }
        val store = InMemorySessionStore()
        assertIs<RunResult.Completed>(AgentRuntime(model, listOf(tool), store,
            { ToolAuthorization.Allowed }).run("image", "read image"))
        assertEquals(listOf(image), store.load("image")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single().images)
    }

    @Test
    fun `selected image reaches model as user content`() = runBlocking {
        val image = ToolImage("image/jpeg", "YQ==")
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                assertEquals(listOf(image), (messages.last() as AgentMessage.User).images)
                return AgentMessage.Assistant("seen")
            }
        }
        val store = InMemorySessionStore()
        assertIs<RunResult.Completed>(AgentRuntime(model, emptyList(), store,
            { ToolAuthorization.Allowed }).run("selected-image", "describe", listOf(image)))
        assertEquals(listOf(image), (store.load("selected-image")!!.activeMessages().first()
            as AgentMessage.User).images)
    }

    @Test
    fun `denied tool never executes`() = runBlocking {
        var executed = false
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String {
                executed = true
                return "private"
            }
        }
        var turns = 0
        val model = object : ModelPort {
            override suspend fun respond(
                messages: List<AgentMessage>, tools: List<ToolDefinition>,
            ): AgentMessage.Assistant = if (turns++ == 0) {
                AgentMessage.Assistant("", listOf(call))
            } else {
                assertTrue((messages.last() as AgentMessage.ToolResult).isError)
                AgentMessage.Assistant("Access denied")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(
            model, listOf(tool), store,
            { ToolAuthorization.Denied("User did not approve") },
        )

        assertIs<RunResult.Completed>(runtime.run("session-2", "Read my note"))
        assertEquals(false, executed)
        val result = store.load("session-2")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals(ToolFailureKind.AUTHORIZATION_DENIED, result.failureKind)
    }

    @Test
    fun `tool execution failure is classified separately from authorization`() = runBlocking {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String = error("File unavailable")
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("Could not read")
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed })

        assertIs<RunResult.Completed>(runtime.run("execution-failure", "Read my note"))
        val result = store.load("execution-failure")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.EXECUTION_FAILED, result.failureKind)
    }

    @Test
    fun `authorization receives the current session and selected tool definition`() = runBlocking {
        val expectedDefinition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
        val tool = object : AgentTool {
            override val definition = expectedDefinition
            override suspend fun execute(arguments: JsonObject) = "note"
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("done")
        }
        val requests = mutableListOf<ToolAuthorizationRequest>()
        val runtime = AgentRuntime(model, listOf(tool), InMemorySessionStore(),
            { request -> requests += request; ToolAuthorization.Allowed })

        assertIs<RunResult.Completed>(runtime.run("first", "Read my note"))
        assertIs<RunResult.Completed>(runtime.run("second", "Read my note"))
        assertEquals(listOf("first", "second"), requests.map { it.sessionId })
        assertEquals(listOf(expectedDefinition, expectedDefinition), requests.map { it.definition })
        assertEquals(listOf(call, call), requests.map { it.call })
    }

    @Test
    fun `authorization failure returns a tool error without executing`() = runBlocking {
        var executed = false
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String {
                executed = true
                return "note"
            }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("Could not read the note")
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store,
            { throw IllegalStateException("Approval UI unavailable") })

        assertIs<RunResult.Completed>(runtime.run("authorization-failure", "Read my note"))
        assertEquals(false, executed)
        val result = store.load("authorization-failure")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>().single()
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.AUTHORIZATION_FAILED, result.failureKind)
        assertEquals(call.id, result.callId)
        assertTrue(result.text.contains("Tool authorization failed"))
    }

    @Test
    fun `time limit completes a pending authorization call`() = runBlocking {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = error("Must not execute")
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("", listOf(call, call.copy(id = "call-2")))
        }
        val store = InMemorySessionStore()
        val events = mutableListOf<AgentEvent>()
        val runtime = AgentRuntime(model, listOf(tool), store,
            { delay(1_000); ToolAuthorization.Allowed },
            limits = RunLimits(timeoutMillis = 100), events = { events += it })

        assertIs<RunResult.LimitReached>(runtime.run("timeout", "Read my note"))
        val messages = store.load("timeout")!!.activeMessages()
        assertEquals(4, messages.size)
        val results = messages.filterIsInstance<AgentMessage.ToolResult>()
        assertEquals(listOf("call-1", "call-2"), results.map { it.callId })
        assertTrue(results.all {
            it.isError && it.text.contains("Time limit") && it.text.contains("execution status is unknown")
        })
        assertTrue(results.all { it.failureKind == ToolFailureKind.INTERRUPTED })
        assertEquals(listOf("call-1", "call-2"),
            events.filterIsInstance<AgentEvent.ToolRequested>().map { it.call.id })
        assertEquals(listOf("call-1", "call-2"),
            events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result.callId })
    }

    @Test
    fun `external cancellation completes a pending authorization call`() = runBlocking {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = error("Must not execute")
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("", listOf(call))
        }
        val started = CompletableDeferred<Unit>()
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store,
            { started.complete(Unit); awaitCancellation() })

        val job = launch { runtime.run("cancelled", "Read my note") }
        started.await()
        job.cancelAndJoin()

        val result = assertIs<AgentMessage.ToolResult>(store.load("cancelled")!!.activeMessages().last())
        assertEquals(call.id, result.callId)
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.INTERRUPTED, result.failureKind)
        assertTrue(result.text.contains("cancelled"))
    }

    @Test
    fun `next run repairs a partially recorded tool batch before sending a new user message`() = runBlocking {
        val secondCall = call.copy(id = "call-2")
        val store = InMemorySessionStore()
        store.create("interrupted")
        store.append("interrupted", SessionEntry("old-user", null, 1,
            AgentMessage.User("Read two notes")))
        store.append("interrupted", SessionEntry("old-answer", "old-user", 2,
            AgentMessage.Assistant("", listOf(call, secondCall))))
        store.append("interrupted", SessionEntry("first-result", "old-answer", 3,
            AgentMessage.ToolResult(call.id, call.name, "first note")))
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                val repaired = assertIs<AgentMessage.ToolResult>(messages[3])
                assertEquals(secondCall.id, repaired.callId)
                assertTrue(repaired.isError)
                assertEquals(ToolFailureKind.INTERRUPTED, repaired.failureKind)
                assertTrue(repaired.text.contains("Previous run ended"))
                assertTrue(repaired.text.contains("execution status is unknown"))
                assertEquals("Continue", assertIs<AgentMessage.User>(messages.last()).text)
                return AgentMessage.Assistant("done")
            }
        }
        val runtime = AgentRuntime(model, emptyList(), store,
            { error("Authorization must not run during recovery") })

        assertIs<RunResult.Completed>(runtime.run("interrupted", "Continue"))
        val results = store.load("interrupted")!!.activeMessages()
            .filterIsInstance<AgentMessage.ToolResult>()
        assertEquals(listOf(call.id, secondCall.id), results.map { it.callId })
    }

    @Test
    fun `branch selects only its ancestor messages`() = runBlocking {
        val store = InMemorySessionStore()
        val model = object : ModelPort {
            override suspend fun respond(
                messages: List<AgentMessage>, tools: List<ToolDefinition>,
            ) = AgentMessage.Assistant("done")
        }
        val runtime = AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed })
        runtime.run("session-3", "first")
        val firstUserId = store.load("session-3")!!.allEntries().first().id
        runtime.branch("session-3", firstUserId)
        runtime.run("session-3", "alternate")

        val session = store.load("session-3")!!
        assertEquals(4, session.allEntries().size)
        assertEquals(
            listOf("first", "alternate", "done"),
            session.activeMessages().map {
                when (it) {
                    is AgentMessage.User -> it.text
                    is AgentMessage.Assistant -> it.text
                    is AgentMessage.ToolResult -> it.text
                    is AgentMessage.ContextSummary -> it.text
                }
            },
        )
    }

    @Test
    fun `context transformer changes model view but preserves complete session`() = runBlocking {
        val store = InMemorySessionStore()
        val observed = mutableListOf<List<AgentMessage>>()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                observed += messages
                return AgentMessage.Assistant("done")
            }
        }
        val runtime = AgentRuntime(
            model, emptyList(), store, { ToolAuthorization.Allowed },
            contextTransformer = ModelContextTransformer { messages -> messages.takeLast(1) },
        )

        runtime.run("session-4", "first")
        runtime.run("session-4", "second")

        assertEquals(1, observed[1].size)
        assertEquals("second", (observed[1].single() as AgentMessage.User).text)
        assertEquals(4, store.load("session-4")!!.activeMessages().size)
    }

    @Test
    fun `oversized tool arguments are rejected before authorization and execution`() = runBlocking {
        var authorized = false
        var executed = false
        val events = mutableListOf<AgentEvent>()
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String { executed = true; return "secret" }
        }
        var turn = 0
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (turn++ == 0) AgentMessage.Assistant("", listOf(call.copy(
                    arguments = JsonObject(mapOf("content" to JsonPrimitive("x".repeat(16 * 1024)))),
                ))) else AgentMessage.Assistant("blocked")
        }
        val runtime = AgentRuntime(model, listOf(tool), InMemorySessionStore(),
            { authorized = true; ToolAuthorization.Allowed },
            events = { events += it },
        )
        assertIs<RunResult.Completed>(runtime.run("blocked", "read"))
        assertEquals(false, authorized)
        assertEquals(false, executed)
        val result = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertTrue(result.isError)
        assertEquals(ToolFailureKind.INVALID_ARGUMENTS, result.failureKind)
        assertTrue(result.text.contains("exceed"))
    }

    @Test
    fun `streamed text emits deltas and stores only final assistant message`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val store = InMemorySessionStore()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) = error("unused")
            override suspend fun respondStreaming(messages: List<AgentMessage>, tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit): AgentMessage.Assistant {
                onTextDelta("hel")
                onTextDelta("lo")
                return AgentMessage.Assistant("hello")
            }
        }
        AgentRuntime(model, emptyList(), store, { ToolAuthorization.Allowed }, events = { events += it })
            .run("stream", "hi")
        assertEquals(listOf("hel", "lo"), events.filterIsInstance<AgentEvent.AssistantTextDelta>().map { it.delta })
        assertEquals("hello", (store.load("stream")!!.activeMessages().last() as AgentMessage.Assistant).text)
    }

    @Test
    fun `only active tools are advertised and executable for each model turn`() = runBlocking {
        var executeCount = 0
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String {
                executeCount++
                return "note"
            }
        }
        var selectionCount = 0
        val advertised = mutableListOf<List<String>>()
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>): AgentMessage.Assistant {
                advertised += tools.map { it.name }
                return if (advertised.size == 1) AgentMessage.Assistant("", listOf(call))
                else AgentMessage.Assistant("done")
            }
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(
            model, listOf(tool), store, { ToolAuthorization.Allowed },
            activeTools = ActiveToolSelector { _, available ->
                selectionCount++
                if (selectionCount == 1) emptySet() else available
            },
        )

        assertIs<RunResult.Completed>(runtime.run("tools", "Read my note"))
        assertEquals(listOf(emptyList(), listOf("read_note")), advertised)
        assertEquals(0, executeCount)
        val inactiveResult = store.load("tools")!!.activeMessages()[2] as AgentMessage.ToolResult
        assertTrue(inactiveResult.isError)
        assertEquals(ToolFailureKind.UNKNOWN_OR_INACTIVE_TOOL, inactiveResult.failureKind)
    }

    @Test
    fun `invalid tool arguments are returned to model before authorization`() = runBlocking {
        var authorized = false
        var executed = false
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("path" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(listOf(JsonPrimitive("path"))),
            )))
            override suspend fun execute(arguments: JsonObject): String { executed = true; return "note" }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) AgentMessage.Assistant("", listOf(call))
                else {
                    val result = messages.last() as AgentMessage.ToolResult
                    assertTrue(result.isError)
                    assertEquals(ToolFailureKind.INVALID_ARGUMENTS, result.failureKind)
                    AgentMessage.Assistant("The tool needs a path")
                }
        }
        val runtime = AgentRuntime(model, listOf(tool), InMemorySessionStore(),
            { authorized = true; ToolAuthorization.Allowed })

        assertIs<RunResult.Completed>(runtime.run("invalid-args", "Read my note"))
        assertEquals(false, authorized)
        assertEquals(false, executed)
    }

    @Test
    fun `model flagged tool call never reaches authorization or execution`() = runBlocking {
        var authorized = false
        var executed = false
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): String { executed = true; return "note" }
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                if (messages.last() is AgentMessage.User) {
                    AgentMessage.Assistant("", listOf(call.copy(argumentsError = "Model output was truncated")))
                } else {
                    val result = messages.last() as AgentMessage.ToolResult
                    assertEquals(ToolFailureKind.INVALID_ARGUMENTS, result.failureKind)
                    assertEquals("Model output was truncated", result.text)
                    AgentMessage.Assistant("retry")
                }
        }
        val runtime = AgentRuntime(model, listOf(tool), InMemorySessionStore(),
            { authorized = true; ToolAuthorization.Allowed })

        assertIs<RunResult.Completed>(runtime.run("flagged-call", "Read"))
        assertEquals(false, authorized)
        assertEquals(false, executed)
    }

    @Test
    fun `tool call limit leaves a result for every requested call`() = runBlocking {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("read_note", "Read one note", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "note"
        }
        val model = object : ModelPort {
            override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
                AgentMessage.Assistant("", listOf(call, call.copy(id = "call-2")))
        }
        val store = InMemorySessionStore()
        val runtime = AgentRuntime(model, listOf(tool), store, { ToolAuthorization.Allowed },
            limits = RunLimits(maxToolCalls = 1))

        assertIs<RunResult.LimitReached>(runtime.run("limit", "Read two notes"))
        val results = store.load("limit")!!.activeMessages().filterIsInstance<AgentMessage.ToolResult>()
        assertEquals(listOf("call-1", "call-2"), results.map { it.callId })
        assertEquals(false, results[0].isError)
        assertEquals(true, results[1].isError)
        assertEquals(ToolFailureKind.LIMIT_REACHED, results[1].failureKind)
    }
}
