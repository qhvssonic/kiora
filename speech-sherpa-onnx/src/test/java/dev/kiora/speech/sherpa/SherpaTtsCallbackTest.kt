package dev.kiora.speech.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SherpaTtsCallbackTest {
    @Test fun exposesExactJniSignatureAndPreservesContinueAndStopResults() {
        val samples = floatArrayOf(0.1f, -0.2f)
        var keepGoing = 1
        val callback = SherpaTtsCallback {
            assertSame(samples, it)
            keepGoing
        }
        // Match GetMethodID("invoke", "([F)Ljava/lang/Integer;") in sherpa's JNI.
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Int::class.javaObjectType, method.returnType)
        assertEquals(1, method.invoke(callback, samples))
        keepGoing = 0
        assertEquals(0, method.invoke(callback, samples))
    }
}
