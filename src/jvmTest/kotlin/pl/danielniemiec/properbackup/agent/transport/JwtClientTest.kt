package pl.danielniemiec.properbackup.agent.transport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtClientTest {

    @Test
    fun `refresh interval is 4 minutes`() {
        assertEquals(240L, JwtClient.REFRESH_INTERVAL_SECONDS)
    }

    @Test
    fun `currentToken throws when buffer unreachable`() {
        val client = JwtClient(
            bufferUrl = "http://127.0.0.1:1",
            serverId = "test-server",
            refreshToken = "test-refresh",
            machineId = "test-machine",
        )
        assertThrows<Exception> { client.currentToken() }
        client.close()
    }

    @Test
    fun `SubscriptionExpiredException has correct message`() {
        val ex = SubscriptionExpiredException("test msg")
        assertEquals("test msg", ex.message)
        assertTrue(ex is RuntimeException)
    }

    @Test
    fun `AuthenticationException has correct message`() {
        val ex = AuthenticationException("auth fail")
        assertEquals("auth fail", ex.message)
        assertTrue(ex is RuntimeException)
    }
}
