package dev.kiora.speech

/** Reading boundaries and minimum quiet tails; never alter words or numeric formatting. */
data class SpeechPhrase(val text: String, val pauseAfterMillis: Int)

object SpeechPhrasing {
    fun split(text: String): List<SpeechPhrase> {
        val phrases = mutableListOf<SpeechPhrase>()
        var start = 0
        var index = 0
        while (index < text.length) {
            var pause = boundary(text, index)
            if (pause == 0) { index++; continue }
            var end = index + 1
            var newlines = if (text[index] == '\n') 1 else 0
            // Keep punctuation clusters and closing quotes with the words they finish.
            while (end < text.length) {
                val c = text[end]
                val nextPause = boundary(text, end)
                if (!c.isWhitespace() && c !in "”’\"'）)]】》」』" && nextPause == 0) break
                pause = maxOf(pause, nextPause)
                if (c == '\n') newlines++
                end++
            }
            if (newlines >= 2) pause = 650
            append(phrases, text.substring(start, end), pause)
            start = end
            index = end
        }
        append(phrases, text.substring(start), 0)
        if (phrases.isNotEmpty()) phrases[phrases.lastIndex] = phrases.last().copy(pauseAfterMillis = 0)
        return phrases
    }

    fun paddingSamples(pauseMillis: Int, trailingQuietSamples: Long, sampleRateHz: Int): Int =
        (pauseMillis.toLong() * sampleRateHz / 1_000 - trailingQuietSamples).coerceAtLeast(0).toInt()

    private fun append(output: MutableList<SpeechPhrase>, source: String, pause: Int) {
        // Symbols alone (for example a trailing emoji) have no pronounceable tokens.
        // Never send them as a standalone native synthesis request.
        val parts = SpeechTextChunks.split(source).filter { part ->
            part.codePoints().anyMatch { Character.isLetterOrDigit(it) }
        }
        if (parts.isEmpty() && output.isNotEmpty()) {
            output[output.lastIndex] = output.last().let { it.copy(pauseAfterMillis = maxOf(it.pauseAfterMillis, pause)) }
        }
        parts.forEachIndexed { index, part ->
            output += SpeechPhrase(part, if (index == parts.lastIndex) pause else 0)
        }
    }

    private fun boundary(text: String, index: Int): Int {
        val c = text[index]
        val numericSeparator = text.getOrNull(index - 1)?.isDigit() == true &&
            text.getOrNull(index + 1)?.isDigit() == true
        return when {
            c == '\n' -> 320
            c in "。！？!?…" -> 320
            c == '.' && !numericSeparator && (index == text.lastIndex ||
                text[index + 1].isWhitespace() || text[index + 1] in "”’\"')]") -> 320
            c in "，、；：" -> if (c == '，' || c == '、') 150 else 240
            c in ",;:" && !numericSeparator -> if (c == ',') 150 else 240
            else -> 0
        }
    }
}
