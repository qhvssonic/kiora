package dev.kiora.embedding.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import dev.kiora.embedding.EmbeddingPort
import dev.kiora.embedding.EmbeddingVector
import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Optional Android adapter for an imported EmbeddingGemma 2 LiteRT-LM bundle. */
class EmbeddingGemma2Port(
    context: Context,
    private val modelFile: File,
    override val modelId: String,
    private val outputDimension: Int = 768,
    private val queryPrefix: String = "task: search result | query: ",
    private val backend: EmbeddingBackend = EmbeddingBackend.CPU,
) : EmbeddingPort {
    private val appContext = context.applicationContext ?: context
    private val lock = Mutex()
    private var engine: UniversalEmbedder? = null
    private var modelDescriptor: ParcelFileDescriptor? = null
    private var closed = false

    init {
        require(modelId.isNotBlank()) { "modelId must identify the model and preprocessing version" }
        require(modelFile.isFile && modelFile.extension.equals("litertlm", ignoreCase = true)) {
            "An imported .litertlm model file is required"
        }
        require(outputDimension in setOf(128, 256, 512, 768)) {
            "EmbeddingGemma 2 supports 128, 256, 512 or 768 dimensions"
        }
    }

    /** Loads the model on a worker thread; the host decides when to warm it up. */
    suspend fun prepare(): Unit = withContext(Dispatchers.Default) {
        lock.withLock { runtime(); Unit }
    }

    override suspend fun embedQuery(text: String): EmbeddingVector = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Query is empty" }
        currentCoroutineContext().ensureActive()
        lock.withLock {
            vector(runtime().embedText(queryPrefix + text))
        }
    }

    override suspend fun embedDocument(text: String, title: String?): EmbeddingVector =
        withContext(Dispatchers.Default) {
            require(text.isNotBlank()) { "Document is empty" }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                vector(runtime().embedText("title: ${title?.takeIf(String::isNotBlank) ?: "none"} | text: $text"))
            }
        }

    override suspend fun embedImage(imageBytes: ByteArray): EmbeddingVector = withContext(Dispatchers.Default) {
        require(imageBytes.isNotEmpty() && imageBytes.size <= MAX_IMAGE_BYTES) {
            "Image must be nonempty and at most 32 MiB"
        }
        val bitmap = decodeSampled(imageBytes)
        try {
            embedBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The host can provide an already oriented, downsampled image without re-encoding it. */
    suspend fun embedBitmap(bitmap: Bitmap): EmbeddingVector = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "Image has been recycled" }
        currentCoroutineContext().ensureActive()
        lock.withLock {
            val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("Could not convert image to ARGB_8888")
            try {
                val image = BitmapImageBuilder(input).build()
                try {
                    vector(runtime().embedImage(image))
                } finally {
                    image.close()
                }
            } finally {
                if (input !== bitmap) input.recycle()
            }
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.Default) {
        lock.withLock {
            if (!closed) {
                closed = true
                try {
                    engine?.close()
                } finally {
                    engine = null
                    modelDescriptor?.close()
                    modelDescriptor = null
                }
            }
        }
    }

    private fun runtime(): UniversalEmbedder {
        check(!closed) { "Embedding model is closed" }
        engine?.let { return it }
        val descriptor = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val delegate = if (backend == EmbeddingBackend.GPU) Delegate.GPU else Delegate.CPU
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetFileDescriptor(descriptor.fd).build())
                .setTextDelegate(delegate)
                .setVisionDelegate(delegate)
                .setL2Normalize(true)
                .build()
            return UniversalEmbedder.createFromOptions(appContext, options).also {
                engine = it
                modelDescriptor = descriptor
            }
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }
    }

    private fun vector(result: EmbeddingResult): EmbeddingVector {
        val values = result.embeddings().singleOrNull()?.floatEmbedding()
            ?: error("Embedding model returned no unique float vector")
        check(values.size == 768) { "Expected a 768-dimensional EmbeddingGemma 2 vector" }
        return EmbeddingVector(modelId, values).truncateAndNormalize(outputDimension)
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image data" }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIDE) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: error("Failed to decode image")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(-90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90f)
            else -> return decoded
        }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        } finally {
            decoded.recycle()
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val MAX_IMAGE_SIDE = 1024
    }
}

enum class EmbeddingBackend { CPU, GPU }
