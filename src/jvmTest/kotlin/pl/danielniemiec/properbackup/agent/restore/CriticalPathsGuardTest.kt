package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CriticalPathsGuardTest {

    private val guard = CriticalPathsGuard()

    @Test
    fun `blocks agent config files`() {
        assertTrue(guard.isCritical("global.yml"))
        assertTrue(guard.isCritical("/home/user/global.yml"))
        assertTrue(guard.isCritical("agent.yml"))
    }

    @Test
    fun `blocks agent jar`() {
        assertTrue(guard.isCritical("properbackup-agent.jar"))
        assertTrue(guard.isCritical("/opt/properbackup-agent.jar"))
    }

    @Test
    fun `blocks systemd service file`() {
        assertTrue(guard.isCritical("/etc/systemd/system/properbackup-agent.service"))
    }

    @Test
    fun `allows normal user files`() {
        assertFalse(guard.isCritical("/home/user/documents/report.txt"))
        assertFalse(guard.isCritical("/var/www/index.html"))
        assertFalse(guard.isCritical("/srv/minecraft/world/level.dat"))
    }

    @Test
    fun `allows custom protected paths`() {
        val customGuard = CriticalPathsGuard(extraProtected = listOf("custom-config.yml", "/opt/critical"))
        assertTrue(customGuard.isCritical("custom-config.yml"))
        assertTrue(customGuard.isCritical("/opt/critical"))
    }

    @Test
    fun `filterDeletions separates allowed and blocked`() {
        val paths = listOf(
            "/home/user/file.txt",
            "global.yml",
            "/var/www/index.html",
            "agent.yml",
        )
        val (allowed, blocked) = guard.filterDeletions(paths)
        assertEquals(2, allowed.size)
        assertEquals(2, blocked.size)
        assertTrue(allowed.contains("/home/user/file.txt"))
        assertTrue(allowed.contains("/var/www/index.html"))
        assertTrue(blocked.contains("global.yml"))
        assertTrue(blocked.contains("agent.yml"))
    }
}
