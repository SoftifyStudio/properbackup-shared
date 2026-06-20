package pl.danielniemiec.properbackup.agent.transport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HeartbeatClientTest {

    @Test
    fun `default heartbeat interval is 60 seconds`() {
        assertEquals(60L, HeartbeatClient.DEFAULT_INTERVAL_SECONDS)
    }
}
