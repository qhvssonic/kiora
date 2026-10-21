package dev.kiora.a2a

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class A2aPeerConnectorTest {
    @Test
    fun `discovers card skills as separate model tools and routes the selected request`() = runBlocking {
        var sentInstruction = ""
        val transport = A2aHttpTransport { method, _, _, body ->
            if (method == "GET") {
                HttpReply(200, """{
                    "name":"yq-ai-assistant",
                    "description":"Enterprise assistant",
                    "defaultInputModes":["text/plain"],
                    "skills":[
                      {"id":"knowledge-search","name":"企业知识库检索","description":"检索内部制度与文档","tags":["rag"],"examples":["原始凭证分类规范是什么"],"inputModes":["text/plain","image/jpeg"]},
                      {"id":"text2sql-query","name":"企业业务数据查询","description":"查询真实业务数据","tags":["sql"],"examples":["各部门人数分布"]}
                    ],
                    "supportedInterfaces":[{"url":"http://localhost:9999/v1","protocolBinding":"JSONRPC","protocolVersion":"1.0"}]
                }""")
            } else {
                sentInstruction = Json.parseToJsonElement(body!!).jsonObject
                    .getValue("params").jsonObject.getValue("message").jsonObject
                    .getValue("parts").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
                HttpReply(200, """{"jsonrpc":"2.0","id":"request-1","result":{"task":{"id":"task-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"result"}]}]}}}""")
            }
        }

        val connection = A2aPeerConnector.discoverConnection(
            "http://192.168.1.5:9999/.well-known/agent-card.json", transport,
            rewriteLoopbackHost = true, newId = { "request-1" },
        )
        val tools = connection.skillTools()

        assertEquals(listOf("企业知识库检索", "企业业务数据查询"), connection.skills.map { it.name })
        assertEquals(listOf("text/plain"), connection.defaultInputModes)
        assertEquals("http://192.168.1.5:9999/v1", connection.endpointUrl)
        assertEquals(listOf("text/plain", "image/jpeg"), connection.skills[0].inputModes)
        assertEquals(listOf("a2a_knowledge_search_1", "a2a_text2sql_query_2"), tools.map { it.definition.name })
        assertEquals(listOf("a2a_dsh_knowledge_search_1", "a2a_dsh_text2sql_query_2"),
            connection.skillTools(toolNamePrefix = "a2a_dsh").map { it.definition.name })
        assertContains(tools[0].definition.description, "原始凭证分类规范是什么")
        assertContains(tools[1].definition.description, "查询真实业务数据")
        tools[0].execute(JsonObject(mapOf("instruction" to JsonPrimitive("原始凭证分类规范是什么"))))
        assertEquals("原始凭证分类规范是什么", sentInstruction)
    }

    @Test
    fun `selects JSONRPC and rewrites advertised loopback for a LAN phone`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val transport = A2aHttpTransport { method, url, headers, body ->
            calls += method to url
            if (method == "GET") {
                HttpReply(200, """{"supportedInterfaces":[{"url":"http://localhost:9999/v1","protocolBinding":"JSONRPC","protocolVersion":"1.0"},{"url":"http://localhost:9999/","protocolBinding":"JSONRPC","protocolVersion":"0.3"}]}""")
            } else {
                assertEquals("Bearer secret", headers["Authorization"])
                assertEquals("1.0", headers["A2A-Version"])
                val request = Json.parseToJsonElement(body!!).jsonObject
                assertEquals("SendMessage", request.getValue("method").jsonPrimitive.content)
                assertEquals("false", request.getValue("params").jsonObject
                    .getValue("configuration").jsonObject.getValue("blocking").jsonPrimitive.content)
                assertEquals("conversation-1", request.getValue("params").jsonObject
                    .getValue("message").jsonObject.getValue("contextId").jsonPrimitive.content)
                HttpReply(200, """{"jsonrpc":"2.0","id":"request-2","result":{"task":{"id":"task-1","contextId":"conversation-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"done"}]}]}}}""")
            }
        }
        var nextId = 0
        val peer = A2aPeerConnector.discover(
            "http://192.168.1.5:9999/.well-known/agent-card.json",
            transport,
            { "secret" },
            rewriteLoopbackHost = true,
            newId = { "request-${++nextId}" },
        )

        val result = peer.send(A2aUserMessage("please help", contextId = "conversation-1"))

        assertIs<JsonRpcA2aPeer>(peer)
        assertEquals(RemoteTaskState.COMPLETED, result.state)
        assertEquals("done", result.output)
        assertEquals("conversation-1", result.contextId)
        assertEquals("POST" to "http://192.168.1.5:9999/v1", calls.last())
    }

    @Test
    fun `file and form arguments appear only for advertised skill input modes`() = runBlocking {
        var parts = emptyList<kotlinx.serialization.json.JsonElement>()
        val transport = A2aHttpTransport { method, _, _, body ->
            if (method == "GET") HttpReply(200, """{
                "defaultInputModes":["text/plain"],
                "skills":[
                  {"id":"upload","name":"Upload","description":"Accepts a PDF and form","inputModes":["text/plain","application/pdf","application/json"]},
                  {"id":"search","name":"Search","description":"Text only"}
                ],
                "supportedInterfaces":[{"url":"https://desktop.test/a2a","protocolBinding":"JSONRPC","protocolVersion":"1.0"}]
            }""") else {
                parts = Json.parseToJsonElement(body!!).jsonObject.getValue("params").jsonObject
                    .getValue("message").jsonObject.getValue("parts").jsonArray
                HttpReply(200, """{"jsonrpc":"2.0","id":"request-1","result":{"task":{"id":"task-1","status":{"state":"TASK_STATE_COMPLETED"}}}}""")
            }
        }
        val connection = A2aPeerConnector.discoverConnection(
            "https://desktop.test/.well-known/agent-card.json", transport, newId = { "request-1" },
        )
        val tools = connection.skillTools(fileLoader = { ids ->
            assertEquals(listOf("selected:0"), ids)
            listOf(A2aInlineFile("form.pdf", "application/pdf", byteArrayOf(1, 2, 3)))
        })
        val uploadProperties = tools[0].definition.parameters.getValue("properties").jsonObject
        val searchProperties = tools[1].definition.parameters.getValue("properties").jsonObject
        assertTrue("files" in uploadProperties && "form" in uploadProperties)
        assertFalse("files" in searchProperties || "form" in searchProperties)

        tools[0].execute(JsonObject(mapOf(
            "instruction" to JsonPrimitive("Submit the form"),
            "files" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("selected:0"))),
            "form" to JsonObject(mapOf("subject" to JsonPrimitive("report"))),
        )))
        assertEquals("Submit the form", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("report", parts[1].jsonObject.getValue("data").jsonObject.getValue("subject").jsonPrimitive.content)
        assertEquals("AQID", parts[2].jsonObject.getValue("raw").jsonPrimitive.content)
    }
}
