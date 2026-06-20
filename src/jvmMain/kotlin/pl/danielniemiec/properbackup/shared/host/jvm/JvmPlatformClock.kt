package pl.danielniemiec.properbackup.shared.host.jvm

import pl.danielniemiec.properbackup.shared.host.PlatformClock
import java.time.Instant

/**
 * Standard JVM clock implementation — delegates to System.currentTimeMillis() and Instant.now().
 */
class JvmPlatformClock : PlatformClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun nowUtc(): Instant = Instant.now()
    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
}
