package pl.danielniemiec.properbackup.agent.restore

import java.nio.file.Path

class CriticalPathsGuard(
    extraProtected: List<String> = emptyList(),
) {
    private val protectedPaths: Set<String> = DEFAULT_PROTECTED + extraProtected.toSet()

    fun isCritical(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return protectedPaths.any { pattern ->
            normalized == pattern
                || normalized.endsWith("/$pattern")
                || normalized.contains("/$pattern/")
                || normalized.startsWith("$pattern/")
        }
    }

    fun filterDeletions(paths: List<String>): Pair<List<String>, List<String>> {
        val allowed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        for (p in paths) {
            if (isCritical(p)) blocked.add(p) else allowed.add(p)
        }
        return allowed to blocked
    }

    companion object {
        val DEFAULT_PROTECTED: Set<String> = setOf(
            "global.yml",
            "agent.yml",
            "conf.d",
            ".properbackup",
            ".properbackup/agent.lock",
            ".properbackup/metadata.db",
            "properbackup-agent.jar",
            "properbackup-agent",
            "/etc/systemd/system/properbackup-agent.service",
            "/etc/init.d/properbackup-agent",
        )
    }
}
