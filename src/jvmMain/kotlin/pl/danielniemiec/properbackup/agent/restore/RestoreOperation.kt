package pl.danielniemiec.properbackup.agent.restore

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
    enum class OperationType { RESTORE, DELETE }
    enum class OperationState { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED }
}
