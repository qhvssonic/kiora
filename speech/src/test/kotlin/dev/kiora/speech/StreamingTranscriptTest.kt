package dev.kiora.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingTranscriptTest {
    @Test fun `partials replace and finals never duplicate`() {
        val transcript = StreamingTranscript()
        fun update(id: Long, rev: Long, text: String, final: Boolean = false) =
            transcript.apply(SpeechRecognitionUpdate(id, rev, text, final))
        assertEquals("你好", update(0, 0, "你好"))
        assertEquals("你好世界", update(0, 1, "你好世界"))
        assertEquals("你好世界", update(0, 0, "旧结果"))
        assertEquals("你好，世界", update(0, 2, "你好，世界", true))
        assertEquals("你好，世界", update(0, 2, "你好，世界", true))
        assertEquals("你好，世界 hello", update(1, 0, "hello"))
        assertEquals("你好，世界 hello world", update(1, 1, "hello world", true))
    }

    @Test fun `empty final retracts tentative text and permits next segment`() {
        val transcript = StreamingTranscript()
        transcript.apply(SpeechRecognitionUpdate(0, 0, "误识别", false))
        assertEquals("", transcript.apply(SpeechRecognitionUpdate(0, 1, "", true)))
        assertEquals("下一句", transcript.apply(SpeechRecognitionUpdate(1, 0, "下一句", true)))
    }

    @Test fun `missing final is detected instead of silently losing words`() {
        assertFailsWith<IllegalArgumentException> {
            StreamingTranscript().apply(SpeechRecognitionUpdate(1, 0, "跳段", false))
        }
    }
}
