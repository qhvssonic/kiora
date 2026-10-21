package dev.kiora.document.paddle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import dev.kiora.document.DocumentOcrPort
import dev.kiora.document.OcrBounds
import dev.kiora.document.OcrLine
import dev.kiora.document.OcrPage
import dev.kiora.document.OcrTiming
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional bridge to PaddleOCR's Android SDK and PP-OCRv6 ONNX models. */
class PaddleDocumentOcr private constructor(private val engine: PaddleOCR) : DocumentOcrPort {
    val coldLoadTimeMs: Long get() = engine.coldLoadTimeMs

    override suspend fun recognize(imageBytes: ByteArray): OcrPage {
        require(imageBytes.isNotEmpty()) { "imageBytes must not be empty" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片尺寸" }
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取图片")
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(imageBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val upright = if (rotation == 0f) decoded else try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(rotation) }, true)
        } finally {
            decoded.recycle()
        }
        try {
            val result = engine.recognize(upright)
            return OcrPage(
                text = result.results.joinToString("\n") { it.text },
                lines = result.results.map { item ->
                    val points = item.box.points
                    OcrLine(
                        text = item.text,
                        bounds = OcrBounds(points.minOf { it.x }.toInt(), points.minOf { it.y }.toInt(),
                            points.maxOf { it.x }.toInt(), points.maxOf { it.y }.toInt()),
                        confidence = item.confidence.takeIf(Float::isFinite),
                    )
                },
                timing = OcrTiming(result.totalTimeMs, result.detectionTimeMs, result.recognitionTimeMs),
            )
        } finally {
            upright.recycle()
        }
    }

    suspend fun release() = engine.release()

    companion object {
        private const val MAX_IMAGE_SIDE = 4096
        private const val DEFAULT_DETECTION_SIDE = 1536

        /** Loads models from the official AAR's default assets. Call from a coroutine. */
        suspend fun create(context: Context, maxDetectionSide: Int = DEFAULT_DETECTION_SIDE): PaddleDocumentOcr {
            require(maxDetectionSide in 320..4096) { "maxDetectionSide must be between 320 and 4096" }
            withContext(Dispatchers.IO) {
                check(OpenCVUtils.init(context.applicationContext)) { "无法加载 OpenCV 本机库" }
            }
            return PaddleDocumentOcr(PaddleOCR.create(context,
                PaddleOCRConfig(detMaxSideLimit = maxDetectionSide)))
        }
    }
}
