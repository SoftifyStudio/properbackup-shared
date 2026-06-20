package pl.danielniemiec.properbackup.agent.transport

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Heartbeat: agent pings buffer every 60s.
 * Buffer records last_heartbeat_at in servers table.
 */
class HeartbeatClient(
    private val bufferUrl: String,
    private val serverId: String,
    private val tokenProvider: () -> String,
) : AutoCloseable {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "heartbeat").also { it.isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    fun start() {
        sendHeartbeat()
        future = scheduler.scheduleAtFixedRate(
            { runCatching { sendHeartbeat() }.onFailure { consecutiveFailures++ } },
            INTERVAL_MS,
            INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun sendHeartbeat() {
        val token = tokenProvider()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl/agent/heartbeat"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("X-Server-Id", serverId)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.discarding())
        if (res.statusCode() in 200..299) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
        }
    }

    override fun close() {
        future?.cancel(false)
        scheduler.shutdownNow()
    }

    companion object {
        const val INTERVAL_MS = 60_000L
    }
}
