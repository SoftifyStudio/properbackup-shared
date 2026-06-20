package pl.danielniemiec.properbackup.core.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CriticalPathsGuardTest {

    @Test
    fun `proc paths are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/proc/1/status"))
        assertTrue(CriticalPathsGuard.isCritical("/proc/cpuinfo"))
    }

    @Test
    fun `sys paths are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/sys/class/net/eth0"))
    }

    @Test
    fun `dev paths are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/dev/null"))
        assertTrue(CriticalPathsGuard.isCritical("/dev/sda1"))
    }

    @Test
    fun `run paths are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/run/lock"))
    }

    @Test
    fun `boot paths are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/boot/vmlinuz"))
    }

    @Test
    fun `ssh config is critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/etc/ssh/sshd_config"))
    }

    @Test
    fun `exact critical paths are matched`() {
        assertTrue(CriticalPathsGuard.isCritical("/etc/hostname"))
        assertTrue(CriticalPathsGuard.isCritical("/etc/resolv.conf"))
        assertTrue(CriticalPathsGuard.isCritical("/etc/hosts"))
        assertTrue(CriticalPathsGuard.isCritical("/etc/machine-id"))
    }

    @Test
    fun `pid and lock files are critical`() {
        assertTrue(CriticalPathsGuard.isCritical("/var/run/nginx.pid"))
        assertTrue(CriticalPathsGuard.isCritical("/tmp/app.lock"))
        assertTrue(CriticalPathsGuard.isCritical("/var/run/docker.sock"))
    }

    @Test
    fun `normal data files are not critical`() {
        assertFalse(CriticalPathsGuard.isCritical("/home/user/data/file.txt"))
        assertFalse(CriticalPathsGuard.isCritical("/var/www/index.html"))
        assertFalse(CriticalPathsGuard.isCritical("/opt/app/data.db"))
    }

    @Test
    fun `config file detection works`() {
        assertTrue(CriticalPathsGuard.isConfigFile("/etc/nginx/nginx.conf"))
        assertTrue(CriticalPathsGuard.isConfigFile("/app/config.yml"))
        assertTrue(CriticalPathsGuard.isConfigFile("/app/settings.json"))
        assertFalse(CriticalPathsGuard.isConfigFile("/app/data.txt"))
    }

    @Test
    fun `filterDeletable separates protected from deletable`() {
        val paths = listOf("/proc/1/status", "/home/user/file.txt", "/dev/null", "/var/www/index.html")
        val (deletable, protected) = CriticalPathsGuard.filterDeletable(paths)
        assertEquals(2, deletable.size)
        assertEquals(2, protected.size)
        assertTrue(deletable.contains("/home/user/file.txt"))
        assertTrue(deletable.contains("/var/www/index.html"))
        assertTrue(protected.contains("/proc/1/status"))
        assertTrue(protected.contains("/dev/null"))
    }
}
