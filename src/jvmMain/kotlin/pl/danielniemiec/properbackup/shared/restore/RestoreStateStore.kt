package pl.danielniemiec.properbackup.shared.restore

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Local persistence for recovery operations — enables resume on crash (HR-7).
 * Uses a simple file-based store (one file per session with completed operations).
 * Production could use SQLite; for now, simple append-only file is sufficient.
 */
class RestoreStateStore(private val stateDir: Path) {

    init {
        Files.createDirectories(stateDir)
    }

    private val inMemoryState = ConcurrentHashMap<String, MutableSet<String>>()

    fun saveDryRun(sessionId: String, result: DiffResult) {
        val file = stateDir.resolve("$sessionId.dry_run")
        Files.writeString(file, buildString {
            appendLine("files_to_restore=${result.filesToRestore.size}")
            appendLine("files_to_delete=${result.filesToDelete.size}")
            appendLine("files_unchanged=${result.filesUnchanged}")
            appendLine("critical_protected=${result.criticalProtected}")
            appendLine("total_restore_bytes=${result.totalRestoreBytes}")
        })
    }

    fun markInProgress(sessionId: String, path: String, type: OperationType) {
        val file = stateDir.resolve("$sessionId.progress")
        Files.writeString(file, "$type:IN_PROGRESS:$path\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    fun markDone(sessionId: String, path: String) {
        val file = stateDir.resolve("$sessionId.done")
        Files.writeString(file, "$path\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        inMemoryState.getOrPut(sessionId) { mutableSetOf() }.add(path)
    }

    fun markFailed(sessionId: String, path: String, error: String) {
        val file = stateDir.resolve("$sessionId.failed")
        Files.writeString(file, "$path|$error\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    fun getCompletedPaths(sessionId: String): Set<String> {
        val cached = inMemoryState[sessionId]
        if (cached != null) return cached

        val file = stateDir.resolve("$sessionId.done")
        if (!Files.exists(file)) return emptySet()

        val paths = Files.readAllLines(file).filter { it.isNotBlank() }.toMutableSet()
        inMemoryState[sessionId] = paths
        return paths
    }

    fun getCompletedDeleteOps(sessionId: String): List<String> {
        val file = stateDir.resolve("$sessionId.deletes")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter { it.isNotBlank() }
    }

    fun cleanup(sessionId: String) {
        inMemoryState.remove(sessionId)
        listOf("dry_run", "progress", "done", "failed", "deletes").forEach { suffix ->
            Files.deleteIfExists(stateDir.resolve("$sessionId.$suffix"))
        }
    }
}
