package dev.kiora.embedding.mediapipe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device smoke test. Copy the published model into the test app's files directory first. */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemma2PortTest {
    @Test fun embedsChineseTextAndImageInTheSameSpace() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val model = File(context.filesDir, "embeddinggemma-2-text-vision-440m.litertlm")
            assumeTrue("Import the published model into ${model.absolutePath}", model.isFile)
            val port = EmbeddingGemma2Port(context, model, "embeddinggemma2-440m-smoke")
            try {
                val started = SystemClock.elapsedRealtime()
                port.prepare()
                val loadMs = SystemClock.elapsedRealtime() - started

                val queryStarted = SystemClock.elapsedRealtime()
                val query = port.embedQuery("红色方块")
                val queryMs = SystemClock.elapsedRealtime() - queryStarted

                val document = port.embedDocument("红色方块", "颜色测试")
                val imageStarted = SystemClock.elapsedRealtime()
                val image = port.embedImage(redSquarePng())
                val imageMs = SystemClock.elapsedRealtime() - imageStarted
                val rgb565 = Bitmap.createBitmap(32, 32, Bitmap.Config.RGB_565)
                val alternateImage = try {
                    rgb565.eraseColor(Color.RED)
                    port.embedBitmap(rgb565)
                } finally {
                    rgb565.recycle()
                }

                assertEquals(768, query.dimension)
                assertEquals(768, document.dimension)
                assertEquals(768, image.dimension)
                assertEquals(768, alternateImage.dimension)
                assertEquals(1.0, query.cosineSimilarity(query), 0.0001)
                assertTrue(query.cosineSimilarity(image).isFinite())
                Log.i("KioraEmbeddingGemma2",
                    "load=${loadMs}ms query=${queryMs}ms image=${imageMs}ms " +
                        "textImageCosine=${query.cosineSimilarity(image)}")
            } finally {
                port.close()
            }
        }
    }

    private fun redSquarePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(96f, 96f, 416f, 416f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
