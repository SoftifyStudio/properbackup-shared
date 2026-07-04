package pl.danielniemiec.properbackup.agent.transport

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatClientTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val heartbeatCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port

        server.createContext("/agent/heartbeat") { exchange ->
            heartbeatCount.incrementAndGet()
            val serverId = exchange.requestHeaders.getFirst("X-Server-Id")
            assertTrue(serverId?.isNotBlank() == true, "X-Server-Id header required")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `start sends immediate heartbeat`() {
        val client = HeartbeatClient(
            "http://localhost:$port",
            "server-1",
        ) { "test-token" }
        try {
            client.start()
            Thread.sleep(500)
            assertTrue(heartbeatCount.get() >= 1, "Should have sent at least one heartbeat")
            assertEquals(0, client.consecutiveFailures)
        } finally {
            client.close()
        }
    }

    @Test
    fun `failures increment counter`() {
        server.removeContext("/agent/heartbeat")
        server.createContext("/agent/heartbeat") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }

        val client = HeartbeatClient(
            "http://localhost:$port",
            "server-1",
        ) { "test-token" }
        try {
            client.start()
            Thread.sleep(500)
            assertTrue(client.consecutiveFailures > 0, "Should have recorded failures")
        } finally {
            client.close()
        }
    }
}
