package pl.danielniemiec.properbackup.agent.transport

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

class CircuitBreakerOpenException(message: String) : IOException(message)

class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val openDurationMs: Long = DEFAULT_OPEN_DURATION_MS,
) {
    private val state = AtomicReference(CircuitState.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)

    fun currentState(): CircuitState {
        val s = state.get()
        if (s == CircuitState.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= openDurationMs) {
                state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)
                return CircuitState.HALF_OPEN
            }
        }
        return state.get()
    }

    @Throws(Exception::class)
    fun <T> execute(action: () -> T): T {
        val current = currentState()
        if (current == CircuitState.OPEN) {
            val remainingMs = openDurationMs - (System.currentTimeMillis() - openedAt.get())
            throw CircuitBreakerOpenException(
                "Circuit breaker OPEN — retry after ${remainingMs}ms"
            )
        }

        return try {
            val result = action()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure(e)
            throw e
        }
    }

    private fun onSuccess() {
        consecutiveFailures.set(0)
        state.set(CircuitState.CLOSED)
    }

    private fun onFailure(e: Exception) {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= failureThreshold) {
            state.set(CircuitState.OPEN)
            openedAt.set(System.currentTimeMillis())
            System.err.println(
                "[CircuitBreaker] OPEN after $count consecutive failures: ${e.message}"
            )
        }
    }

    fun reset() {
        state.set(CircuitState.CLOSED)
        consecutiveFailures.set(0)
        openedAt.set(0)
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_OPEN_DURATION_MS = 60_000L
    }
}
