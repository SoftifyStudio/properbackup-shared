package pl.danielniemiec.properbackup.agent.transport

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Heartbeat client — pings buffer every 60s so the buffer can track
 * last_heartbeat_at in the servers table.
 */
class HeartbeatClient(
    private val bufferUrl: String,
    private val serverId: String,
    private val jwtClient: JwtClient,
) : AutoCloseable {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "heartbeat").apply { isDaemon = true }
    }

    private var future: ScheduledFuture<*>? = null

    fun start(intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS) {
        future?.cancel(false)
        future = scheduler.scheduleAtFixedRate(
            { sendHeartbeat() },
            0,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    fun sendHeartbeat() {
        try {
            val token = jwtClient.currentToken()
            val body = gson.toJson(mapOf("serverId" to serverId))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${bufferUrl.trimEnd('/')}/agent/heartbeat"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: SubscriptionExpiredException) {
            System.err.println("[heartbeat] Subscription expired — stopping heartbeat")
            future?.cancel(false)
        } catch (e: Exception) {
            System.err.println("[heartbeat] Failed: ${e.message}")
        }
    }

    override fun close() {
        future?.cancel(false)
        scheduler.shutdownNow()
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 60L
    }
}
