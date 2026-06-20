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
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * JWT client implementing HR-6: 5-min lifetime, auto-refresh every 4 min.
 * Activation token → exchange for short-lived JWT → auto-refresh loop.
 * Token is NEVER persisted to disk or logged.
 */
class JwtClient(
    private val bufferUrl: String,
    private val serverId: String,
    private val refreshToken: String,
    private val machineId: String,
) : AutoCloseable {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val gson = Gson()
    private val lock = ReentrantReadWriteLock()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jwt-refresh").apply { isDaemon = true }
    }

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var expiresAtMillis: Long = 0

    private var refreshFuture: ScheduledFuture<*>? = null

    fun currentToken(): String {
        lock.readLock().lock()
        try {
            val token = accessToken
            if (token != null && System.currentTimeMillis() < expiresAtMillis) {
                return token
            }
        } finally {
            lock.readLock().unlock()
        }
        return refresh()
    }

    fun bootstrap(): String {
        val token = refresh()
        scheduleAutoRefresh()
        return token
    }

    fun refresh(): String {
        lock.writeLock().lock()
        try {
            val body = gson.toJson(
                mapOf(
                    "serverId" to serverId,
                    "refreshToken" to refreshToken,
                    "machineId" to machineId,
                ),
            )
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${bufferUrl.trimEnd('/')}/agents/refresh"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            val status = res.statusCode()

            if (status == 403) {
                val resBody = res.body() ?: ""
                if (resBody.contains("SUBSCRIPTION_EXPIRED")) {
                    throw SubscriptionExpiredException("Subscription expired — agent must stop")
                }
                throw AuthenticationException("Refresh rejected (403): $resBody")
            }
            if (status == 401) {
                throw AuthenticationException("Refresh token invalid (401)")
            }
            if (status < 200 || status >= 300) {
                throw IOException("JWT refresh failed ($status): ${res.body()}")
            }

            val json = JsonParser.parseString(res.body()).asJsonObject
            val token = json.get("accessToken").asString
            val expiresIn = if (json.has("expiresIn")) json.get("expiresIn").asLong else 300L

            accessToken = token
            expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000)
            return token
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun scheduleAutoRefresh() {
        refreshFuture?.cancel(false)
        refreshFuture = scheduler.scheduleAtFixedRate(
            {
                try {
                    refresh()
                } catch (e: SubscriptionExpiredException) {
                    System.err.println("[jwt] ${e.message}")
                    refreshFuture?.cancel(false)
                } catch (e: Exception) {
                    System.err.println("[jwt] Refresh failed: ${e.message}")
                }
            },
            REFRESH_INTERVAL_SECONDS,
            REFRESH_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    override fun close() {
        refreshFuture?.cancel(false)
        scheduler.shutdownNow()
    }

    companion object {
        const val REFRESH_INTERVAL_SECONDS = 240L // 4 minutes
    }
}

class SubscriptionExpiredException(message: String) : RuntimeException(message)
class AuthenticationException(message: String) : RuntimeException(message)
