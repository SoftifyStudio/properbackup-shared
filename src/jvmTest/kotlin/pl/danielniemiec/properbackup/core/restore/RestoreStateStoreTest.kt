package pl.danielniemiec.properbackup.core.restore

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RestoreStateStoreTest {

    companion object {
        private val tmpDir = Files.createTempDirectory("restore-state-test")
        private val store = RestoreStateStore(tmpDir.resolve("test-restore.db"))

        @JvmStatic
        @AfterAll
        fun cleanup() {
            store.close()
            tmpDir.toFile().deleteRecursively()
        }
    }

    @BeforeEach
    fun cleanTable() {
        store.cleanup("session-1")
    }

    private fun makeOp(
        id: String = "op-1",
        type: RestoreOperation.OperationType = RestoreOperation.OperationType.RESTORE,
        path: String = "/app/file.txt",
        state: RestoreOperation.OperationState = RestoreOperation.OperationState.PENDING,
    ) = RestoreOperation(
        id = id,
        recoverySessionId = "session-1",
        type = type,
        path = path,
        pathHash = SnapshotDiff.pathHash(path),
        sha256Target = "abcdef1234567890",
        sha256Local = null,
        sizeBytes = 1024,
        state = state,
    )

    @Test
    fun `insert and retrieve operation`() {
        val op = makeOp()
        store.insertOperation(op)
        val pending = store.pendingOperations("session-1")
        assertEquals(1, pending.size)
        assertEquals("op-1", pending[0].id)
        assertEquals(RestoreOperation.OperationState.PENDING, pending[0].state)
    }

    @Test
    fun `batch insert operations`() {
        val ops = listOf(makeOp("op-1"), makeOp("op-2", path = "/app/file2.txt"), makeOp("op-3", path = "/app/file3.txt"))
        store.insertOperations(ops)
        val all = store.allOperations("session-1")
        assertEquals(3, all.size)
    }

    @Test
    fun `update state`() {
        store.insertOperation(makeOp())
        store.updateState("op-1", RestoreOperation.OperationState.IN_PROGRESS)
        val pending = store.pendingOperations("session-1")
        assertEquals(1, pending.size)
        assertEquals(RestoreOperation.OperationState.IN_PROGRESS, pending[0].state)
    }

    @Test
    fun `update state to DONE removes from pending`() {
        store.insertOperation(makeOp())
        store.updateState("op-1", RestoreOperation.OperationState.DONE)
        val pending = store.pendingOperations("session-1")
        assertEquals(0, pending.size)
    }

    @Test
    fun `update state with error`() {
        store.insertOperation(makeOp())
        store.updateState("op-1", RestoreOperation.OperationState.FAILED, "disk full")
        val all = store.allOperations("session-1")
        assertEquals("disk full", all[0].error)
    }

    @Test
    fun `summary counts correctly`() {
        store.insertOperations(listOf(
            makeOp("op-1"),
            makeOp("op-2", path = "/b"),
            makeOp("op-3", path = "/c"),
        ))
        store.updateState("op-1", RestoreOperation.OperationState.DONE)
        store.updateState("op-2", RestoreOperation.OperationState.FAILED, "err")
        val summary = store.summary("session-1")
        assertEquals(3, summary.total)
        assertEquals(1, summary.done)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.pending)
    }

    @Test
    fun `cleanup removes all operations for session`() {
        store.insertOperations(listOf(makeOp("op-1"), makeOp("op-2", path = "/b")))
        store.cleanup("session-1")
        assertEquals(0, store.allOperations("session-1").size)
    }

    @Test
    fun `pendingOperations returns PENDING and IN_PROGRESS`() {
        store.insertOperations(listOf(
            makeOp("op-1"),
            makeOp("op-2", path = "/b"),
            makeOp("op-3", path = "/c"),
        ))
        store.updateState("op-1", RestoreOperation.OperationState.IN_PROGRESS)
        store.updateState("op-3", RestoreOperation.OperationState.DONE)
        val pending = store.pendingOperations("session-1")
        assertEquals(2, pending.size)
    }
}
