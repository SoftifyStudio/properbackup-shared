package pl.danielniemiec.properbackup.agent.throttle

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * I/O throttle limiting disk read throughput and CPU utilisation.
 *
 * [readRateMbps] — max MB/s of disk I/O.  0 = unlimited.
 * [maxCpuPercent] — target CPU ceiling (1-100). After each read chunk
 *                    the thread sleeps proportionally to keep overall
 *                    utilisation near the target.  0 = no throttle.
 */
class IoThrottle(
    private val readRateMbps: Int = 50,
    private val maxCpuPercent: Int = 25,
) {
    private val bytesPerSecond: Long = if (readRateMbps > 0) readRateMbps.toLong() * 1024 * 1024 else 0

    /**
     * Read all bytes from [path] while respecting the I/O and CPU limits.
     */
    @Throws(IOException::class)
    fun readAllBytes(path: Path): ByteArray {
        if (bytesPerSecond <= 0 && maxCpuPercent <= 0) {
            return Files.readAllBytes(path)
        }
        val size = Files.size(path)
        if (size <= CHUNK_SIZE && maxCpuPercent <= 0) {
            return Files.readAllBytes(path)
        }
        Files.newInputStream(path).use { inp ->
            return readThrottled(inp, size.toInt())
        }
    }

    private fun readThrottled(input: InputStream, sizeHint: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream(sizeHint)
        val chunk = ByteArray(CHUNK_SIZE)
        var totalRead = 0L
        val startNanos = System.nanoTime()

        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            totalRead += n

            if (bytesPerSecond > 0) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val expectedMs = (totalRead * 1000L) / bytesPerSecond
                val sleepMs = expectedMs - elapsedMs
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            if (maxCpuPercent in 1..99) {
                val workMs = 1L
                val sleepMs = workMs * (100 - maxCpuPercent) / maxCpuPercent
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        return buf.toByteArray()
    }

    companion object {
        private const val CHUNK_SIZE = 256 * 1024

        @JvmStatic
        fun unlimited(): IoThrottle = IoThrottle(0, 0)
    }
}
