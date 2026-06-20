package pl.danielniemiec.properbackup.shared.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for RetryPolicy (spec [AGT-B1]).
 */
class RetryPolicyTest {

    @Test
    fun `succeeds on first attempt without retries`() {
        val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 10)
        var attempts = 0

        val result = policy.execute { attempts++; "ok" }

        assertEquals("ok", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retries on failure and succeeds`() {
        val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 10)
        var attempts = 0

        val result = policy.execute {
            attempts++
            if (attempts < 3) throw RuntimeException("fail #$attempts")
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `throws after all attempts exhausted`() {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10)
        var attempts = 0

        val ex = assertThrows(TransportException::class.java) {
            policy.execute {
                attempts++
                throw RuntimeException("always fails")
            }
        }

        assertEquals(3, attempts)
        assertTrue(ex.message!!.contains("All 3 retry attempts failed"))
    }

    @Test
    fun `respects retryOn predicate — does not retry non-retryable exceptions`() {
        val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 10)
        var attempts = 0

        val ex = assertThrows(TransportException::class.java) {
            policy.execute(retryOn = { it is java.io.IOException }) {
                attempts++
                throw IllegalStateException("non-retryable")
            }
        }

        assertEquals(1, attempts) // no retries
    }

    @Test
    fun `backoff increases exponentially`() {
        val policy = RetryPolicy(maxAttempts = 4, initialDelayMs = 50, jitterPercent = 0.0)
        val startTime = System.currentTimeMillis()
        var attempts = 0

        assertThrows(TransportException::class.java) {
            policy.execute {
                attempts++
                throw RuntimeException("fail")
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        // Expected delays: 50 + 100 + 200 = 350ms (between attempts 1→2, 2→3, 3→4)
        assertTrue(elapsed >= 300, "Backoff too fast: ${elapsed}ms (expected >=300ms)")
        assertEquals(4, attempts)
    }

    @Test
    fun `jitter adds randomness to delay`() {
        val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 100, jitterPercent = 0.20)
        val timings = mutableListOf<Long>()

        repeat(10) {
            val start = System.currentTimeMillis()
            try {
                policy.execute { throw RuntimeException("fail") }
            } catch (_: TransportException) {
                timings.add(System.currentTimeMillis() - start)
            }
        }

        // With 20% jitter on 100ms, delays should be 80-120ms
        // At least some variation should exist
        val unique = timings.distinct().size
        assertTrue(unique >= 2, "Expected jitter variation, got $timings")
    }
}
