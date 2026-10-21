package dev.kiora.model

import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class ChatCompletionModelPortTest {
    @Test
    fun `base URL gets default path while explicit endpoints stay unchanged`() = runBlocking {
        assertEquals("https://example.com/v1/chat/completions", resolveChatCompletionsEndpoint("https://example.com"))
        assertEquals("https://example.com/v1/chat/completions", resolveChatCompletionsEndpoint("https://example.com/"))
        assertEquals("https://example.com/v1/chat/completions", resolveChatCompletionsEndpoint("https://example.com/v1"))
        assertEquals("https://api.deepseek.com/chat/completions", resolveChatCompletionsEndpoint("https://api.deepseek.com/chat/completions"))
        val transport = ModelHttpTransport { url, _, _ ->
            assertEquals("https://example.com/v1/chat/completions", url)
            ModelHttpReply(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        assertEquals("ok", ChatCompletionModelPort("https://example.com", "test", { null }, transport)
            .respond(emptyList(), emptyList()).text)
    }

    @Test
    fun `stream assembles text and fragmented tool arguments`() = runBlocking {
        val received = mutableListOf<String>()
        val transport = object : StreamingModelHttpTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): ModelHttpReply = error("unused")
            override suspend fun postStream(url: String, headers: Map<String, String>, body: String, onData: suspend (String) -> Unit) {
                assertEquals("true", Json.parseToJsonElement(body).jsonObject.getValue("stream").toString())
                onData("""{"choices":[{"delta":{"content":"Hello ","tool_calls":[{"index":0,"id":"call-","function":{"name":"read_","arguments":"{\"x\":"}}]}}]}""")
                onData("""{"choices":[{"delta":{"content":"world","tool_calls":[{"index":0,"id":"1","function":{"name":"note","arguments":"1}"}}]}}]}""")
                onData("[DONE]")
            }
        }
        val port = ChatCompletionModelPort("https://model.test/chat/completions", "test", { null }, transport)
        val answer = port.respondStreaming(listOf(AgentMessage.User("hi")), emptyList()) { received += it }
        assertEquals("Hello world", answer.text)
        assertEquals(listOf("Hello ", "world"), received)
        assertEquals("call-1", answer.toolCalls.single().id)
        assertEquals("read_note", answer.toolCalls.single().name)
        assertEquals("1", answer.toolCalls.single().arguments.getValue("x").jsonPrimitive.content)
    }

    @Test
    fun `provider selector routes to selected compatible endpoint`() = runBlocking {
        val urls = mutableListOf<String>()
        val transport = ModelHttpTransport { url, _, body ->
            urls += url
            ModelHttpReply(200, """{"choices":[{"message":{"content":"${Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content}"}}]}""")
        }
        val providers = CompatibleModelProviders(listOf(
            CompatibleProvider("first", "First", "https://first.test", "one"),
            CompatibleProvider("second", "Second", "https://second.test/chat/completions", "two"),
        ), { null }, transport)
        assertEquals("one", providers.respond(emptyList(), emptyList()).text)
        providers.select("second")
        assertEquals("two", providers.respond(emptyList(), emptyList()).text)
        assertEquals(listOf("https://first.test/v1/chat/completions", "https://second.test/chat/completions"), urls)
    }

    @Test
    fun `provider selector preserves usage and the actual response model`() = runBlocking {
        val transport = ModelHttpTransport { _, _, _ ->
            ModelHttpReply(200,
                """{"model":"physical-v2","choices":[{"message":{"content":"ok"}}],"usage":{"total_tokens":9}}""")
        }
        val providers = CompatibleModelProviders(
            listOf(CompatibleProvider("cloud-a", "Cloud A", "https://first.test", "alias")),
            { null }, transport)
        val response = providers.respondWithUsage(emptyList(), emptyList())
        assertEquals(9, response.usage?.totalTokens)
        assertEquals(ModelIdentity("physical-v2", "cloud-a"), response.model)
    }

    @Test
    fun `tool call is decoded and returned tool result is sent back`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val transport = ModelHttpTransport { _, headers, body ->
            assertEquals("Bearer test-key", headers["Authorization"])
            requests += Json.parseToJsonElement(body).jsonObject
            if (requests.size == 1) {
                ModelHttpReply(200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"device_info","arguments":"{}"}}]}}]}""")
            } else {
                ModelHttpReply(200, """{"choices":[{"message":{"content":"设备信息已读取。"}}]}""")
            }
        }
        val port = ChatCompletionModelPort(
            "https://model.test/chat/completions", "example-model", { "test-key" }, transport,
        )
        val tool = ToolDefinition("device_info", "Read device", JsonObject(mapOf("type" to JsonPrimitive("object"))))
        val user = AgentMessage.User("查看设备信息")

        val call = port.respond(listOf(user), listOf(tool))
        val answer = port.respond(
            listOf(user, call, AgentMessage.ToolResult("call-1", "device_info", "Pixel")), listOf(tool),
        )

        assertEquals("device_info", call.toolCalls.single().name)
        assertEquals("设备信息已读取。", answer.text)
        val offeredTool = requests[0].getValue("tools").jsonArray.single().jsonObject
            .getValue("function").jsonObject
        assertEquals("device_info", offeredTool.getValue("name").jsonPrimitive.content)
        assertEquals("Read device", offeredTool.getValue("description").jsonPrimitive.content)
        assertContains(
            requests[0].getValue("messages").jsonArray.first().jsonObject
                .getValue("content").jsonPrimitive.content,
            "call the matching tool",
        )
        val sentMessages = requests[1].getValue("messages").jsonArray
        assertEquals("tool", sentMessages.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("call-1", sentMessages.last().jsonObject.getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `malformed tool arguments become a recoverable call error`() = runBlocking {
        val transport = ModelHttpTransport { _, _, _ -> ModelHttpReply(200,
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"bad-1","function":{"name":"read_note","arguments":"{"}}]}}]}""") }
        val answer = ChatCompletionModelPort("https://model.test", "test", { null }, transport)
            .respond(listOf(AgentMessage.User("read")), emptyList())
        assertEquals("Tool arguments must be a valid JSON object", answer.toolCalls.single().argumentsError)
    }

    @Test
    fun `length finish blocks even syntactically valid tool calls`() = runBlocking {
        val response = """{"choices":[{"finish_reason":"length","message":{"content":null,"tool_calls":[{"id":"cut-1","function":{"name":"send","arguments":"{\"text\":\"partial\"}"}}]}}]}"""
        val transport = ModelHttpTransport { _, _, _ -> ModelHttpReply(200, response) }
        val answer = ChatCompletionModelPort("https://model.test", "test", { null }, transport)
            .respond(listOf(AgentMessage.User("send")), emptyList())
        assertContains(answer.toolCalls.single().argumentsError.orEmpty(), "truncated by the model output limit")
    }

    @Test
    fun `stream length finish blocks tool calls when finish chunk has no delta`() = runBlocking {
        val transport = object : StreamingModelHttpTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): ModelHttpReply = error("unused")
            override suspend fun postStream(url: String, headers: Map<String, String>, body: String, onData: suspend (String) -> Unit) {
                onData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"cut-1","function":{"name":"send","arguments":"{\"text\":\"partial\"}"}}]}}]}""")
                onData("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
                onData("[DONE]")
            }
        }
        val answer = ChatCompletionModelPort("https://model.test", "test", { null }, transport)
            .respondStreaming(listOf(AgentMessage.User("send")), emptyList()) { }
        assertContains(answer.toolCalls.single().argumentsError.orEmpty(), "truncated by the model output limit")
    }

    @Test
    fun `nonstreaming response reports provider usage and sends summary as system context`() = runBlocking {
        var request: JsonObject? = null
        val transport = ModelHttpTransport { _, _, body ->
            request = Json.parseToJsonElement(body).jsonObject
            ModelHttpReply(200, """{"choices":[{"message":{"content":"done"}}],"usage":{"prompt_tokens":24,"completion_tokens":3,"total_tokens":27,"prompt_tokens_details":{"cached_tokens":8}}}""")
        }
        val response = ChatCompletionModelPort("https://model.test", "test", { null }, transport)
            .respondWithUsage(listOf(AgentMessage.ContextSummary("Remember the user's choice"),
                AgentMessage.User("continue")), emptyList())

        assertEquals(24, response.usage?.inputTokens)
        assertEquals(3, response.usage?.outputTokens)
        assertEquals(27, response.usage?.totalTokens)
        assertEquals(8, response.usage?.cachedInputTokens)
        val summary = request!!.getValue("messages").jsonArray[1].jsonObject
        assertEquals("system", summary.getValue("role").jsonPrimitive.content)
        assertContains(summary.getValue("content").jsonPrimitive.content, "Remember the user's choice")
    }

    @Test
    fun `streaming usage chunk is captured when requested`() = runBlocking {
        val transport = object : StreamingModelHttpTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): ModelHttpReply = error("unused")
            override suspend fun postStream(url: String, headers: Map<String, String>, body: String,
                                            onData: suspend (String) -> Unit) {
                val options = Json.parseToJsonElement(body).jsonObject.getValue("stream_options").jsonObject
                assertEquals("true", options.getValue("include_usage").toString())
                onData("""{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""")
                onData("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}""")
                onData("[DONE]")
            }
        }
        val response = ChatCompletionModelPort("https://model.test", "test", { null }, transport,
            requestStreamingUsage = true).respondStreamingWithUsage(listOf(AgentMessage.User("hi")), emptyList()) { }
        assertEquals("done", response.assistant.text)
        assertEquals(14, response.usage?.totalTokens)
    }

    @Test
    fun `image tool result is sent as a user image after the tool reply`() = runBlocking {
        var request: JsonObject? = null
        val transport = ModelHttpTransport { _, _, body ->
            request = Json.parseToJsonElement(body).jsonObject
            ModelHttpReply(200, """{"choices":[{"message":{"content":"a red flower"}}]}""")
        }
        val call = dev.kiora.core.ToolCall("image-1", "read", JsonObject(emptyMap()))
        ChatCompletionModelPort("https://model.test", "vision", { null }, transport).respond(listOf(
            AgentMessage.User("What is in the photo?"),
            AgentMessage.Assistant("", listOf(call)),
            AgentMessage.ToolResult("image-1", "read", "Photo", images = listOf(ToolImage("image/jpeg", "YQ=="))),
        ), emptyList())
        val sent = request!!.getValue("messages").jsonArray
        assertEquals("tool", sent[sent.lastIndex - 1].jsonObject.getValue("role").jsonPrimitive.content)
        val imageMessage = sent.last().jsonObject
        assertEquals("user", imageMessage.getValue("role").jsonPrimitive.content)
        val image = imageMessage.getValue("content").jsonArray.last().jsonObject
        assertEquals("image_url", image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,YQ==",
            image.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `selected image is sent in the user message`() = runBlocking {
        var request: JsonObject? = null
        val transport = ModelHttpTransport { _, _, body ->
            request = Json.parseToJsonElement(body).jsonObject
            ModelHttpReply(200, """{"choices":[{"message":{"content":"a pump"}}]}""")
        }
        ChatCompletionModelPort("https://model.test", "deepseek-flash", { null }, transport).respond(
            listOf(AgentMessage.User("Describe this photo", listOf(ToolImage("image/jpeg", "YQ==")))),
            emptyList(),
        )
        val message = request!!.getValue("messages").jsonArray.last().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        val parts = message.getValue("content").jsonArray
        assertEquals("Describe this photo", parts.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,YQ==",
            parts.last().jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }
}
