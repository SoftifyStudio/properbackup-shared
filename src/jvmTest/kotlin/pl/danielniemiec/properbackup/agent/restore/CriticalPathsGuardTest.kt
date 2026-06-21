package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CriticalPathsGuardTest {

    @TempDir
    lateinit var tempDir: Path

    private fun guard(backupRoots: List<String> = emptyList()): CriticalPathsGuard {
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)
        return CriticalPathsGuard(configDir, stateDir, backupRoots)
    }

    @Test
    fun `protects proc filesystem`() {
        assertTrue(guard().isProtected("/proc/cpuinfo"))
        assertTrue(guard().isProtected("/proc/self/status"))
    }

    @Test
    fun `protects sys filesystem`() {
        assertTrue(guard().isProtected("/sys/class/net"))
        assertTrue(guard().isProtected("/sys/devices/system/cpu"))
    }

    @Test
    fun `protects dev filesystem`() {
        assertTrue(guard().isProtected("/dev/sda"))
        assertTrue(guard().isProtected("/dev/null"))
    }

    @Test
    fun `protects run directory`() {
        assertTrue(guard().isProtected("/run/user/1000"))
    }

    @Test
    fun `protects boot directory`() {
        assertTrue(guard().isProtected("/boot/vmlinuz"))
    }

    @Test
    fun `protects systemd config`() {
        assertTrue(guard().isProtected("/etc/systemd/system/foo.service"))
    }

    @Test
    fun `protects passwd and shadow`() {
        assertTrue(guard().isProtected("/etc/passwd"))
        assertTrue(guard().isProtected("/etc/shadow"))
    }

    @Test
    fun `protects windows system32 case insensitive`() {
        assertTrue(guard().isProtected("C:\\Windows\\System32\\drivers\\etc\\hosts"))
        assertTrue(guard().isProtected("c:\\windows\\system32\\foo.dll"))
    }

    @Test
    fun `protects macos system`() {
        assertTrue(guard().isProtected("/System/Library/Frameworks"))
        assertTrue(guard().isProtected("/private/var/db/some.db"))
    }

    @Test
    fun `protects config directory`() {
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)
        val g = CriticalPathsGuard(configDir, stateDir)
        assertTrue(g.isProtected(configDir.resolve("global.yml").toString()))
    }

    @Test
    fun `protects state directory`() {
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)
        val g = CriticalPathsGuard(configDir, stateDir)
        assertTrue(g.isProtected(stateDir.resolve("recovery_state.json").toString()))
    }

    @Test
    fun `allows normal user files`() {
        val g = guard()
        assertFalse(g.isProtected(tempDir.resolve("userdata/file.txt").toString()))
    }

    @Test
    fun `allows home directory files`() {
        val g = guard()
        assertFalse(g.isProtected("/home/user/documents/report.pdf"))
    }

    @Test
    fun `protects symlink outside backup roots`() {
        val link = tempDir.resolve("link")
        val target = tempDir.resolve("outside/target.txt")
        Files.createDirectories(target.parent)
        Files.writeString(target, "data")
        Files.createSymbolicLink(link, target)

        val backupRoot = tempDir.resolve("backups").toString()
        val g = guard(backupRoots = listOf(backupRoot))
        assertTrue(g.isProtected(link.toString()))
    }

    @Test
    fun `allows symlink inside backup roots`() {
        val backupDir = tempDir.resolve("backups")
        Files.createDirectories(backupDir)
        val target = backupDir.resolve("target.txt")
        Files.writeString(target, "data")
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, target)

        val g = guard(backupRoots = listOf(backupDir.toAbsolutePath().normalize().toString()))
        assertFalse(g.isProtected(link.toString()))
    }

    // Adversarial path traversal tests
    @Test
    fun `blocks path traversal to etc passwd`() {
        assertTrue(guard().isProtected("/etc/passwd"))
    }

    @Test
    fun `blocks path traversal with dot-dot`() {
        // Path.normalize() resolves ".." so /home/user/../../etc/passwd -> /etc/passwd
        assertTrue(guard().isProtected("/home/user/../../etc/passwd"))
    }

    @Test
    fun `blocks traversal to proc via normalized path`() {
        assertTrue(guard().isProtected("/tmp/../proc/cpuinfo"))
    }
}
