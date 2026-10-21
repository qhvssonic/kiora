package dev.kiora.a2a

import dev.kiora.core.ToolProgressSink
import dev.kiora.core.ToolAttention
import dev.kiora.core.ToolOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class A2aDelegateToolTest {
    @Test
    fun `waits for a remote task to complete`() = runBlocking {
        var polls = 0
        val peer = object : A2aPeer {
            override suspend fun send(instruction: String) = RemoteTask("t1", RemoteTaskState.WORKING)
            override suspend fun continueTask(taskId: String, instruction: String) = error("Not used")
            override suspend fun getTask(taskId: String): RemoteTask {
                polls++
                return RemoteTask(taskId, RemoteTaskState.COMPLETED, "report ready")
            }
            override suspend fun cancelTask(taskId: String) = Unit
        }
        val tool = A2aDelegateTool(peer, pollIntervalMillis = 1)

        val result = tool.execute(JsonObject(mapOf("instruction" to JsonPrimitive("edit report"))))

        assertEquals(1, polls)
        assertContains(result, "report ready")
    }

    @Test
    fun `reports A2A state changes without repeating each poll`() = runBlocking {
        var polls = 0
        val peer = object : A2aPeer {
            override suspend fun send(instruction: String) = RemoteTask("t1", RemoteTaskState.SUBMITTED)
            override suspend fun continueTask(taskId: String, instruction: String) = error("Not used")
            override suspend fun getTask(taskId: String): RemoteTask = when (++polls) {
                1, 2 -> RemoteTask(taskId, RemoteTaskState.WORKING)
                else -> RemoteTask(taskId, RemoteTaskState.COMPLETED, "done")
            }
            override suspend fun cancelTask(taskId: String) = Unit
        }
        val progress = mutableListOf<String>()
        val output = A2aDelegateTool(peer, pollIntervalMillis = 1).executeResult(
            JsonObject(mapOf("instruction" to JsonPrimitive("work"))),
            ToolProgressSink { progress += it },
        )

        assertEquals(listOf("Task t1 submitted", "Task t1 working"), progress)
        assertContains(output.text, "done")
    }

    @Test
    fun `pending remote task exposes machine readable continuation`() = runBlocking {
        val peer = object : A2aPeer {
            override suspend fun send(instruction: String) =
                RemoteTask("task-42", RemoteTaskState.INPUT_REQUIRED, "Which account?")
            override suspend fun continueTask(taskId: String, instruction: String) = error("Not used")
            override suspend fun getTask(taskId: String) = error("Not used")
            override suspend fun cancelTask(taskId: String) = Unit
        }
        val result = A2aDelegateTool(peer).executeResult(
            JsonObject(mapOf("instruction" to JsonPrimitive("work"))), ToolProgressSink { })
        assertEquals(ToolAttention.INPUT_REQUIRED, result.attention)
        assertEquals("task-42", result.structuredContent!!.jsonObject.getValue("taskId").jsonPrimitive.content)
    }

    @Test
    fun `skill result sender preserves host supplied attention and task data`() = runBlocking {
        val peer = object : A2aPeer {
            override suspend fun send(instruction: String) = error("Host sender should handle it")
            override suspend fun continueTask(taskId: String, instruction: String) = error("Not used")
            override suspend fun getTask(taskId: String) = error("Not used")
            override suspend fun cancelTask(taskId: String) = Unit
        }
        val skill = A2aSkillTool(peer, "desktop", A2aSkill("review", "Review", "Review a file"),
            "a2a_review", resultSender = { message, taskId ->
                assertEquals("review this", message.text)
                assertEquals(null, taskId)
                ToolOutput("Choose a file", structuredContent = JsonObject(mapOf(
                    "taskId" to JsonPrimitive("task-7"),
                    "state" to JsonPrimitive("INPUT_REQUIRED"),
                    "output" to JsonPrimitive("Choose a file"),
                )), attention = ToolAttention.INPUT_REQUIRED)
            })
        val result = skill.executeResult(JsonObject(mapOf("instruction" to JsonPrimitive("review this"))))
        assertEquals(ToolAttention.INPUT_REQUIRED, result.attention)
        assertEquals("task-7", result.structuredContent!!.jsonObject.getValue("taskId").jsonPrimitive.content)
        assertEquals(true, skill.definition.outputSchema != null)
    }

    @Test
    fun `skill preflight passes file ids to the host before upload`() = runBlocking {
        val peer = object : A2aPeer {
            override suspend fun send(instruction: String) = error("not used")
            override suspend fun continueTask(taskId: String, instruction: String) = error("not used")
            override suspend fun getTask(taskId: String) = error("not used")
            override suspend fun cancelTask(taskId: String) = Unit
        }
        val skill = A2aSkillTool(peer, "desktop", A2aSkill("send", "Send", "", inputModes = listOf("*/*")),
            "a2a_send", fileLoader = { error("file bytes must not be read during preflight") },
            fileIdValidator = { ids ->
                assertEquals(listOf("workspace:0"), ids)
                "Use selected:0 for the selected phone file"
            })
        val error = skill.validateArguments(JsonObject(mapOf(
            "instruction" to JsonPrimitive("send"),
            "files" to JsonArray(listOf(JsonPrimitive("workspace:0"))),
        )))
        assertEquals("Use selected:0 for the selected phone file", error)
    }
}
