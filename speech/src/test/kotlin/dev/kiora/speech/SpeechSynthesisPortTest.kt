package dev.kiora.speech

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechSynthesisPortTest {
    @Test fun rejectsInvalidRequestsAndPcm() {
        assertThrows(IllegalArgumentException::class.java) { SpeechSynthesisRequest(" ") }
        assertThrows(IllegalArgumentException::class.java) { SpeechSynthesisRequest("a".repeat(4001)) }
        assertThrows(IllegalArgumentException::class.java) { SpeechSynthesisRequest("hello", Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SpeechAudioChunk(floatArrayOf(Float.POSITIVE_INFINITY), 44100) }
        assertThrows(IllegalArgumentException::class.java) { SpeechAudioChunk(floatArrayOf(1.1f), 44100) }
        assertEquals(1_000, SpeechSynthesisResult(44100, 44100).durationMillis)
    }

    @Test fun ownsPcmAndPreservesUnicodeAcrossBoundedSegments() {
        val input = floatArrayOf(0.25f, -0.5f)
        val chunk = SpeechAudioChunk(input, 44100)
        input[0] = 1f
        val retrieved = chunk.samples()
        retrieved[1] = 1f
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), chunk.samples())

        val text = "你好🙂世界。".repeat(100)
        val segments = SpeechTextChunks.split(text)
        assertEquals(text, segments.joinToString(""))
        assertTrue(segments.all { it.codePointCount(0, it.length) <= 160 })
        assertTrue(segments.all { it.isNotBlank() })
    }
}
