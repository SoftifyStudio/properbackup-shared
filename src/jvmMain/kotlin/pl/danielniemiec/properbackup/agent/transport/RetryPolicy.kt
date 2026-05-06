package pl.danielniemiec.properbackup.agent.transport

class RetryPolicy(private val maxAttempts: Int, private val initialDelayMs: Long) {

    fun interface RetryableAction {
        @Throws(Exception::class)
        fun run()
    }

    companion object {
        @JvmStatic
        fun defaultPolicy(): RetryPolicy = RetryPolicy(5, 2_000L)
    }

    @Throws(Exception::class)
    fun execute(action: RetryableAction) {
        var delay = initialDelayMs
        var last: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                action.run()
                return
            } catch (e: Exception) {
                last = e
                if (attempt == maxAttempts) break
                System.err.printf(
                    "WARN: attempt %d/%d failed (%s), retrying in %dms%n",
                    attempt,
                    maxAttempts,
                    e.message,
                    delay,
                )
                Thread.sleep(delay)
                delay = minOf(delay * 2, 60_000)
            }
        }
        throw last!!
    }
}
