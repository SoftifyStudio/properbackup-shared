package pl.danielniemiec.properbackup.agent.restore

import java.nio.file.Files
import java.nio.file.Path

class CriticalPathsGuard(
    private val configDir: Path,
    private val stateDir: Path,
    private val backupRoots: List<String> = emptyList(),
) {
    private val posixCritical = listOf(
        "/proc/", "/sys/", "/dev/", "/run/",
        "/boot/", "/etc/systemd/", "/etc/passwd", "/etc/shadow",
    )
    private val windowsCritical = listOf(
        "C:\\Windows\\System32\\", "C:\\Windows\\WinSxS\\",
    )
    private val macosCritical = listOf(
        "/System/", "/private/var/db/",
    )

    fun isProtected(path: String): Boolean {
        val normalized = Path.of(path).toAbsolutePath().normalize().toString()

        if (posixCritical.any { normalized.startsWith(it) }) return true
        // Check both normalized and raw path for Windows (cross-platform compat)
        if (windowsCritical.any { normalized.startsWith(it, ignoreCase = true) || path.startsWith(it, ignoreCase = true) }) return true
        if (macosCritical.any { normalized.startsWith(it) }) return true

        val cfgAbs = configDir.toAbsolutePath().normalize().toString()
        if (normalized.startsWith(cfgAbs)) return true

        val stateAbs = stateDir.toAbsolutePath().normalize().toString()
        if (normalized.startsWith(stateAbs)) return true

        if (isSymlinkOutsideRoots(path)) return true

        return false
    }

    private fun isSymlinkOutsideRoots(path: String): Boolean {
        val p = Path.of(path)
        if (!Files.isSymbolicLink(p)) return false
        return try {
            val target = Files.readSymbolicLink(p).toAbsolutePath().normalize().toString()
            backupRoots.none { target.startsWith(it) }
        } catch (_: Exception) {
            true
        }
    }
}
