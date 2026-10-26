package dev.kiora.face.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import dev.kiora.embedding.EmbeddingVector
import dev.kiora.face.*
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.*

/** buffalo_sc: det_500m.onnx (SCRFD) + w600k_mbf.onnx (MobileFaceNet). CPU baseline. */
class InsightFacePort(
    private val directory: File,
    override val modelId: String,
    private val threads: Int = 2,
) : FacePort {
    private val lock = Mutex()
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null
    private var closed = false
    init { require(modelId.isNotBlank()); require(threads in 1..8) }

    private fun load() {
        check(!closed) { "Face engine is closed" }
        if (detector != null) return
        validateDirectory(directory)
        val env = OrtEnvironment.getEnvironment()
        fun session(file: String) = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            env.createSession(File(directory, file).absolutePath, options)
        }
        val det = session(DETECTOR)
        var rec: OrtSession? = null
        try {
            rec = session(RECOGNIZER)
            check(det.numInputs == 1L && det.numOutputs == 9L) { "Expected SCRFD with 9 outputs" }
            check(rec.numInputs == 1L && rec.numOutputs == 1L) { "Expected MobileFaceNet" }
            val shape = (rec.inputInfo.values.single().info as TensorInfo).shape
            check(shape.size == 4 && shape[0] <= 1 && shape[1] == 3L && shape[2] == 112L && shape[3] == 112L) {
                "Expected 112px RGB face model"
            }
            detector = det; recognizer = rec
        } catch (error: Throwable) { rec?.close(); det.close(); throw error }
    }

    /** Opens sessions to validate imports before the host commits a model switch. */
    suspend fun prepare() = withContext(Dispatchers.IO) { lock.withLock { load() } }

    override suspend fun analyze(image: FaceImage): List<FaceObservation> = withContext(Dispatchers.Default) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            load()
            val bitmap = Bitmap.createBitmap(image.pixels(), image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                val faces = detect(bitmap)
                faces.map { face ->
                    currentCoroutineContext().ensureActive()
                    val crop = align(bitmap, face.landmarks)
                    try {
                        val output = run(requireNotNull(recognizer), toTensor(crop, 127.5f), 112).single()
                        check(output.size == 512 && output.all(Float::isFinite)) { "Invalid face feature output" }
                        val norm = sqrt(output.sumOf { it.toDouble()*it })
                        check(norm > 1e-9)
                        val normalized = FloatArray(output.size) { (output[it]/norm).toFloat() }
                        FaceObservation(face, EmbeddingVector(modelId, normalized), quality(face, crop))
                    } finally { crop.recycle() }
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun detect(bitmap: Bitmap): List<DetectedFace> {
        val size = 640
        val scale = min(size.toFloat()/bitmap.width, size.toFloat()/bitmap.height)
        val w = (bitmap.width*scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height*scale).toInt().coerceAtLeast(1)
        // Official SCRFD pads only the bottom/right and rescales outputs by the height ratio.
        val detScale = h.toFloat()/bitmap.height
        val input = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            Canvas(input).apply { drawColor(Color.BLACK); drawBitmap(resized, 0f, 0f, null) }
            val outputs = run(requireNotNull(detector), toTensor(input, 128f), size)
            val candidates = mutableListOf<DetectedFace>()
            for ((level, stride) in listOf(8, 16, 32).withIndex()) {
                val scores = outputs[level]; val boxes = outputs[level+3]; val points = outputs[level+6]
                val width = size/stride; val anchors = width*width*2
                check(scores.size == anchors && boxes.size == anchors*4 && points.size == anchors*10) {
                    "Incompatible SCRFD output layout"
                }
                for (i in 0 until anchors) {
                    val score = scores[i]
                    if (!score.isFinite() || score < .6f || score > 1f) continue
                    val x = (i/2 % width)*stride.toFloat(); val y = (i/2/width)*stride.toFloat()
                    val left = ((x-boxes[i*4]*stride)/detScale).coerceIn(0f, bitmap.width.toFloat())
                    val top = ((y-boxes[i*4+1]*stride)/detScale).coerceIn(0f, bitmap.height.toFloat())
                    val right = ((x+boxes[i*4+2]*stride)/detScale).coerceIn(0f, bitmap.width.toFloat())
                    val bottom = ((y+boxes[i*4+3]*stride)/detScale).coerceIn(0f, bitmap.height.toFloat())
                    if (!listOf(left, top, right, bottom).all(Float::isFinite) || right <= left || bottom <= top) continue
                    val landmarks = (0..4).map { p ->
                        FacePoint((x+points[i*10+p*2]*stride)/detScale,
                            (y+points[i*10+p*2+1]*stride)/detScale)
                    }
                    candidates += DetectedFace(FaceBox(left, top, right, bottom), landmarks, score)
                }
            }
            val kept = mutableListOf<DetectedFace>()
            for (candidate in candidates.sortedByDescending { it.confidence }) {
                if (kept.none { iou(it.box, candidate.box) > .4f }) kept += candidate
            }
            check(kept.size <= 100) { "Too many faces in one image; use a closer image" }
            return kept
        } finally { if (resized !== bitmap) resized.recycle(); input.recycle() }
    }

    private fun run(session: OrtSession, input: FloatArray, size: Int): List<FloatArray> {
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input),
            longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                return (0 until result.size()).map { i ->
                    val buffer = (result[i] as OnnxTensor).floatBuffer
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }
    }

    private fun toTensor(bitmap: Bitmap, denominator: Float): FloatArray {
        val n = bitmap.width*bitmap.height
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(n*3).also { out -> for (i in pixels.indices) {
            out[i] = (Color.red(pixels[i])-127.5f)/denominator
            out[n+i] = (Color.green(pixels[i])-127.5f)/denominator
            out[2*n+i] = (Color.blue(pixels[i])-127.5f)/denominator
        } }
    }

    private fun align(bitmap: Bitmap, points: List<FacePoint>): Bitmap {
        val a = FaceAlignment.transform(points)
        val matrix = Matrix().apply { setValues(floatArrayOf(a[0], a[1], a[2], a[3], a[4], a[5], 0f, 0f, 1f)) }
        return Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.BLACK); drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
    }

    private fun quality(face: DetectedFace, crop: Bitmap): Set<FaceQualityIssue> = buildSet {
        if (min(face.box.width, face.box.height) < 60f) add(FaceQualityIssue.TOO_SMALL)
        if (face.confidence < .75f) add(FaceQualityIssue.LOW_CONFIDENCE)
        val eyes = face.landmarks.take(2)
        if (hypot(eyes[1].x-eyes[0].x, eyes[1].y-eyes[0].y) < face.box.width*.18f)
            add(FaceQualityIssue.EXTREME_POSE)
        val pixels = IntArray(112*112); crop.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        fun gray(i: Int) = Color.red(pixels[i])*.299 + Color.green(pixels[i])*.587 + Color.blue(pixels[i])*.114
        var sum = 0.0; var squares = 0.0; var n = 0
        for (y in 20..91) for (x in 20..91) {
            val i = y*112+x
            val v = gray(i-1)+gray(i+1)+gray(i-112)+gray(i+112)-4*gray(i)
            sum += v; squares += v*v; n++
        }
        if (squares/n-(sum/n).pow(2) < 15.0) add(FaceQualityIssue.BLURRED)
    }

    override suspend fun close() = withContext(Dispatchers.IO) { lock.withLock {
        closed = true
        try { recognizer?.close() } finally { recognizer = null; detector?.close(); detector = null }
        Unit
    } }

    companion object {
        const val DETECTOR = "det_500m.onnx"
        const val RECOGNIZER = "w600k_mbf.onnx"
        val REQUIRED_FILES = listOf(DETECTOR, RECOGNIZER)
        fun validateDirectory(directory: File) {
            REQUIRED_FILES.forEach { name ->
                val f = File(directory, name)
                require(f.isFile && f.length() in 1..64L*1024*1024) { "Missing or invalid $name" }
            }
        }
        private fun iou(a: FaceBox, b: FaceBox): Float {
            val area = max(0f, min(a.right,b.right)-max(a.left,b.left)+1)*
                max(0f,min(a.bottom,b.bottom)-max(a.top,b.top)+1)
            return area/((a.width+1)*(a.height+1)+(b.width+1)*(b.height+1)-area)
        }
    }
}
