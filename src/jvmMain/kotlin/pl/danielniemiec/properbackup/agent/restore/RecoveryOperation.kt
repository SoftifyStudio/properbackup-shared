package pl.danielniemiec.properbackup.agent.restore

data class RecoveryOperation(
    val id: String,
    val type: OperationType,
    val path: String,
    val sha256Target: String? = null,
    val sha256Local: String? = null,
    val sizeBytes: Long = 0,
    var state: OperationState = OperationState.PENDING,
    var error: String? = null,
)

enum class OperationType { RESTORE, DELETE, SKIP }

enum class OperationState {
    PENDING, DOWNLOADING, WRITING, VERIFYING, DONE, FAILED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED
}
