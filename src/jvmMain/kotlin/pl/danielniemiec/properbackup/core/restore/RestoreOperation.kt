package pl.danielniemiec.properbackup.core.restore

/**
 * Represents a single file operation within a recovery session.
 * Each operation is idempotent and tracked for crash recovery (HR-7).
 */
data class RestoreOperation(
    val id: String,
    val recoverySessionId: String,
    val type: OperationType,
    val path: String,
    val pathHash: String,
    val sha256Target: String?,
    val sha256Local: String?,
    val sizeBytes: Long?,
    val state: OperationState,
    val error: String? = null,
) {
    enum class OperationType { RESTORE, DELETE, SKIP }
    enum class OperationState { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED }
}
