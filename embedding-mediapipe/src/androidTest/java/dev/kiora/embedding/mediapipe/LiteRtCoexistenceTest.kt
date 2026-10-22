package dev.kiora.embedding.mediapipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.model.litert.LiteRtBackend
import dev.kiora.model.litert.LiteRtModelPort
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the existing chat adapter with the LiteRT-LM version selected by retrieval. */
@RunWith(AndroidJUnit4::class)
class LiteRtCoexistenceTest {
    @Test fun gemma4StillResponds() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val model = File(context.filesDir, "gemma-4-E2B-it.litertlm")
            assumeTrue("Import the Gemma 4 model into ${model.absolutePath}", model.isFile)
            val port = LiteRtModelPort(model, backend = LiteRtBackend.CPU)
            try {
                val response = port.respond(listOf(AgentMessage.User("请用一个词回答：你好")), emptyList())
                assertTrue("Gemma 4 returned empty text", response.text.isNotBlank())
            } finally {
                port.close()
            }
        }
    }
}
