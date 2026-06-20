package pl.danielniemiec.properbackup.shared.transport

import pl.danielniemiec.properbackup.shared.host.LogLevel
import pl.danielniemiec.properbackup.shared.host.Cancellable
import pl.danielniemiec.properbackup.shared.host.HostAdapter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * JWT client for agent authentication with the buffer.
 *
 * Per spec [AGT-A2]:
 * - Token lifetime: 5 minutes
 * - Auto-refresh: 60s before expiry (at ~4 min mark)
 * - Token stored ONLY in memory (never persisted to disk)
 * - On 403 SUBSCRIPTION_EXPIRED: stops agent, alerts owner
 */
class JwtClient(
    private val host: HostAdapter,
    private val bufferUrl: String,
    private val refreshToken: String,
    private val machineId: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val currentToken = AtomicReference<TokenState?>(null)
    private var refreshTask: Cancellable? = null

    data class TokenState(
        val accessToken: String,
        val expiresAtMillis: Long,
    )

    /**
     * Initialize: perform first token refresh, then schedule recurring refresh.
     * @throws AuthenticationException if initial refresh fails
     */
    fun initialize() {
        val token = doRefresh()
        currentToken.set(token)

        // Schedule refresh 60s before expiry
        val refreshIntervalMs = TOKEN_LIFETIME_MS - REFRESH_MARGIN_MS
        refreshTask = host.scheduleRepeating(refreshIntervalMs) {
            try {
                val newToken = doRefresh()
                currentToken.set(newToken)
                host.log(LogLevel.DEBUG, "JWT refreshed, expires in ${TOKEN_LIFETIME_MS / 1000}s")
            } catch (e: SubscriptionExpiredException) {
                host.log(LogLevel.ERROR, "Subscription expired — stopping agent", e)
                currentToken.set(null)
                refreshTask?.cancel()
            } catch (e: Exception) {
                host.log(LogLevel.WARN, "JWT refresh failed, will retry: ${e.message}")
            }
        }
    }

    fun shutdown() {
        refreshTask?.cancel()
        currentToken.set(null)
    }

    /**
     * Get the current valid access token.
     * @throws AuthenticationException if no valid token available
     */
    fun accessToken(): String {
        val token = currentToken.get()
            ?: throw AuthenticationException("No JWT token available — not initialized or subscription expired")

        if (host.clock.nowMillis() >= token.expiresAtMillis) {
            // Token expired, try immediate refresh
            val refreshed = doRefresh()
            currentToken.set(refreshed)
            return refreshed.accessToken
        }

        return token.accessToken
    }

    private fun doRefresh(): TokenState {
        val body = """{"refreshToken":"$refreshToken","machineId":"$machineId"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl/agents/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        return when (response.statusCode()) {
            200 -> parseTokenResponse(response.body())
            403 -> throw SubscriptionExpiredException("Buffer returned 403: ${response.body()}")
            401 -> throw AuthenticationException("Refresh token invalid: ${response.body()}")
            else -> throw TransportException("JWT refresh failed: HTTP ${response.statusCode()}")
        }
    }

    private fun parseTokenResponse(body: String): TokenState {
        // Simple JSON parsing without external deps — token is a single field
        val tokenMatch = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(body)
            ?: throw TransportException("Invalid token response: missing accessToken field")
        val expiresMatch = Regex(""""expiresAt"\s*:\s*(\d+)""").find(body)

        val accessToken = tokenMatch.groupValues[1]
        val expiresAt = expiresMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: (host.clock.nowMillis() + TOKEN_LIFETIME_MS)

        return TokenState(accessToken, expiresAt)
    }

    companion object {
        const val TOKEN_LIFETIME_MS = 5 * 60 * 1000L  // 5 minutes
        const val REFRESH_MARGIN_MS = 60 * 1000L      // refresh 60s before expiry
    }
}

class AuthenticationException(message: String) : RuntimeException(message)
class SubscriptionExpiredException(message: String) : RuntimeException(message)
class TransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
