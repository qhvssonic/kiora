package dev.kiora.document.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MlKitChineseOcrTest {
    @Test fun recognizesPrintedDocumentOffline() = runBlocking {
        val bitmap = Bitmap.createBitmap(1600, 440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
        canvas.drawText("设备编号 A102", 60f, 170f, paint)
        canvas.drawText("检查日期 2026-10-20", 60f, 330f, paint)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        MlKitChineseOcr().use { recognizer ->
            val started = SystemClock.elapsedRealtime()
            val page = recognizer.recognize(bytes)
            val firstWallMs = SystemClock.elapsedRealtime() - started
            val measured = List(5) {
                val runStarted = SystemClock.elapsedRealtime()
                recognizer.recognize(bytes)
                SystemClock.elapsedRealtime() - runStarted
            }.sorted()
            Log.i("KioraMlKitOCR", "firstWall=${firstWallMs}ms warmMedianWall=${measured[2]}ms text=${page.text}")
            assertTrue("OCR did not read the printed test image: ${page.text}",
                page.text.contains("A102"))
            assertTrue("OCR returned no line bounds", page.lines.any { it.bounds != null })
        }
    }
}
