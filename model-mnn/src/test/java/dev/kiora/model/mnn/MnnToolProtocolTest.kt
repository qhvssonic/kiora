package dev.kiora.model.mnn

import dev.kiora.core.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MnnToolProtocolTest {
    private val read = ToolDefinition("read", "Read workspace file",
        Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"}}}""").jsonObject)

    @Test fun acceptsOnlyDeclaredToolWithObjectArguments() {
        val call = MnnToolProtocol.parse(
            """{"kiora_tool_call":{"name":"read","arguments":{"path":"notes.md"}}}""", listOf(read))
        assertEquals("read", call?.name)
        assertEquals("notes.md", call?.arguments?.get("path")?.toString()?.trim('"'))
        assertNull(MnnToolProtocol.parse("""{"kiora_tool_call":{"name":"shell","arguments":{}}}""",
            listOf(read)))
    }

    @Test fun ordinaryAnswersAndIncompleteJsonRemainText() {
        assertNull(MnnToolProtocol.parse("The answer is 42.", listOf(read)))
        assertNull(MnnToolProtocol.parse("{\"kiora_tool_call\":", listOf(read)))
        assertNull(MnnToolProtocol.parse("""{"kiora_tool_call":{"name":"read","arguments":{}}}""",
            emptyList()))
    }
}
