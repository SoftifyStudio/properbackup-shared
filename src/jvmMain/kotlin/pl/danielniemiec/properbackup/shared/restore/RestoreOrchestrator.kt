package pl.danielniemiec.properbackup.shared.restore

import java.io.InputStream
import java.nio.file.Path

/**
 * Top-level restore orchestration per user-facing-recovery-spec.md §9.2.
 * Coordinates: dry run → pre-recovery snapshot → per-file restore/delete → verification.
 * Resumable on crash via RestoreStateStore.
 */
class RestoreOrchestrator(
    private val sessionId: String,
    private val targetSnapshotId: String,
    private val baseDir: Path,
    private val backupRoots: List<String>,
    private val snapshotDiff: SnapshotDiff,
    private val fileRestorer: FileRestorer,
    private val fileDeleter: FileDeleter,
    private val stateStore: RestoreStateStore,
    private val progressReporter: ProgressReporter,
) {
    companion object {
        const val BATCH_SIZE = 100
    }

    interface ProgressReporter {
        fun reportDryRun(result: DiffResult)
        fun reportProgress(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long)
        fun reportComplete(success: Boolean, reason: String? = null)
    }

    /**
     * Phase 1: Compute dry run and report to buffer.
     */
    fun computeDryRun(
        snapshotEntries: List<SnapshotManifestEntry>,
        localEntries: List<LocalFileEntry>,
    ): DiffResult {
        val result = snapshotDiff.compute(snapshotEntries, localEntries, backupRoots)
        stateStore.saveDryRun(sessionId, result)
        progressReporter.reportDryRun(result)
        return result
    }

    /**
     * Phase 2: Execute recovery — restore + delete per plan.
     * Resumable: reads state from RestoreStateStore on crash recovery.
     */
    fun execute(diff: DiffResult): Boolean {
        var filesRestored = 0
        var filesDeleted = 0
        var bytesTransferred = 0L
        var batchCount = 0

        // Resume support: skip operations already done
        val completedPaths = stateStore.getCompletedPaths(sessionId)

        // Restore files
        for (op in diff.filesToRestore) {
            if (op.path in completedPaths) {
                filesRestored++
                continue
            }

            stateStore.markInProgress(sessionId, op.path, OperationType.RESTORE)
            val result = fileRestorer.restore(op, baseDir)

            if (result.success) {
                stateStore.markDone(sessionId, op.path)
                filesRestored++
                bytesTransferred += result.bytesWritten
            } else {
                stateStore.markFailed(sessionId, op.path, result.error ?: "unknown")
                progressReporter.reportComplete(false, "Failed to restore: ${op.path} — ${result.error}")
                return false
            }

            batchCount++
            if (batchCount >= BATCH_SIZE) {
                progressReporter.reportProgress(filesRestored, filesDeleted, bytesTransferred)
                batchCount = 0
            }
        }

        // Delete files (quarantine)
        for (op in diff.filesToDelete) {
            if (op.path in completedPaths) {
                filesDeleted++
                continue
            }

            stateStore.markInProgress(sessionId, op.path, OperationType.DELETE)
            val result = fileDeleter.delete(op, baseDir)

            if (result.success) {
                stateStore.markDone(sessionId, op.path)
                filesDeleted++
            } else {
                stateStore.markFailed(sessionId, op.path, result.error ?: "unknown")
                progressReporter.reportComplete(false, "Failed to delete: ${op.path} — ${result.error}")
                return false
            }

            batchCount++
            if (batchCount >= BATCH_SIZE) {
                progressReporter.reportProgress(filesRestored, filesDeleted, bytesTransferred)
                batchCount = 0
            }
        }

        // Final progress report
        progressReporter.reportProgress(filesRestored, filesDeleted, bytesTransferred)
        progressReporter.reportComplete(true)
        return true
    }

    /**
     * Cancel: rollback already-deleted files from quarantine (HR-9).
     */
    fun cancel(): Int {
        val deletedOps = stateStore.getCompletedDeleteOps(sessionId)
        return fileDeleter.rollbackAll(deletedOps.map { path ->
            FileOperation(path, sha256Hash(path), type = OperationType.DELETE)
        }, baseDir)
    }

    private fun sha256Hash(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
