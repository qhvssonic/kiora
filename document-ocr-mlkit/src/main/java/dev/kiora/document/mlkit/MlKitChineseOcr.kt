package dev.kiora.document.mlkit

import android.media.ExifInterface
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.kiora.document.DocumentOcrPort
import dev.kiora.document.OcrBounds
import dev.kiora.document.OcrLine
import dev.kiora.document.OcrPage
import dev.kiora.document.OcrTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled Chinese OCR model. It works without a first-use model download. */
class MlKitChineseOcr : DocumentOcrPort, AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(imageBytes: ByteArray): OcrPage = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        require(imageBytes.isNotEmpty()) { "imageBytes must not be empty" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法读取图片")
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(imageBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, rotation))
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
        // The ML Kit task can outlive a cancelled coroutine; retain pixels until it finishes.
        task.addOnCompleteListener { bitmap.recycle() }
        val result = suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
        OcrPage(
            text = result.text,
            lines = result.textBlocks.flatMap { block -> block.lines.map { line ->
                OcrLine(
                    text = line.text,
                    bounds = line.boundingBox?.let { OcrBounds(it.left, it.top, it.right, it.bottom) },
                    confidence = line.confidence.takeIf { it > 0f },
                )
            } },
            timing = OcrTiming(totalMs = SystemClock.elapsedRealtime() - started),
        )
    }

    override fun close() = recognizer.close()

    private companion object { const val MAX_IMAGE_SIDE = 4096 }
}
