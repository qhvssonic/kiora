package dev.kiora.speech.tools

import dev.kiora.speech.SpeechSynthesisResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeakTextToolTest {
    @Test fun validatesBeforePlaybackAndReturnsOnlyCompletionMetadata() = runBlocking {
        var calls = 0
        val tool = SpeakTextTool { request ->
            calls++
            assertEquals("你好", request.text)
            SpeechSynthesisResult(44100, 44100)
        }
        assertNotNull(tool.validateArguments(buildJsonObject { put("text", " ") }))
        assertNotNull(tool.validateArguments(buildJsonObject { put("text", "你好"); put("speed", "fast") }))
        assertEquals(0, calls)
        val output = tool.executeResult(buildJsonObject { put("text", "你好") })
        assertEquals(1, calls)
        assertFalse(output.isError)
        assertEquals("played", output.structuredContent?.jsonObject?.get("status")?.toString()?.trim('"'))
        assertEquals("1000", output.structuredContent?.jsonObject?.get("durationMillis")?.toString())
        assertTrue(output.text.contains("completed"))
        assertFalse(output.text.contains("你好"))
    }
}
