package pl.danielniemiec.properbackup.agent.transport

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtClientTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val refreshCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port

        server.createContext("/agents/refresh") { exchange ->
            refreshCount.incrementAndGet()
            val body = exchange.requestBody.bufferedReader().readText()
            assertTrue(body.contains("refreshToken"), "Request should contain refreshToken")
            assertTrue(body.contains("machineId"), "Request should contain machineId")

            val response = """{"accessToken":"test-jwt-token-${refreshCount.get()}","expiresAt":"2099-01-01T00:00:00Z"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `bootstrap exchanges refresh token for JWT`() {
        val client = JwtClient("http://localhost:$port", "test-refresh-token", "machine-1")
        try {
            val ok = client.bootstrap()
            assertTrue(ok, "bootstrap should succeed")
            val token = client.currentToken()
            assertNotNull(token)
            assertTrue(token.startsWith("test-jwt-token-"), "Should have received JWT")
            assertEquals(1, refreshCount.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `bootstrap fails on 403`() {
        server.removeContext("/agents/refresh")
        server.createContext("/agents/refresh") { exchange ->
            exchange.sendResponseHeaders(403, 0)
            exchange.responseBody.close()
        }

        val client = JwtClient("http://localhost:$port", "bad-token", "machine-1")
        try {
            val ok = client.bootstrap()
            assertTrue(!ok, "bootstrap should fail on 403")
            assertNotNull(client.lastError)
        } finally {
            client.close()
        }
    }
}
