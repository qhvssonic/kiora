package dev.kiora.a2a

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpJsonA2aPeerTest {
    @Test
    fun `discovers HTTP interface and sends task with bearer token`() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val transport = A2aHttpTransport { method, url, headers, body ->
            calls += Triple(method, url, body)
            if (url.endsWith("agent-card.json")) {
                HttpReply(200, """{"supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0","url":"https://desktop.test/a2a"}]}""")
            } else {
                assertEquals("Bearer secret", headers["Authorization"])
                assertEquals("1.0", headers["A2A-Version"])
                HttpReply(200, """{"task":{"id":"t-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"done"}]}]}}""")
            }
        }
        val peer = HttpJsonA2aPeer.discover(
            "https://desktop.test/.well-known/agent-card.json",
            transport,
            { "secret" },
            { "message-1" },
        )

        val task = peer.send("整理报告")

        assertEquals(RemoteTaskState.COMPLETED, task.state)
        assertEquals("done", task.output)
        assertEquals("https://desktop.test/a2a/message:send", calls.last().second)
        val message = Json.parseToJsonElement(calls.last().third!!).jsonObject.getValue("message").jsonObject
        assertEquals("message-1", message.getValue("messageId").jsonPrimitive.content)
        assertEquals("ROLE_USER", message.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `follows up and polls an input-required task`() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var sends = 0
        val transport = A2aHttpTransport { method, url, _, body ->
            calls += Triple(method, url, body)
            when {
                url.endsWith("agent-card.json") -> HttpReply(200, """{"supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0","url":"https://desktop.test/a2a"}]}""")
                url.endsWith("message:send") -> {
                    sends++
                    if (sends == 1) HttpReply(200, """{"task":{"id":"t-1","status":{"state":"TASK_STATE_INPUT_REQUIRED","message":{"parts":[{"text":"Which file?"}]}}}}""")
                    else HttpReply(200, """{"task":{"id":"t-1","status":{"state":"TASK_STATE_WORKING"}}}""")
                }
                else -> HttpReply(200, """{"task":{"id":"t-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"report.pdf"}]}]}}""")
            }
        }
        val peer = HttpJsonA2aPeer.discover("https://desktop.test/agent-card.json", transport)

        assertEquals(RemoteTaskState.INPUT_REQUIRED, peer.send("整理文件").state)
        assertEquals(RemoteTaskState.WORKING, peer.continueTask("t-1", "report.md").state)
        assertEquals("report.pdf", peer.getTask("t-1").output)
        assertTrue(calls.last().second.endsWith("/tasks/t-1"))
        val followUp = Json.parseToJsonElement(calls[2].third!!).jsonObject.getValue("message").jsonObject
        assertEquals("t-1", followUp.getValue("taskId").jsonPrimitive.content)
    }
}
