package pl.danielniemiec.properbackup.agent.restore

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface RestoreFileSource {
    fun openFile(path: String): InputStream
}

interface RestoreProgressCallback {
    fun onProgress(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long)
    fun onOperationDone(op: RestoreOperation, result: String)
    fun onComplete(filesRestored: Int, filesDeleted: Int, bytesTransferred: Long)
    fun onFailed(reason: String)
}

class RestoreExecutor(
    private val stateStore: RestoreStateStore,
    private val fileRestorer: FileRestorer,
    private val fileDeleter: FileDeleter,
    private val guard: CriticalPathsGuard,
    private val fileSource: RestoreFileSource,
) {
    private val cancelled = AtomicBoolean(false)
    private val filesRestoredCount = AtomicInteger(0)
    private val filesDeletedCount = AtomicInteger(0)
    private val bytesTransferredTotal = AtomicLong(0)

    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean get() = cancelled.get()

    fun execute(
        sessionId: String,
        diff: DiffResult,
        callback: RestoreProgressCallback,
    ) {
        cancelled.set(false)
        filesRestoredCount.set(0)
        filesDeletedCount.set(0)
        bytesTransferredTotal.set(0)

        for (entry in diff.entries) {
            if (entry.action == FileDiffEntry.DiffAction.UNCHANGED) continue

            val op = RestoreOperation(
                id = UUID.randomUUID().toString(),
                recoverySessionId = sessionId,
                type = when (entry.action) {
                    FileDiffEntry.DiffAction.RESTORE -> RestoreOperation.OperationType.RESTORE
                    FileDiffEntry.DiffAction.DELETE -> RestoreOperation.OperationType.DELETE
                    else -> continue
                },
                path = entry.path,
                pathHash = entry.pathHash,
                sha256Target = entry.sha256Target,
                sha256Local = null,
                sizeBytes = entry.sizeBytes,
                state = RestoreOperation.OperationState.PENDING,
            )
            stateStore.insertOperation(op)
        }

        val pending = stateStore.pendingOperations(sessionId)

        for (op in pending) {
            if (cancelled.get()) {
                callback.onFailed("Cancelled by user")
                return
            }

            stateStore.updateState(op.id, RestoreOperation.OperationState.IN_PROGRESS)

            try {
                when (op.type) {
                    RestoreOperation.OperationType.RESTORE -> {
                        if (guard.isCritical(op.path)) {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED, "Critical path protected")
                            callback.onOperationDone(op, "skipped:critical")
                            continue
                        }

                        val sourceStream = fileSource.openFile(op.path)
                        val result = fileRestorer.restoreFile(
                            targetPath = File(op.path),
                            sourceStream = sourceStream,
                            expectedSha256 = op.sha256Target,
                            sizeBytes = op.sizeBytes,
                        )

                        when (result) {
                            is FileRestorer.RestoreResult.Success -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesRestoredCount.incrementAndGet()
                                bytesTransferredTotal.addAndGet(result.bytesWritten)
                                callback.onOperationDone(op, "done")
                            }
                            is FileRestorer.RestoreResult.ShaVerifyFailed -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED,
                                    "SHA-256 mismatch: expected=${result.expected}, actual=${result.actual}")
                                callback.onFailed("SHA-256 verification failed for ${op.path}")
                                return
                            }
                            is FileRestorer.RestoreResult.Error -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                                callback.onFailed("Restore failed for ${op.path}: ${result.message}")
                                return
                            }
                        }
                    }
                    RestoreOperation.OperationType.DELETE -> {
                        val result = fileDeleter.deleteFile(File(op.path), op.recoverySessionId)
                        when (result) {
                            is FileDeleter.DeleteResult.MovedToQuarantine -> {
                                stateStore.setQuarantinePath(op.id, result.quarantinePath)
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesDeletedCount.incrementAndGet()
                                callback.onOperationDone(op, "deleted")
                            }
                            is FileDeleter.DeleteResult.AlreadyGone -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesDeletedCount.incrementAndGet()
                                callback.onOperationDone(op, "already_gone")
                            }
                            is FileDeleter.DeleteResult.Blocked -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED, result.reason)
                                callback.onOperationDone(op, "skipped:blocked")
                            }
                            is FileDeleter.DeleteResult.Error -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                                callback.onFailed("Delete failed for ${op.path}: ${result.message}")
                                return
                            }
                        }
                    }
                }

                callback.onProgress(
                    filesRestoredCount.get(),
                    filesDeletedCount.get(),
                    bytesTransferredTotal.get(),
                )
            } catch (e: Exception) {
                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, e.message)
                callback.onFailed("Exception during ${op.type.name} of ${op.path}: ${e.message}")
                return
            }
        }

        callback.onComplete(
            filesRestoredCount.get(),
            filesDeletedCount.get(),
            bytesTransferredTotal.get(),
        )
    }

    fun resume(
        sessionId: String,
        callback: RestoreProgressCallback,
    ) {
        cancelled.set(false)

        val inProgress = stateStore.inProgressOperations(sessionId)
        for (op in inProgress) {
            stateStore.updateState(op.id, RestoreOperation.OperationState.PENDING)
        }

        val counts = stateStore.countByState(sessionId)
        filesRestoredCount.set(counts[RestoreOperation.OperationState.DONE] ?: 0)
        filesDeletedCount.set(0)
        bytesTransferredTotal.set(0)

        val pending = stateStore.pendingOperations(sessionId)
        if (pending.isEmpty()) {
            callback.onComplete(filesRestoredCount.get(), filesDeletedCount.get(), bytesTransferredTotal.get())
            return
        }

        for (op in pending) {
            if (cancelled.get()) {
                callback.onFailed("Cancelled by user")
                return
            }

            stateStore.updateState(op.id, RestoreOperation.OperationState.IN_PROGRESS)

            try {
                when (op.type) {
                    RestoreOperation.OperationType.RESTORE -> {
                        if (guard.isCritical(op.path)) {
                            stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED, "Critical path protected")
                            continue
                        }
                        val sourceStream = fileSource.openFile(op.path)
                        val result = fileRestorer.restoreFile(
                            targetPath = File(op.path),
                            sourceStream = sourceStream,
                            expectedSha256 = op.sha256Target,
                            sizeBytes = op.sizeBytes,
                        )
                        when (result) {
                            is FileRestorer.RestoreResult.Success -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesRestoredCount.incrementAndGet()
                                bytesTransferredTotal.addAndGet(result.bytesWritten)
                            }
                            is FileRestorer.RestoreResult.ShaVerifyFailed -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED,
                                    "SHA-256 mismatch: expected=${result.expected}, actual=${result.actual}")
                                callback.onFailed("SHA-256 verification failed for ${op.path}")
                                return
                            }
                            is FileRestorer.RestoreResult.Error -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                                callback.onFailed("Restore failed for ${op.path}: ${result.message}")
                                return
                            }
                        }
                    }
                    RestoreOperation.OperationType.DELETE -> {
                        val result = fileDeleter.deleteFile(File(op.path), op.recoverySessionId)
                        when (result) {
                            is FileDeleter.DeleteResult.MovedToQuarantine -> {
                                stateStore.setQuarantinePath(op.id, result.quarantinePath)
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesDeletedCount.incrementAndGet()
                            }
                            is FileDeleter.DeleteResult.AlreadyGone -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.DONE)
                                filesDeletedCount.incrementAndGet()
                            }
                            is FileDeleter.DeleteResult.Blocked -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.SKIPPED, result.reason)
                            }
                            is FileDeleter.DeleteResult.Error -> {
                                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, result.message)
                                callback.onFailed("Delete failed for ${op.path}: ${result.message}")
                                return
                            }
                        }
                    }
                }

                callback.onProgress(
                    filesRestoredCount.get(),
                    filesDeletedCount.get(),
                    bytesTransferredTotal.get(),
                )
            } catch (e: Exception) {
                stateStore.updateState(op.id, RestoreOperation.OperationState.FAILED, e.message)
                callback.onFailed("Exception during resume ${op.type.name} of ${op.path}: ${e.message}")
                return
            }
        }

        callback.onComplete(
            filesRestoredCount.get(),
            filesDeletedCount.get(),
            bytesTransferredTotal.get(),
        )
    }
}
