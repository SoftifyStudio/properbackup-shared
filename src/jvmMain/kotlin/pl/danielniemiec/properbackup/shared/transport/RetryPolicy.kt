package pl.danielniemiec.properbackup.shared.transport

import java.util.concurrent.ThreadLocalRandom

/**
 * Retry policy with exponential backoff and jitter.
 *
 * Per spec [AGT-B1]:
 * - Attempts: 5
 * - Backoff: 1s, 2s, 4s, 8s, 16s (with ±20% jitter)
 * - Max total delay: ~31s
 */
class RetryPolicy(
    private val maxAttempts: Int = 5,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 16_000,
    private val jitterPercent: Double = 0.20,
) {
    /**
     * Execute an action with retries.
     * @param retryOn predicate to determine if the exception is retryable
     * @param action the action to execute
     * @throws TransportException if all attempts fail
     */
    fun <T> execute(
        retryOn: (Exception) -> Boolean = { true },
        action: () -> T,
    ): T {
        var lastException: Exception? = null
        var delay = initialDelayMs

        for (attempt in 1..maxAttempts) {
            try {
                return action()
            } catch (e: Exception) {
                lastException = e
                if (!retryOn(e) || attempt == maxAttempts) break

                val jitteredDelay = applyJitter(delay)
                Thread.sleep(jitteredDelay)
                delay = (delay * 2).coerceAtMost(maxDelayMs)
            }
        }

        throw TransportException(
            "All $maxAttempts retry attempts failed: ${lastException?.message}",
            lastException,
        )
    }

    private fun applyJitter(delayMs: Long): Long {
        val jitterRange = (delayMs * jitterPercent).toLong()
        val jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1)
        return (delayMs + jitter).coerceAtLeast(0)
    }
}
