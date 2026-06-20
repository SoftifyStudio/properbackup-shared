package pl.danielniemiec.properbackup.core.restore

/**
 * Callback interface for reporting restore progress to the buffer.
 * Implementors post incremental progress and state transitions via HTTP.
 */
interface RestoreProgressReporter {
    fun reportProgress(sessionId: String, filesRestoredDelta: Int, filesDeletedDelta: Int, bytesDelta: Long)
    fun reportComplete(sessionId: String, success: Boolean, failureReason: String? = null)
    fun reportDryRunResult(
        sessionId: String,
        filesToRestore: Int,
        filesToDelete: Int,
        filesUnchanged: Int,
        totalBytes: Long,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        criticalProtected: Int,
        estimatedDurationSeconds: Int?,
    )
}
