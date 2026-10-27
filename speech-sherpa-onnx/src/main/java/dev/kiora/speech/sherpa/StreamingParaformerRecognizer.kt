package dev.kiora.speech.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.SpeechRecognitionUpdate
import dev.kiora.speech.StreamingSpeechRecognitionPort
import dev.kiora.speech.StreamingSpeechSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** CPU streaming Paraformer via the same sherpa-onnx runtime as SenseVoice and MeloTTS.
 * Each openSession owns a model and stream. Hosts should serialize sessions to bound model memory.
 * Uses 0.8 s right-context padding at endpoints, as in sherpa-onnx's Android Paraformer example.
 */
class StreamingParaformerRecognizer(
    private val directory: File,
    private val numThreads: Int = 2,
) : StreamingSpeechRecognitionPort {
    init { require(numThreads in 1..8); validateDirectory(directory) }

    override suspend fun openSession(): StreamingSpeechSession {
        var owned: Session? = null
        try {
            return withContext(Dispatchers.Default) {
                val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = File(directory, "encoder.int8.onnx").absolutePath,
                            decoder = File(directory, "decoder.int8.onnx").absolutePath,
                        ),
                        tokens = File(directory, "tokens.txt").absolutePath,
                        numThreads = numThreads, provider = "cpu", modelType = "paraformer",
                    ),
                    enableEndpoint = true,
                ))
                try { Session(recognizer).also { owned = it } }
                catch (error: Throwable) { recognizer.release(); throw error }
            }
        } catch (error: Throwable) {
            // withContext can be cancelled while dispatching the successfully created result.
            withContext(NonCancellable + Dispatchers.Default) { owned?.close() }
            throw error
        }
    }

    private class Session(private val recognizer: OnlineRecognizer) : StreamingSpeechSession {
        private val stream = recognizer.createStream()
        private val lock = Any()
        private var closed = false
        private var finished = false
        private var segment = 0L
        private var revision = 0L
        private var previous = ""

        override suspend fun accept(audio: Pcm16Audio): List<SpeechRecognitionUpdate> =
            withContext(Dispatchers.Default) {
                require(audio.sampleRateHz == 16_000) { "Streaming Paraformer expects 16 kHz mono PCM" }
                require(audio.samples.size <= 16_000) { "Feed chunks of at most one second" }
                val context = currentCoroutineContext()
                synchronized(lock) {
                    check(!closed && !finished) { "Speech session has ended" }
                    context.ensureActive()
                    stream.acceptWaveform(FloatArray(audio.samples.size) { audio.samples[it] / 32768f }, 16_000)
                    decode(context)
                    if (recognizer.isEndpoint(stream)) {
                        padAndDecode(context)
                        val result = update(final = true)
                        recognizer.reset(stream)
                        segment++; revision = 0; previous = ""
                        result
                    } else update(final = false)
                }
            }

        override suspend fun finish(): List<SpeechRecognitionUpdate> = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            synchronized(lock) {
                check(!closed) { "Speech session is closed" }
                if (finished) return@synchronized emptyList()
                context.ensureActive()
                stream.acceptWaveform(FloatArray(12_800), 16_000)
                stream.inputFinished()
                decode(context)
                finished = true
                update(final = true)
            }
        }

        private fun padAndDecode(context: CoroutineContext) {
            stream.acceptWaveform(FloatArray(12_800), 16_000)
            decode(context)
        }

        private fun decode(context: CoroutineContext) {
            while (recognizer.isReady(stream)) {
                context.ensureActive()
                recognizer.decode(stream)
            }
            context.ensureActive()
        }

        private fun update(final: Boolean): List<SpeechRecognitionUpdate> {
            val text = recognizer.getResult(stream).text.trim()
            if (!final && text == previous) return emptyList()
            previous = text
            return listOf(SpeechRecognitionUpdate(segment, revision++, text, final))
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                try { stream.release() } finally { recognizer.release() }
            }
        }
    }

    companion object {
        fun validateDirectory(directory: File) {
            for (name in listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")) {
                require(File(directory, name).let { it.isFile && it.length() > 0 }) { "Missing or empty $name" }
            }
        }
    }
}
