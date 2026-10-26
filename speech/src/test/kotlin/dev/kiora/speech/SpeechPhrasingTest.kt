package dev.kiora.speech

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpeechPhrasingTest {
    @Test fun doesNotSynthesizeStandaloneSymbols() {
        val phrases = SpeechPhrasing.split("你好！😄\n\n再见。🙂")
        assertEquals(listOf("你好！", "再见。"), phrases.map { it.text })
        assertEquals(listOf(650, 0), phrases.map { it.pauseAfterMillis })
        assertTrue(SpeechPhrasing.split("🙂…").isEmpty())
    }

    @Test fun distinguishesClauseSentenceAndParagraphWithoutPausingAfterTheEnd() {
        val phrases = SpeechPhrasing.split("你好，世界！\n\n下一段：慢慢说。结束。")
        assertEquals(listOf("你好，", "世界！", "下一段：", "慢慢说。", "结束。"), phrases.map { it.text })
        assertEquals(listOf(150, 650, 240, 320, 0), phrases.map { it.pauseAfterMillis })
    }

    @Test fun preservesNumbersQuotesAndPunctuationClusters() {
        val phrases = SpeechPhrasing.split("他说：“价格是1,234.56元，时间为12:30！”\n再见。")
        assertTrue(phrases.any { "1,234.56" in it.text })
        assertTrue(phrases.any { "12:30！”" in it.text })
        assertEquals("他说：“价格是1,234.56元，时间为12:30！”再见。", phrases.joinToString("") { it.text })
        assertEquals(0, phrases.last().pauseAfterMillis)
    }

    @Test fun boundsLongNativeCallsWithoutInventingPausesAtLengthCuts() {
        val text = "你好🙂".repeat(130)
        val phrases = SpeechPhrasing.split(text)
        assertEquals(text, phrases.joinToString("") { it.text })
        assertTrue(phrases.all { it.text.codePointCount(0, it.text.length) <= 160 })
        assertTrue(phrases.all { it.pauseAfterMillis == 0 })
    }

    @Test fun respectsExistingQuietTailAndNeverTrimsAudio() {
        assertEquals(0, SpeechPhrasing.paddingSamples(150, 8_000, 44_100))
        assertEquals(8_820, SpeechPhrasing.paddingSamples(300, 4_410, 44_100))
        assertEquals(28_665, SpeechPhrasing.paddingSamples(650, 0, 44_100))
    }
}
