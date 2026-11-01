package dev.kiora.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class InferencePriority { INTERACTIVE, BATCH }
enum class InferencePhase { WAITING, STARTED, FINISHED, CANCELLED, FAILED }

data class InferenceEvent(
    val operation: String,
    val phase: InferencePhase,
    val waitMillis: Long,
    val executionMillis: Long,
)

/**
 * One native inference operation at a time. Hosts share an instance across optional engines.
 * The block must include native cleanup: the next operation starts only after it returns.
 * Interactive waiters precede queued batch work; active work is never forcibly interrupted.
 * This bounds concurrency, not resident bytes. Do not nest leases on the same coordinator.
 */
class InferenceCoordinator(private val onEvent: (InferenceEvent) -> Unit = {}) {
    private class Waiter(val operation: String, val priority: InferencePriority,
                         val queuedAt: Long = System.nanoTime()) {
        val ready = CompletableDeferred<Unit>()
    }
    private val lock = Mutex()
    private val waiting = mutableListOf<Waiter>()
    private var active: Waiter? = null

    suspend fun <T> withInference(operation: String,
        priority: InferencePriority = InferencePriority.INTERACTIVE,
        block: suspend () -> T): T {
        require(operation.isNotBlank())
        val waiter = Waiter(operation, priority)
        emit(waiter, InferencePhase.WAITING, 0, 0)
        lock.withLock {
            if (active == null) { active = waiter; waiter.ready.complete(Unit) }
            else waiting.add(waiter)
        }
        var beganAt: Long? = null
        var phase = InferencePhase.CANCELLED
        try {
            waiter.ready.await()
            currentCoroutineContext().ensureActive()
            beganAt = System.nanoTime()
            emit(waiter, InferencePhase.STARTED, millis(beganAt - waiter.queuedAt), 0)
            return block().also { phase = InferencePhase.FINISHED }
        } catch (error: Throwable) {
            phase = if (error is kotlinx.coroutines.CancellationException) InferencePhase.CANCELLED
                else InferencePhase.FAILED
            throw error
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (active === waiter) {
                        val next = waiting.firstOrNull { it.priority == InferencePriority.INTERACTIVE }
                            ?: waiting.firstOrNull()
                        if (next != null) waiting.remove(next)
                        active = next
                        next?.ready?.complete(Unit)
                    } else waiting.remove(waiter)
                }
            }
            val end = System.nanoTime()
            emit(waiter, phase, millis((beganAt ?: end) - waiter.queuedAt),
                beganAt?.let { millis(end - it) } ?: 0)
        }
    }

    private fun millis(nanos: Long) = nanos / 1_000_000
    private fun emit(waiter: Waiter, phase: InferencePhase, wait: Long, execution: Long) {
        // Diagnostic sinks must never compromise cleanup or prevent the next lease.
        runCatching { onEvent(InferenceEvent(waiter.operation, phase, wait, execution)) }
    }
}
