package pl.danielniemiec.properbackup.shared.host

import java.time.Instant

/**
 * Time abstraction — hosts can override for testing or non-standard clock sources.
 */
interface PlatformClock {
    fun nowMillis(): Long
    fun nowUtc(): Instant
    fun monotonicMillis(): Long
}
