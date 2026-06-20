package pl.danielniemiec.properbackup.shared.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.shared.host.PlatformClock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for CircuitBreaker (spec [AGT-B2]).
 */
class CircuitBreakerTest {

    private val clock = TestClock()

    @Test
    fun `starts in CLOSED state`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `transitions to OPEN after 3 consecutive failures`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        cb.onFailure()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        cb.onFailure()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        cb.onFailure()
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
    }

    @Test
    fun `OPEN state rejects requests immediately`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        repeat(3) { cb.onFailure() }

        assertThrows(CircuitOpenException::class.java) {
            cb.execute { "should not reach" }
        }
    }

    @Test
    fun `transitions to HALF_OPEN after timeout`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        repeat(3) { cb.onFailure() }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

        // Advance clock past open duration
        clock.advance(60_001)
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState())
    }

    @Test
    fun `HALF_OPEN success transitions to CLOSED`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        repeat(3) { cb.onFailure() }
        clock.advance(60_001)

        // In HALF_OPEN, one request is allowed
        val result = cb.execute { "probe-success" }
        assertEquals("probe-success", result)
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `HALF_OPEN failure transitions back to OPEN`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        repeat(3) { cb.onFailure() }
        clock.advance(60_001)
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState())

        // Probe fails
        assertThrows(RuntimeException::class.java) {
            cb.execute { throw RuntimeException("probe fail") }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
    }

    @Test
    fun `success resets failure counter`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        cb.onFailure()
        cb.onFailure()
        cb.onSuccess() // resets counter
        cb.onFailure()
        cb.onFailure()
        // Only 2 consecutive failures now, not 3
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `reset clears state`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        repeat(3) { cb.onFailure() }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

        cb.reset()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `execute wraps action and records success`() {
        val cb = CircuitBreaker(clock, failureThreshold = 3, openDurationMs = 60_000)

        cb.onFailure()
        cb.onFailure()

        val result = cb.execute { 42 }
        assertEquals(42, result)
        // Success should have reset the counter
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }
}

/**
 * Test clock that allows manual time advancement.
 */
class TestClock : PlatformClock {
    private val monotonic = AtomicLong(0)
    private val wallClock = AtomicLong(System.currentTimeMillis())

    fun advance(ms: Long) {
        monotonic.addAndGet(ms)
        wallClock.addAndGet(ms)
    }

    override fun nowMillis(): Long = wallClock.get()
    override fun nowUtc(): Instant = Instant.ofEpochMilli(wallClock.get())
    override fun monotonicMillis(): Long = monotonic.get()
}
