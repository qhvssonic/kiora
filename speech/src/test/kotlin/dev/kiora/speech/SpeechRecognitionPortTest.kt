package dev.kiora.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpeechRecognitionPortTest {
    @Test fun audioDurationAndValidation() {
        assertEquals(1_000, Pcm16Audio(ShortArray(16_000), 16_000).durationMillis)
        assertThrows(IllegalArgumentException::class.java) { Pcm16Audio(shortArrayOf(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Audio(shortArrayOf(), 16_000) }
    }
}
