package pl.danielniemiec.properbackup.agent.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class CircuitBreakerTest {

    private lateinit var cb: CircuitBreaker

    @BeforeEach
    fun setUp() {
        cb = CircuitBreaker(failureThreshold = 3, openDurationMs = 200)
    }

    @Test
    fun `starts in CLOSED state`() {
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `successful execution keeps circuit CLOSED`() {
        val result = cb.execute { "ok" }
        assertEquals("ok", result)
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(0, cb.failureCount())
    }

    @Test
    fun `1 failure keeps circuit CLOSED`() {
        assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(1, cb.failureCount())
    }

    @Test
    fun `2 failures keeps circuit CLOSED`() {
        repeat(2) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(2, cb.failureCount())
    }

    @Test
    fun `3 consecutive failures opens circuit`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
        assertEquals(3, cb.failureCount())
    }

    @Test
    fun `OPEN circuit throws CircuitOpenException immediately`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        assertThrows<CircuitOpenException> { cb.execute { "should not run" } }
    }

    @Test
    fun `success resets failure counter`() {
        repeat(2) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        cb.execute { "ok" }
        assertEquals(0, cb.failureCount())
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `OPEN transitions to HALF_OPEN after cooldown`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

        Thread.sleep(250) // wait for cooldown (200ms)
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState())
    }

    @Test
    fun `HALF_OPEN success closes circuit`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        Thread.sleep(250)
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState())

        cb.execute { "probe ok" }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(0, cb.failureCount())
    }

    @Test
    fun `HALF_OPEN failure reopens circuit`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        Thread.sleep(250)
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState())

        assertThrows<IOException> { cb.execute { throw IOException("probe fail") } }
        // After 1 more failure, total consecutive = 4, >= threshold => OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
    }

    @Test
    fun `reset clears state`() {
        repeat(3) {
            assertThrows<IOException> { cb.execute { throw IOException("fail") } }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

        cb.reset()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(0, cb.failureCount())
    }

    @Test
    fun `non-consecutive failures do not trip circuit`() {
        assertThrows<IOException> { cb.execute { throw IOException("1") } }
        cb.execute { "ok" } // resets counter
        assertThrows<IOException> { cb.execute { throw IOException("2") } }
        cb.execute { "ok" }
        assertThrows<IOException> { cb.execute { throw IOException("3") } }
        // Only 1 consecutive failure
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        assertEquals(1, cb.failureCount())
    }
}
