package dev.kiora.speech.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.kiora.speech.SpeechAudioChunk
import dev.kiora.speech.SpeechSynthesisPort
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.SpeechSynthesisResult
import dev.kiora.speech.SpeechPhrasing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A complete imported sherpa MeloTTS bundle. No network access or Android UI. */
class MeloTtsSynthesizer(
    private val directory: File,
    override val modelId: String,
    private val numThreads: Int = 2,
    private val modelFileName: String = "model.int8.onnx",
) : SpeechSynthesisPort {
    private val lock = Mutex()
    private var engine: OfflineTts? = null
    private var closed = false

    init {
        require(modelId.isNotBlank()) { "Model ID must identify the imported weights" }
        require(numThreads in 1..8)
        validateDirectory(directory, modelFileName)
    }

    suspend fun prepare() = withContext(Dispatchers.Default) {
        lock.withLock { runtime(); Unit }
    }

    override suspend fun synthesize(request: SpeechSynthesisRequest,
        onAudio: (SpeechAudioChunk) -> Unit): SpeechSynthesisResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val caller = currentCoroutineContext()
            caller.ensureActive()
            val tts = runtime()
            val rate = tts.sampleRate()
            var count = 0L
            val phrases = SpeechPhrasing.split(request.text)
            require(phrases.isNotEmpty()) { "Text contains no pronounceable words or numbers" }
            for (phrase in phrases) {
                caller.ensureActive()
                var failure: Throwable? = null
                var delivered = 0L
                var trailingQuiet = 0L
                tts.generateWithCallback(phrase.text, sid = 0, speed = request.speed, callback = SherpaTtsCallback { samples ->
                    // Never let a Kotlin exception cross a JNI callback frame.
                    if (failure != null) 0 else try {
                        caller.ensureActive()
                        if (samples.isNotEmpty()) {
                            var quiet = 0
                            for (i in samples.lastIndex downTo 0) {
                                if (kotlin.math.abs(samples[i]) > 0.003f) break
                                quiet++
                            }
                            trailingQuiet = if (quiet == samples.size) trailingQuiet + quiet else quiet.toLong()
                            onAudio(SpeechAudioChunk(samples, rate))
                            delivered += samples.size
                        }
                        caller.ensureActive()
                        1
                    } catch (error: Throwable) {
                        failure = error
                        0
                    }
                })
                failure?.let { throw it }
                caller.ensureActive()
                check(delivered > 0) { "MeloTTS produced no speech for a text segment" }
                count += delivered
                // Pad only the missing quiet tail. Keep pauses in the PCM queue so synthesis
                // can continue ahead; sleeping here would reintroduce playback starvation.
                val padding = SpeechPhrasing.paddingSamples(phrase.pauseAfterMillis, trailingQuiet, rate)
                if (padding > 0) {
                    caller.ensureActive()
                    onAudio(SpeechAudioChunk(FloatArray(padding), rate))
                    count += padding
                }
            }
            SpeechSynthesisResult(count, rate)
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.Default) {
        lock.withLock {
            if (!closed) {
                closed = true
                engine?.release()
                engine = null
            }
        }
    }

    private fun runtime(): OfflineTts {
        check(!closed) { "MeloTTS synthesizer is closed" }
        return engine ?: OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(directory, modelFileName).absolutePath,
                    lexicon = File(directory, "lexicon.txt").absolutePath,
                    tokens = File(directory, "tokens.txt").absolutePath,
                ), numThreads = numThreads, provider = "cpu", debug = false,
            ),
            ruleFsts = RULE_FILES.joinToString(",") { File(directory, it).absolutePath },
            maxNumSentences = 1,
        )).also { engine = it }
    }

    companion object {
        val RULE_FILES: List<String> = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst")
        val REQUIRED_FILES: List<String> = listOf("model.int8.onnx", "lexicon.txt", "tokens.txt") + RULE_FILES

        fun requiredFiles(modelFileName: String = "model.int8.onnx"): List<String> {
            require(modelFileName == "model.int8.onnx" || modelFileName == "model.onnx") {
                "Unsupported MeloTTS model filename"
            }
            return listOf(modelFileName, "lexicon.txt", "tokens.txt") + RULE_FILES
        }

        fun validateDirectory(directory: File, modelFileName: String = "model.int8.onnx") {
            require(directory.isDirectory) { "MeloTTS model directory is missing" }
            requiredFiles(modelFileName).forEach { name ->
                val file = File(directory, name)
                require(file.isFile && file.length() > 0) { "MeloTTS model asset is missing: $name" }
            }
        }
    }
}
