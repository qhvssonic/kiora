package dev.kiora.android.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.javascriptengine.JavaScriptSandbox

@RunWith(AndroidJUnit4::class)
class IsolatedJavaScriptToolTest {
    @Test
    fun evaluatesArithmeticInIsolatedProcess() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = IsolatedJavaScriptTool(context)
        val result = tool.execute(JsonObject(mapOf("code" to JsonPrimitive("1 + 1"))))
        assertEquals("2", result)
    }
}
