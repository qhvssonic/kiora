package dev.kiora.speech

/** One bounded utterance. A speed of 1 is the model's normal speaking rate. */
data class SpeechSynthesisRequest(val text: String, val speed: Float = 1f) {
    init {
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH) {
            "Speech text must contain 1 to $MAX_TEXT_LENGTH characters"
        }
        require(speed.isFinite() && speed in 0.5f..2f) { "Speech speed must be between 0.5 and 2" }
    }

    companion object { const val MAX_TEXT_LENGTH = 4_000 }
}

/** Owned, finite mono float PCM in [-1, 1]. The sample rate comes from the synthesizer. */
class SpeechAudioChunk(samples: FloatArray, val sampleRateHz: Int) {
    private val data = samples.copyOf()
    init {
        require(sampleRateHz in 8_000..192_000) { "Unsupported speech sample rate" }
        require(data.isNotEmpty() && data.all { it.isFinite() && it in -1f..1f }) {
            "Speech PCM must be nonempty and finite, in [-1, 1]"
        }
    }
    val sampleCount: Int get() = data.size
    fun samples(): FloatArray = data.copyOf()
}

data class SpeechSynthesisResult(val sampleCount: Long, val sampleRateHz: Int) {
    init { require(sampleCount > 0 && sampleRateHz in 8_000..192_000) }
    val durationMillis: Long get() = sampleCount * 1_000 / sampleRateHz
}

/**
 * Synthesis only; the host owns playback, audio focus, storage, and user controls.
 * onAudio runs synchronously on a worker thread and may block to apply backpressure.
 * Its PCM is owned and may be retained, but the host must bound its playback queue.
 * Canceling must suppress further audio; native work may finish its current segment.
 * close waits for in-flight work before releasing native resources.
 */
interface SpeechSynthesisPort {
    val modelId: String
    suspend fun synthesize(request: SpeechSynthesisRequest, onAudio: (SpeechAudioChunk) -> Unit): SpeechSynthesisResult
    suspend fun close()
}

/** Bound native generation and its accumulated return buffer without splitting a surrogate pair. */
object SpeechTextChunks {
    fun split(text: String, maxCodePoints: Int = 160): List<String> {
        require(maxCodePoints in 16..512)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.codePointCount(start, text.length)
            var end = if (remaining <= maxCodePoints) text.length
                else text.offsetByCodePoints(start, maxCodePoints)
            if (end < text.length) {
                val floor = text.offsetByCodePoints(start, maxCodePoints / 2)
                val natural = (end - 1 downTo floor).firstOrNull { index ->
                    text[index] in "。！？!?；;\n" || text[index].isWhitespace() ||
                        (text[index] == '.' && text.getOrNull(index + 1)?.isWhitespace() == true)
                }
                if (natural != null) end = natural + 1
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }
}
