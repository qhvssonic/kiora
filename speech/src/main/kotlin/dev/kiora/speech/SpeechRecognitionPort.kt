package dev.kiora.speech

/** One bounded mono PCM recording. Samples are signed 16-bit integers. */
class Pcm16Audio(val samples: ShortArray, val sampleRateHz: Int) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(samples.isNotEmpty()) { "audio must not be empty" }
    }

    val durationMillis: Long get() = samples.size.toLong() * 1_000 / sampleRateHz
}

data class SpeechTranscript(
    val text: String,
    val language: String? = null,
    val emotion: String? = null,
    val event: String? = null,
)

/** Host owns microphone access, permissions, recording limits, and user confirmation. */
fun interface SpeechRecognitionPort {
    suspend fun transcribe(audio: Pcm16Audio): SpeechTranscript
}
