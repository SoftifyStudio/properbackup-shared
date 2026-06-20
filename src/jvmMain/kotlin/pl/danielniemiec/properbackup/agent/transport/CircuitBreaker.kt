package pl.danielniemiec.properbackup.agent.transport

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit Breaker for buffer uploads (HR-8 from agent-vps-master-spec).
 *
 * States:
 * - CLOSED: normal operation, requests go through
 * - OPEN: 3 consecutive 5xx -> block all requests for 60s
 * - HALF_OPEN: after 60s, allow 1 probe request; success -> CLOSED, fail -> OPEN
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000L,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)
    private val halfOpenInProgress = AtomicReference(false)

    fun currentState(): State {
        val s = state.get()
        if (s == State.OPEN && System.currentTimeMillis() - openedAt.get() >= openDurationMs) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN)
            return State.HALF_OPEN
        }
        return s
    }

    /**
     * Execute an action through the circuit breaker.
     * @throws CircuitOpenException if circuit is OPEN
     */
    @Throws(Exception::class)
    fun <T> execute(action: () -> T): T {
        val current = currentState()

        if (current == State.OPEN) {
            throw CircuitOpenException(
                "Circuit breaker is OPEN — ${consecutiveFailures.get()} consecutive failures, " +
                    "will probe in ${remainingOpenMs()}ms"
            )
        }

        if (current == State.HALF_OPEN) {
            if (!halfOpenInProgress.compareAndSet(false, true)) {
                throw CircuitOpenException("Circuit breaker is HALF_OPEN — probe in progress")
            }
        }

        return try {
            val result = action()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        } finally {
            if (current == State.HALF_OPEN) {
                halfOpenInProgress.set(false)
            }
        }
    }

    private fun onSuccess() {
        consecutiveFailures.set(0)
        state.set(State.CLOSED)
    }

    private fun onFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= failureThreshold) {
            state.set(State.OPEN)
            openedAt.set(System.currentTimeMillis())
            System.err.println("[CircuitBreaker] OPEN after $failures consecutive failures (cooldown=${openDurationMs}ms)")
        }
    }

    fun reset() {
        state.set(State.CLOSED)
        consecutiveFailures.set(0)
        openedAt.set(0)
        halfOpenInProgress.set(false)
    }

    fun failureCount(): Int = consecutiveFailures.get()

    private fun remainingOpenMs(): Long {
        val elapsed = System.currentTimeMillis() - openedAt.get()
        return maxOf(0, openDurationMs - elapsed)
    }
}

class CircuitOpenException(message: String) : RuntimeException(message)
