package dev.kiora.speech.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.SpeechRecognitionPort
import dev.kiora.speech.SpeechTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Offline SenseVoice adapter. Provide a sherpa-onnx exported model.int8.onnx and tokens.txt. */
class SenseVoiceRecognizer(
    modelFile: File,
    tokensFile: File,
    language: String = "auto",
    numThreads: Int = 2,
) : SpeechRecognitionPort, Closeable {
    private val recognizer: OfflineRecognizer
    private val lock = Any()
    private var closed = false

    init {
        require(modelFile.isFile && modelFile.length() > 0) { "SenseVoice model file is missing or empty" }
        require(tokensFile.isFile && tokensFile.length() > 0) { "SenseVoice tokens file is missing or empty" }
        require(numThreads in 1..8) { "numThreads must be between 1 and 8" }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = language,
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = numThreads,
                    provider = "cpu",
                ),
            ),
        )
    }

    override suspend fun transcribe(audio: Pcm16Audio): SpeechTranscript = withContext(Dispatchers.Default) {
        require(audio.sampleRateHz == 16_000) { "SenseVoice expects 16 kHz mono PCM" }
        synchronized(lock) {
            check(!closed) { "SenseVoice recognizer is closed" }
            val stream = recognizer.createStream()
            try {
                val samples = FloatArray(audio.samples.size) { audio.samples[it] / 32768f }
                stream.acceptWaveform(samples, audio.sampleRateHz)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                SpeechTranscript(
                    text = result.text.trim(),
                    language = result.lang.takeIf(String::isNotBlank),
                    emotion = result.emotion.takeIf(String::isNotBlank),
                    event = result.event.takeIf(String::isNotBlank),
                )
            } finally {
                stream.release()
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }
}
