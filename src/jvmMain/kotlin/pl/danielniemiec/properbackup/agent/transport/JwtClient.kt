package pl.danielniemiec.properbackup.agent.transport

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JWT 5min lifetime, auto-refresh every 4min (HR-6).
 * Bootstrap: exchange activation refreshToken -> short-lived JWT.
 * All buffer requests use currentToken().
 */
class JwtClient(
    private val bufferUrl: String,
    private val refreshToken: String,
    private val machineId: String,
) : AutoCloseable {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()
    private val accessToken = AtomicReference<String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jwt-refresh").also { it.isDaemon = true }
    }
    private var refreshFuture: ScheduledFuture<*>? = null

    @Volatile
    var lastError: String? = null
        private set

    fun bootstrap(): Boolean {
        return try {
            doRefresh()
            refreshFuture = scheduler.scheduleAtFixedRate(
                { runCatching { doRefresh() }.onFailure { lastError = it.message } },
                REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    fun currentToken(): String =
        accessToken.get() ?: throw IllegalStateException("JWT not bootstrapped — call bootstrap() first")

    private fun doRefresh() {
        val body = gson.toJson(
            mapOf("refreshToken" to refreshToken, "machineId" to machineId),
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl/agents/refresh"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        when (res.statusCode()) {
            200 -> {
                val json = JsonParser.parseString(res.body()).asJsonObject
                val token = json.get("accessToken").asString
                accessToken.set(token)
                lastError = null
            }
            403 -> throw IOException("Access denied (403): ${res.body()}")
            401 -> throw IOException("Unauthorized (401): ${res.body()}")
            else -> throw IOException("Refresh failed (${res.statusCode()}): ${res.body()}")
        }
    }

    override fun close() {
        refreshFuture?.cancel(false)
        scheduler.shutdownNow()
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 4L * 60 * 1000
    }
}
