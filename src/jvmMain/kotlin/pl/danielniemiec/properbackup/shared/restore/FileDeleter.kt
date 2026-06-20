package pl.danielniemiec.properbackup.shared.restore

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * HR-7/HR-9: Quarantine-based file deletion.
 * Instead of permanent delete, moves files to .quarantine/<recovery_id>/<path_hash>
 * with 30-day TTL. Enables rollback on cancel.
 */
class FileDeleter(
    private val quarantineBase: Path,
    private val recoverySessionId: String,
) {
    data class DeleteResult(
        val path: String,
        val success: Boolean,
        val quarantinePath: String? = null,
        val error: String? = null,
    )

    private val quarantineDir: Path = quarantineBase.resolve(recoverySessionId)

    init {
        Files.createDirectories(quarantineDir)
    }

    /**
     * Move file to quarantine instead of deleting.
     * Idempotent: if file doesn't exist, consider it already deleted.
     */
    fun delete(operation: FileOperation, baseDir: Path): DeleteResult {
        val filePath = baseDir.resolve(operation.path)

        if (!Files.exists(filePath)) {
            return DeleteResult(operation.path, true, error = "Already absent")
        }

        try {
            val quarantinePath = quarantineDir.resolve(operation.pathHash)
            quarantinePath.parent?.let { Files.createDirectories(it) }
            Files.move(filePath, quarantinePath, StandardCopyOption.REPLACE_EXISTING)
            return DeleteResult(operation.path, true, quarantinePath = quarantinePath.toString())
        } catch (e: Exception) {
            return DeleteResult(operation.path, false, error = e.message)
        }
    }

    /**
     * Rollback: restore files from quarantine back to their original paths.
     * Used during cancel recovery (HR-9).
     */
    fun rollbackAll(deletedOperations: List<FileOperation>, baseDir: Path): Int {
        var restored = 0
        for (op in deletedOperations) {
            val quarantinePath = quarantineDir.resolve(op.pathHash)
            val originalPath = baseDir.resolve(op.path)
            if (Files.exists(quarantinePath)) {
                try {
                    originalPath.parent?.let { Files.createDirectories(it) }
                    Files.move(quarantinePath, originalPath, StandardCopyOption.REPLACE_EXISTING)
                    restored++
                } catch (_: Exception) {
                    // Best effort
                }
            }
        }
        return restored
    }

    /**
     * Cleanup quarantine after TTL (called by scheduled task).
     */
    fun cleanupQuarantine() {
        if (Files.exists(quarantineDir)) {
            Files.walk(quarantineDir).sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }
}
