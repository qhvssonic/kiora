package dev.kiora.document

/** Coordinates refer to the decoded image used for OCR, after sampling and EXIF rotation. */
data class OcrBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class OcrLine(
    val text: String,
    val bounds: OcrBounds?,
    val confidence: Float?,
)

data class OcrTiming(
    val totalMs: Long,
    val detectionMs: Long? = null,
    val recognitionMs: Long? = null,
)

data class OcrPage(
    val text: String,
    val lines: List<OcrLine>,
    val timing: OcrTiming? = null,
)

/** Reads source text from one image. Capture, retention, and user corrections belong to the host. */
fun interface DocumentOcrPort {
    suspend fun recognize(imageBytes: ByteArray): OcrPage
}
