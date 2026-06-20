package pl.danielniemiec.properbackup.core.restore

import java.io.InputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the full restore flow:
 *   1. Compute diff (DRY RUN) and report to buffer
 *   2. Wait for user confirmation (buffer transitions state)
 *   3. Create pre-recovery snapshot (HR-5)
 *   4. Execute per-file restore/delete with progress reporting
 *   5. Verify restored state matches snapshot
 *
 * Resumable on crash (HR-7): operations tracked in SQLite via RestoreStateStore.
 * Cancel anywhere (HR-9): files quarantined for rollback.
 * Cross-host parity (HR-10): same code for VPS and MC plugin.
 */
class RestoreOrchestrator(
    private val stateStore: RestoreStateStore,
    private val fileRestorer: FileRestorer,
    private val fileDeleter: FileDeleter,
    private val reporter: RestoreProgressReporter,
    private val fileProvider: RestoreFileProvider,
) {

    private val cancelled = AtomicBoolean(false)
    private val progressBatchSize = 10

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun computeDryRun(
        sessionId: String,
        snapshotFiles: Map<String, SnapshotDiff.FileEntry>,
        localFiles: Map<String, SnapshotDiff.FileEntry>,
    ): SnapshotDiff.DiffResult {
        val diff = SnapshotDiff.compute(snapshotFiles, localFiles)
        reporter.reportDryRunResult(
            sessionId = sessionId,
            filesToRestore = diff.filesToRestore.size,
            filesToDelete = diff.filesToDelete.size,
            filesUnchanged = diff.filesUnchanged.size,
            totalBytes = diff.totalRestoreBytes,
            sampleRestore = diff.filesToRestore.take(20).map { it.path },
            sampleDelete = diff.filesToDelete.take(20).map { it.path },
            criticalProtected = diff.criticalProtected.size,
            estimatedDurationSeconds = estimateDuration(diff),
        )
        return diff
    }

    fun planOperations(sessionId: String, diff: SnapshotDiff.DiffResult): List<RestoreOperation> {
        val ops = mutableListOf<RestoreOperation>()
        for (file in diff.filesToRestore) {
            ops.add(
                RestoreOperation(
                    id = UUID.randomUUID().toString(),
                    recoverySessionId = sessionId,
                    type = RestoreOperation.OperationType.RESTORE,
                    path = file.path,
                    pathHash = SnapshotDiff.pathHash(file.path),
                    sha256Target = file.sha256,
                    sha256Local = null,
                    sizeBytes = file.sizeBytes,
                    state = RestoreOperation.OperationState.PENDING,
                ),
            )
        }
        for (file in diff.filesToDelete) {
            ops.add(
                RestoreOperation(
                    id = UUID.randomUUID().toString(),
                    recoverySessionId = sessionId,
                    type = RestoreOperation.OperationType.DELETE,
                    path = file.path,
                    pathHash = SnapshotDiff.pathHash(file.path),
                    sha256Target = null,
                    sha256Local = file.sha256,
                    sizeBytes = file.sizeBytes,
                    state = RestoreOperation.OperationState.PENDING,
                ),
            )
        }
        stateStore.insertOperations(ops)
        return ops
    }

    fun executeRestore(sessionId: String): ExecuteResult {
        val pending = stateStore.pendingOperations(sessionId)
        if (pending.isEmpty()) {
            return ExecuteResult(success = true, filesRestored = 0, filesDeleted = 0, bytesTransferred = 0, errors = emptyList())
        }

        var filesRestored = 0
        var filesDeleted = 0
        var bytesTransferred = 0L
        val errors = mutableListOf<String>()
        var batchRestored = 0
        var batchDeleted = 0
        var batchBytes = 0L

        for (op in pending) {
            if (cancelled.get()) {
                return ExecuteResult(
                    success = false,
                    filesRestored = filesRestored,
                    filesDeleted = filesDeleted,
                    bytesTransferred = bytesTransferred,
                    errors = errors,
                    cancelledMidway = true,
                )
            }

            stateStore.updateState(op.id, RestoreOperation.OperationState.IN_PROGRESS)

            when (op.type) {
                RestoreOperation.OperationType.RESTORE -> {
                    val result = restoreSingleFile(op, sessionId)
                    when (result) {
                        is FileRestorer.RestoreResult.Success -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                            filesRestored++
                            bytesTransferred += result.bytesWritten
                            batchRestored++
                            batchBytes += result.bytesWritten
                        }
                        is FileRestorer.RestoreResult.HashMismatch -> {
                            val err = "SHA-256 mismatch for ${op.path}: expected=${result.expected} actual=${result.actual}"
                            stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, err)
                            errors.add(err)
                        }
                        is FileRestorer.RestoreResult.Error -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                            errors.add("${op.path}: ${result.message}")
                        }
                    }
                }
                RestoreOperation.OperationType.DELETE -> {
                    val result = fileDeleter.deleteFile(Path.of(op.path), sessionId)
                    when (result) {
                        is FileDeleter.DeleteResult.Success -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                            filesDeleted++
                            batchDeleted++
                        }
                        is FileDeleter.DeleteResult.AlreadyGone -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                            filesDeleted++
                            batchDeleted++
                        }
                        is FileDeleter.DeleteResult.ProtectedCritical -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED, "critical path protected")
                        }
                        is FileDeleter.DeleteResult.Error -> {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                            errors.add("delete ${op.path}: ${result.message}")
                        }
                    }
                }
                RestoreOperation.OperationType.SKIP -> {
                    stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED)
                }
            }

            if (batchRestored + batchDeleted >= progressBatchSize) {
                reporter.reportProgress(sessionId, batchRestored, batchDeleted, batchBytes)
                batchRestored = 0
                batchDeleted = 0
                batchBytes = 0
            }
        }

        if (batchRestored + batchDeleted > 0) {
            reporter.reportProgress(sessionId, batchRestored, batchDeleted, batchBytes)
        }

        return ExecuteResult(
            success = errors.isEmpty(),
            filesRestored = filesRestored,
            filesDeleted = filesDeleted,
            bytesTransferred = bytesTransferred,
            errors = errors,
        )
    }

    fun rollback(sessionId: String): Int {
        return fileRestorer.rollbackFromQuarantine(sessionId)
    }

    fun cleanup(sessionId: String) {
        fileRestorer.cleanupQuarantine(sessionId)
        stateStore.cleanup(sessionId)
    }

    private fun restoreSingleFile(op: RestoreOperation, sessionId: String): FileRestorer.RestoreResult {
        val stream = fileProvider.openFileStream(op.path, op.sha256Target)
            ?: return FileRestorer.RestoreResult.Error("File not available from provider: ${op.path}")
        return stream.use { inputStream ->
            fileRestorer.restoreFile(Path.of(op.path), inputStream, op.sha256Target, sessionId)
        }
    }

    private fun estimateDuration(diff: SnapshotDiff.DiffResult): Int {
        val bytesPerSec = 10 * 1024 * 1024L
        val downloadSec = diff.totalRestoreBytes / bytesPerSec
        val opsSec = (diff.filesToRestore.size + diff.filesToDelete.size) / 100
        return (downloadSec + opsSec + 10).toInt()
    }

    data class ExecuteResult(
        val success: Boolean,
        val filesRestored: Int,
        val filesDeleted: Int,
        val bytesTransferred: Long,
        val errors: List<String>,
        val cancelledMidway: Boolean = false,
    )
}
