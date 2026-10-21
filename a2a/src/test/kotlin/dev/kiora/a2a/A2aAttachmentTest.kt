package dev.kiora.a2a

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class A2aAttachmentTest {
    @Test
    fun `JSONRPC sends text and inline photo as A2A 1 raw part`() = runBlocking {
        var parts = emptyList<kotlinx.serialization.json.JsonElement>()
        val transport = A2aHttpTransport { method, _, _, body ->
            if (method == "GET") HttpReply(200, """{"supportedInterfaces":[{"url":"https://desktop.test/v1","protocolBinding":"JSONRPC","protocolVersion":"1.0"}]}""")
            else {
                parts = Json.parseToJsonElement(body!!).jsonObject.getValue("params").jsonObject
                    .getValue("message").jsonObject.getValue("parts").jsonArray
                HttpReply(200, """{"jsonrpc":"2.0","id":"id-1","result":{"task":{"id":"task-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"done"}]}]}}}""")
            }
        }
        val peer = A2aPeerConnector.discover("https://desktop.test/.well-known/agent-card.json", transport,
            newId = { "id-1" })

        peer.send(A2aUserMessage("请分析这张照片", listOf(A2aInlineFile("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))))

        assertEquals("请分析这张照片", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("AQID", parts[1].jsonObject.getValue("raw").jsonPrimitive.content)
        assertEquals("photo.jpg", parts[1].jsonObject.getValue("filename").jsonPrimitive.content)
        assertEquals("image/jpeg", parts[1].jsonObject.getValue("mediaType").jsonPrimitive.content)
    }

    @Test
    fun `inline files have a mobile-sized request limit`() {
        assertFailsWith<IllegalArgumentException> {
            A2aInlineFile("large.jpg", "image/jpeg", ByteArray(A2aInlineFile.MAX_INLINE_BYTES + 1))
        }
    }
}
