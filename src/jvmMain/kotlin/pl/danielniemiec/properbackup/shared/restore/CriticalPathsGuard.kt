package pl.danielniemiec.properbackup.shared.restore

import java.nio.file.Path

/**
 * HR-8: Critical Files Whitelist — NEVER delete these paths during recovery.
 * Per user-facing-recovery-spec.md §0 HR-8.
 */
object CriticalPathsGuard {

    private val UNIX_SYSTEM_DIRS = setOf(
        "/proc", "/sys", "/dev", "/run", "/snap",
    )

    private val WINDOWS_SYSTEM_DIRS = setOf(
        "C:\\Windows\\System32",
        "C:\\Windows\\SysWOW64",
    )

    private val MACOS_SYSTEM_DIRS = setOf(
        "/System", "/Library/Preferences/SystemConfiguration",
    )

    private val PROPERBACKUP_PATHS = setOf(
        ".properbackup/config.json",
        ".properbackup/state.json",
        ".properbackup/recovery_state.json",
        "plugins/ProperBackup/config.yml",
        "plugins/ProperBackup/state.json",
    )

    /**
     * Returns true if the given path should be PROTECTED (never deleted/overwritten).
     */
    fun isProtected(path: String, backupRoots: List<String> = emptyList()): Boolean {
        val normalized = path.replace("\\", "/")

        // System directories
        for (dir in UNIX_SYSTEM_DIRS + MACOS_SYSTEM_DIRS) {
            if (normalized.startsWith(dir.replace("\\", "/"))) return true
        }
        for (dir in WINDOWS_SYSTEM_DIRS) {
            if (path.startsWith(dir, ignoreCase = true)) return true
        }

        // ProperBackup own state
        for (pbPath in PROPERBACKUP_PATHS) {
            if (normalized.endsWith(pbPath)) return true
        }

        // Quarantine directories
        if (normalized.contains(".properbackup/quarantine/")) return true

        // Symlinks going outside backup roots (path traversal defense)
        if (backupRoots.isNotEmpty()) {
            val resolvedNorm = normalized
            val insideAnyRoot = backupRoots.any { root ->
                resolvedNorm.startsWith(root.replace("\\", "/"))
            }
            if (!insideAnyRoot) return true
        }

        return false
    }

    /**
     * Filter a list of paths and return only the safe (non-protected) ones.
     */
    fun filterSafe(paths: List<String>, backupRoots: List<String> = emptyList()): List<String> =
        paths.filter { !isProtected(it, backupRoots) }

    /**
     * Count how many paths in the list are protected.
     */
    fun countProtected(paths: List<String>, backupRoots: List<String> = emptyList()): Int =
        paths.count { isProtected(it, backupRoots) }
}
