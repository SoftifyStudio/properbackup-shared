package pl.danielniemiec.properbackup.shared.transport

import pl.danielniemiec.properbackup.shared.host.PlatformClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit breaker for buffer transport layer.
 *
 * States:
 * - CLOSED: normal operation, requests flow through
 * - OPEN: after [failureThreshold] consecutive failures, all requests fail immediately
 *         for [openDurationMs] (default 60s)
 * - HALF_OPEN: after timeout, one probe request is allowed through.
 *             Success → CLOSED; Failure → OPEN (reset timer)
 *
 * Per spec [AGT-B2]: 3-strikes rule, 60s open window.
 */
class CircuitBreaker(
    private val clock: PlatformClock,
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)

    fun currentState(): State {
        val s = state.get()
        if (s == State.OPEN && clock.monotonicMillis() - openedAt.get() >= openDurationMs) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN)
            return State.HALF_OPEN
        }
        return s
    }

    /**
     * Execute an action through the circuit breaker.
     * @throws CircuitOpenException if the circuit is OPEN
     */
    fun <T> execute(action: () -> T): T {
        val current = currentState()
        if (current == State.OPEN) {
            throw CircuitOpenException(
                "Circuit breaker OPEN — ${consecutiveFailures.get()} consecutive failures. " +
                    "Will retry in ${remainingOpenMs()}ms"
            )
        }

        return try {
            val result = action()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    fun onSuccess() {
        consecutiveFailures.set(0)
        state.set(State.CLOSED)
    }

    fun onFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= failureThreshold) {
            state.set(State.OPEN)
            openedAt.set(clock.monotonicMillis())
        }
    }

    fun reset() {
        state.set(State.CLOSED)
        consecutiveFailures.set(0)
    }

    private fun remainingOpenMs(): Long {
        val elapsed = clock.monotonicMillis() - openedAt.get()
        return (openDurationMs - elapsed).coerceAtLeast(0)
    }
}

class CircuitOpenException(message: String) : RuntimeException(message)
