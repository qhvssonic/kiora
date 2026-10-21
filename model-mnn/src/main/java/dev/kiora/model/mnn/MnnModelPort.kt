package dev.kiora.model.mnn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ModelResponse
import dev.kiora.core.ModelUsage
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolImage
import dev.kiora.core.UsageReportingModelPort
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-process MNN model port. Vision requires a compatible model package. */
class MnnModelPort(
    private val configFile: File,
    private val maxOutputTokens: Int = 768,
    private val imageCacheDirectory: File = File(
        System.getProperty("java.io.tmpdir") ?: configFile.parentFile?.absolutePath ?: ".",
        "kiora-mnn-images",
    ),
) : UsageReportingModelPort {
    private val lock = Mutex()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kiora-mnn").apply { isDaemon = true }
    }
    private var handle = 0L
    private var closed = false
    val supportsVision: Boolean by lazy {
        val root = configFile.parentFile
        val metadata = File(root, "llm_config.json")
        val declared = runCatching {
            Json.parseToJsonElement(metadata.readText()).jsonObject["is_visual"]
                ?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
        declared && File(root, "visual.mnn").isFile && File(root, "visual.mnn.weight").isFile
    }

    init {
        require(configFile.isFile && configFile.name == "config.json") {
            "MNN model needs an existing config.json and its companion files"
        }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
    }

    suspend fun prepare() = lock.withLock { ensureLoaded() }

    override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
        infer(messages, tools, null).assistant

    override suspend fun respondStreaming(messages: List<AgentMessage>, tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit) = infer(messages, tools, onTextDelta).assistant

    override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
        infer(messages, tools, null)

    override suspend fun respondStreamingWithUsage(messages: List<AgentMessage>,
        tools: List<ToolDefinition>, onTextDelta: suspend (String) -> Unit) =
        infer(messages, tools, onTextDelta)

    private suspend fun infer(messages: List<AgentMessage>, tools: List<ToolDefinition>,
        onTextDelta: (suspend (String) -> Unit)?): ModelResponse = lock.withLock {
        require(messages.isNotEmpty()) { "Model history is empty" }
        val nativeHandle = ensureLoaded()
        val roles = mutableListOf("system")
        val contents = mutableListOf(escapeMediaTags(MnnToolProtocol.instruction(tools)))
        val stagedImages = mutableListOf<File>()
        try {
            messages.forEach { message ->
                when (message) {
                    is AgentMessage.User -> {
                        roles += "user"
                        contents += imagePrompt(message.text, message.images, stagedImages)
                    }
                    is AgentMessage.ContextSummary -> {
                        roles += "system"; contents += "Earlier conversation summary:\n${escapeMediaTags(message.text)}"
                    }
                    is AgentMessage.Assistant -> {
                        roles += "assistant"
                        contents += escapeMediaTags(if (message.toolCalls.isEmpty()) message.text else
                            message.toolCalls.joinToString("\n") { MnnToolProtocol.toolHistory(it) })
                    }
                    is AgentMessage.ToolResult -> {
                        roles += "tool"
                        contents += imagePrompt("${message.toolName}: ${message.text}", message.images, stagedImages)
                    }
                }
            }
        val output = StringBuilder()
        var heldForTool = false
        var decisionMade = false
        val error = AtomicReference<Throwable?>(null)
        val cancelled = AtomicBoolean(false)
        val finished = CompletableDeferred<Unit>()
        MnnNativeBridge.clearCancel(nativeHandle)
        val metrics = try {
            suspendCancellableCoroutine<LongArray> { continuation ->
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    MnnNativeBridge.cancel(nativeHandle)
                }
                try {
                    worker.execute {
                        try {
                            if (cancelled.get()) return@execute
                            val result = MnnNativeBridge.generate(nativeHandle, roles.toTypedArray(),
                                contents.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                                maxOutputTokens, object : MnnNativeBridge.TokenSink {
                                    override fun onBytes(bytes: ByteArray): Boolean {
                                        if (cancelled.get()) return true
                                        val chunk = bytes.toString(Charsets.UTF_8)
                                        output.append(chunk)
                                        if (onTextDelta != null) {
                                            if (!decisionMade) {
                                                val first = output.toString().trimStart().firstOrNull()
                                                if (first != null) {
                                                    decisionMade = true
                                                    heldForTool = first == '{' || first == '`' || first == '<'
                                                    if (!heldForTool) try {
                                                        runBlocking { onTextDelta(output.toString()) }
                                                    } catch (failure: Throwable) {
                                                        error.set(failure)
                                                        return true
                                                    }
                                                }
                                            } else if (!heldForTool) try {
                                                runBlocking { onTextDelta(chunk) }
                                            } catch (failure: Throwable) {
                                                error.set(failure)
                                                return true
                                            }
                                        }
                                        return cancelled.get()
                                    }
                                })
                            val failure = error.get()
                            if (failure != null) continuation.resumeWithException(failure)
                            else if (continuation.isActive) continuation.resume(result)
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                } catch (failure: Throwable) {
                    finished.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } finally {
            // A cancelled coroutine must not release or reuse a native engine still generating.
            withContext(NonCancellable) { finished.await() }
        }
        val text = output.toString()
        val toolCall = MnnToolProtocol.parse(text, tools)
        if (heldForTool && toolCall == null && onTextDelta != null) onTextDelta(text)
        val assistant = if (toolCall == null) AgentMessage.Assistant(text)
            else AgentMessage.Assistant("", listOf(toolCall))
        ModelResponse(assistant,
            usage = ModelUsage(inputTokens = metrics.getOrNull(0)?.toInt()?.coerceAtLeast(0),
                outputTokens = metrics.getOrNull(1)?.toInt()?.coerceAtLeast(0)),
            model = ModelIdentity(configFile.parentFile?.name ?: configFile.name, "mnn-local"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stagedImages.forEach(File::delete) }
        }
    }

    private suspend fun imagePrompt(text: String, images: List<ToolImage>, staged: MutableList<File>): String =
        buildString {
            append(escapeMediaTags(text))
            require(images.isEmpty() || supportsVision) {
                "MNN model package needs is_visual=true, visual.mnn and visual.mnn.weight for images"
            }
            for (image in images) {
                val file = withContext(Dispatchers.IO) { stageImage(image, staged) }
                append("\n<img>").append(file.absolutePath).append("</img>")
            }
        }

    private fun stageImage(image: ToolImage, staged: MutableList<File>): File {
        require(image.data.length <= 12 * 1024 * 1024) { "MNN image exceeds the input limit" }
        val bytes = Base64.getDecoder().decode(image.data)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "MNN image cannot be decoded" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("MNN image cannot be decoded")
        try {
            check(imageCacheDirectory.isDirectory || imageCacheDirectory.mkdirs()) {
                "MNN image cache is unavailable"
            }
            val png = image.mimeType != "image/jpeg"
            val file = File.createTempFile("kiora-mnn-", if (png) ".png" else ".jpg",
                imageCacheDirectory)
            staged += file
            file.outputStream().use { output ->
                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    90, output)) { "MNN image conversion failed" }
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun escapeMediaTags(text: String): String = text.replace("<img>", "&lt;img&gt;")
        .replace("<audio>", "&lt;audio&gt;")

    private suspend fun ensureLoaded(): Long {
        check(!closed) { "MNN model is closed" }
        if (handle == 0L) handle = withContext(Dispatchers.Default) {
            MnnNativeBridge.load(configFile.absolutePath)
        }
        return handle
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        val old = handle
        handle = 0L
        if (old != 0L) withContext(Dispatchers.Default) { MnnNativeBridge.release(old) }
        worker.shutdown()
    }
}

internal object MnnNativeBridge {
    init { System.loadLibrary("kiora_mnn_bridge") }
    interface TokenSink { fun onBytes(bytes: ByteArray): Boolean }
    external fun load(configPath: String): Long
    external fun generate(handle: Long, roles: Array<String>, contents: Array<ByteArray>,
        maxTokens: Int, sink: TokenSink): LongArray
    external fun cancel(handle: Long)
    external fun clearCancel(handle: Long)
    external fun release(handle: Long)
}
