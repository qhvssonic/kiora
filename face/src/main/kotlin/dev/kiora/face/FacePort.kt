package dev.kiora.face

import dev.kiora.embedding.EmbeddingVector
import kotlin.math.hypot

/** Upright, unmirrored pixels. URI permissions and image orientation belong to the host. */
class FaceImage(val width: Int, val height: Int, pixels: IntArray) {
    init {
        require(width in 1..4096 && height in 1..4096)
        require(pixels.size == width * height)
    }
    private val data = pixels.copyOf()
    fun pixels(): IntArray = data.copyOf()
}

data class FacePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}

data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(right > left && bottom > top)
    }
    val width get() = right - left
    val height get() = bottom - top
}

/** Landmark order: image-left eye, image-right eye, nose, image-left/right mouth corners. */
data class DetectedFace(val box: FaceBox, val landmarks: List<FacePoint>, val confidence: Float) {
    init { require(landmarks.size == 5 && confidence.isFinite() && confidence in 0f..1f) }
}

data class FaceObservation(
    val face: DetectedFace,
    val embedding: EmbeddingVector,
    /** Screening only, not a calibrated biometric quality score or liveness result. */
    val qualityIssues: Set<FaceQualityIssue> = emptySet(),
)

enum class FaceQualityIssue { TOO_SMALL, LOW_CONFIDENCE, BLURRED, EXTREME_POSE }

interface FacePort {
    /** Includes weights and preprocessing identity. Never compare vectors across identities. */
    val modelId: String
    suspend fun analyze(image: FaceImage): List<FaceObservation>
    /** Serialized with inference; releases native sessions but not a shared runtime environment. */
    suspend fun close()
}

data class FaceTemplate(val personId: String, val embedding: EmbeddingVector) {
    init { require(personId.isNotBlank()) }
}
data class FaceCandidate(val personId: String, val similarity: Double)
enum class FaceMatchStatus { MATCH, UNKNOWN, AMBIGUOUS }
data class FaceSearchResult(val status: FaceMatchStatus, val candidates: List<FaceCandidate>) {
    val matchedPersonId: String? get() = if (status == FaceMatchStatus.MATCH) candidates.firstOrNull()?.personId else null
}

/** Hosts must calibrate both settings against their gallery size and target devices/data. */
data class FaceMatchPolicy(val minimumSimilarity: Double, val minimumMargin: Double) {
    init {
        require(minimumSimilarity.isFinite() && minimumSimilarity in -1.0..1.0)
        require(minimumMargin.isFinite() && minimumMargin in 0.0..2.0)
    }
}

object FaceMatcher {
    /** Multiple templates vote by their best score; the runner-up must be a different person. */
    fun search(query: EmbeddingVector, templates: Iterable<FaceTemplate>, policy: FaceMatchPolicy,
               limit: Int = 5): FaceSearchResult {
        require(limit in 1..100)
        val best = mutableMapOf<String, Double>()
        for (template in templates) {
            val score = query.cosineSimilarity(template.embedding)
            best[template.personId] = maxOf(best[template.personId] ?: -1.0, score)
        }
        val ranked = best.map { FaceCandidate(it.key, it.value) }
            .sortedWith(compareByDescending<FaceCandidate> { it.similarity }.thenBy { it.personId })
        val status = when {
            ranked.isEmpty() || ranked[0].similarity < policy.minimumSimilarity -> FaceMatchStatus.UNKNOWN
            ranked.size > 1 && ranked[0].similarity - ranked[1].similarity < policy.minimumMargin -> FaceMatchStatus.AMBIGUOUS
            else -> FaceMatchStatus.MATCH
        }
        return FaceSearchResult(status, ranked.take(limit))
    }
}

/** Least-squares similarity transform, without OpenCV or Android dependencies. */
object FaceAlignment {
    private val target = listOf(FacePoint(38.2946f, 51.6963f), FacePoint(73.5318f, 51.5014f),
        FacePoint(56.0252f, 71.7366f), FacePoint(41.5493f, 92.3655f), FacePoint(70.7299f, 92.2041f))

    /** Returns [a, -b, tx, b, a, ty], mapping source coordinates to the 112px ArcFace crop. */
    fun transform(points: List<FacePoint>): FloatArray {
        require(points.size == 5)
        val sx = points.map { it.x.toDouble() }.average()
        val sy = points.map { it.y.toDouble() }.average()
        val dx = target.map { it.x.toDouble() }.average()
        val dy = target.map { it.y.toDouble() }.average()
        var den = 0.0; var real = 0.0; var imaginary = 0.0
        for (i in points.indices) {
            val x = points[i].x - sx; val y = points[i].y - sy
            val u = target[i].x - dx; val v = target[i].y - dy
            den += x*x + y*y; real += x*u + y*v; imaginary += x*v - y*u
        }
        require(den > 1e-6) { "Degenerate face landmarks" }
        val a = real/den; val b = imaginary/den
        require(hypot(a, b) > 1e-6) { "Invalid face alignment" }
        return floatArrayOf(a.toFloat(), -b.toFloat(), (dx-a*sx+b*sy).toFloat(),
            b.toFloat(), a.toFloat(), (dy-b*sx-a*sy).toFloat())
    }
}
