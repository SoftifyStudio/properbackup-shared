package pl.danielniemiec.properbackup.agent.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class CircuitBreakerTest {

    private lateinit var breaker: CircuitBreaker

    @BeforeEach
    fun setUp() {
        breaker = CircuitBreaker(failureThreshold = 3, openDurationMs = 200)
    }

    @Test
    fun `starts in CLOSED state`() {
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `stays CLOSED on success`() {
        val result = breaker.execute { "ok" }
        assertEquals("ok", result)
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `stays CLOSED after fewer failures than threshold`() {
        repeat(2) {
            assertThrows(IOException::class.java) {
                breaker.execute<Unit> { throw IOException("fail") }
            }
        }
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `opens after 3 consecutive failures`() {
        repeat(3) {
            assertThrows(IOException::class.java) {
                breaker.execute<Unit> { throw IOException("fail-$it") }
            }
        }
        assertEquals(CircuitState.OPEN, breaker.currentState())
    }

    @Test
    fun `OPEN state rejects calls with CircuitBreakerOpenException`() {
        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        assertThrows(CircuitBreakerOpenException::class.java) {
            breaker.execute { "should not run" }
        }
    }

    @Test
    fun `transitions to HALF_OPEN after open duration expires`() {
        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        assertEquals(CircuitState.OPEN, breaker.currentState())

        Thread.sleep(250)

        assertEquals(CircuitState.HALF_OPEN, breaker.currentState())
    }

    @Test
    fun `HALF_OPEN transitions to CLOSED on success`() {
        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        Thread.sleep(250)
        assertEquals(CircuitState.HALF_OPEN, breaker.currentState())

        val result = breaker.execute { "recovered" }
        assertEquals("recovered", result)
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `HALF_OPEN transitions back to OPEN on failure`() {
        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        Thread.sleep(250)
        assertEquals(CircuitState.HALF_OPEN, breaker.currentState())

        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail again") } }
        }
        assertEquals(CircuitState.OPEN, breaker.currentState())
    }

    @Test
    fun `success resets consecutive failure count`() {
        repeat(2) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        breaker.execute { "ok" }
        repeat(2) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `reset returns to CLOSED state`() {
        repeat(3) {
            runCatching { breaker.execute<Unit> { throw IOException("fail") } }
        }
        assertEquals(CircuitState.OPEN, breaker.currentState())
        breaker.reset()
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `exception from action is propagated`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            breaker.execute<Unit> { throw IllegalStateException("custom") }
        }
        assertEquals("custom", ex.message)
    }
}
