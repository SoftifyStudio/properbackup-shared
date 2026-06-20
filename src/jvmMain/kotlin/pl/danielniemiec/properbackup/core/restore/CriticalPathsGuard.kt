package pl.danielniemiec.properbackup.core.restore

import java.nio.file.Path

/**
 * HR-8: Agent NEVER deletes critical system files.
 * Whitelist of paths that must never be modified or deleted during recovery.
 */
object CriticalPathsGuard {

    private val CRITICAL_PREFIXES = listOf(
        "/proc/",
        "/sys/",
        "/dev/",
        "/run/",
        "/boot/",
        "/etc/fstab",
        "/etc/shadow",
        "/etc/passwd",
        "/etc/group",
        "/etc/sudoers",
        "/etc/ssh/",
        "/etc/ssl/",
    )

    private val CRITICAL_EXACT = setOf(
        "/etc/hostname",
        "/etc/resolv.conf",
        "/etc/hosts",
        "/etc/machine-id",
    )

    private val CRITICAL_PATTERNS = listOf(
        Regex(".*\\.pid$"),
        Regex(".*\\.sock$"),
        Regex(".*\\.socket$"),
        Regex(".*\\.lock$"),
    )

    private val CONFIG_EXTENSIONS = setOf(
        ".yml", ".yaml", ".conf", ".cfg", ".ini", ".properties", ".toml", ".json",
    )

    fun isCritical(absolutePath: String): Boolean {
        val normalized = absolutePath.replace("\\", "/")
        if (CRITICAL_EXACT.contains(normalized)) return true
        if (CRITICAL_PREFIXES.any { normalized.startsWith(it) }) return true
        if (CRITICAL_PATTERNS.any { it.matches(normalized) }) return true
        if (isSymlink(Path.of(absolutePath))) return true
        return false
    }

    fun isConfigFile(absolutePath: String): Boolean {
        val lower = absolutePath.lowercase()
        return CONFIG_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun filterDeletable(paths: List<String>): Pair<List<String>, List<String>> {
        val deletable = mutableListOf<String>()
        val protected = mutableListOf<String>()
        for (p in paths) {
            if (isCritical(p)) protected.add(p) else deletable.add(p)
        }
        return deletable to protected
    }

    private fun isSymlink(path: Path): Boolean = try {
        java.nio.file.Files.isSymbolicLink(path)
    } catch (_: Exception) {
        false
    }
}
