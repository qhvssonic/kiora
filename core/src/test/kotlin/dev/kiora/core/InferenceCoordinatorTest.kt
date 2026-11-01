package dev.kiora.core

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class InferenceCoordinatorTest {
    @Test fun `cancelled native work completes cleanup before next operation starts`() = runBlocking {
        val coordinator = InferenceCoordinator()
        val started = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = launch {
            coordinator.withInference("asr") {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { closing.complete(Unit); release.await() } }
            }
        }
        started.await()
        first.cancel()
        closing.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withInference("tts") { secondStarted = true }
        }
        assertFalse(secondStarted)
        release.complete(Unit)
        first.join(); second.join()
        assertTrue(secondStarted)
    }

    @Test fun `interactive work overtakes waiting batch and cancelled waiter does not consume lease`() = runBlocking {
        val coordinator = InferenceCoordinator { error("broken diagnostic sink") }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { coordinator.withInference("first") { started.complete(Unit); release.await() } }
        started.await()
        val batch = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withInference("batch", InferencePriority.BATCH) { order.add("batch") }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withInference("cancelled") { error("must not execute") }
        }
        cancelled.cancelAndJoin()
        val interactive = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withInference("mic") { order.add("mic") }
        }
        release.complete(Unit)
        joinAll(first, batch, interactive)
        assertEquals(listOf("mic", "batch"), order)
        assertFailsWith<IllegalArgumentException> {
            coordinator.withInference("bad") { throw IllegalArgumentException("failure") }
        }
        assertEquals(42, coordinator.withInference("recovered") { 42 })
    }
}
