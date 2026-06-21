package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RestoreStateStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save and load operations`() {
        val store = RestoreStateStore(tempDir.resolve("state.json"))
        val ops = listOf(
            RecoveryOperation("op1", OperationType.RESTORE, "/a/b.txt", sha256Target = "abc123"),
            RecoveryOperation("op2", OperationType.DELETE, "/c/d.txt"),
        )
        store.save(ops)
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("op1", loaded[0].id)
        assertEquals("PENDING", loaded[0].state)
    }

    @Test
    fun `markState updates single operation`() {
        val store = RestoreStateStore(tempDir.resolve("state.json"))
        val ops = listOf(
            RecoveryOperation("op1", OperationType.RESTORE, "/a/b.txt"),
            RecoveryOperation("op2", OperationType.DELETE, "/c/d.txt"),
        )
        store.save(ops)
        store.markState("op1", OperationState.DOWNLOADING)

        val loaded = store.load()
        assertEquals("DOWNLOADING", loaded[0].state)
        assertEquals("PENDING", loaded[1].state)
    }

    @Test
    fun `markState with error`() {
        val store = RestoreStateStore(tempDir.resolve("state.json"))
        val ops = listOf(RecoveryOperation("op1", OperationType.RESTORE, "/x.txt"))
        store.save(ops)
        store.markState("op1", OperationState.FAILED, "SHA mismatch")

        val loaded = store.load()
        assertEquals("FAILED", loaded[0].state)
        assertEquals("SHA mismatch", loaded[0].error)
    }

    @Test
    fun `load from empty returns empty list`() {
        val store = RestoreStateStore(tempDir.resolve("nonexistent.json"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `cleanup removes file`() {
        val file = tempDir.resolve("state.json")
        val store = RestoreStateStore(file)
        store.save(listOf(RecoveryOperation("op1", OperationType.RESTORE, "/x")))
        assertTrue(store.exists())
        store.cleanup()
        assertFalse(store.exists())
    }

    @Test
    fun `handles corrupt json gracefully`() {
        val file = tempDir.resolve("corrupt.json")
        java.nio.file.Files.writeString(file, "not-valid-json{{{")
        val store = RestoreStateStore(file)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `state transitions tracked correctly through full lifecycle`() {
        val store = RestoreStateStore(tempDir.resolve("lifecycle.json"))
        val ops = listOf(RecoveryOperation("op1", OperationType.RESTORE, "/file.txt"))
        store.save(ops)

        store.markState("op1", OperationState.DOWNLOADING)
        assertEquals("DOWNLOADING", store.load()[0].state)

        store.markState("op1", OperationState.WRITING)
        assertEquals("WRITING", store.load()[0].state)

        store.markState("op1", OperationState.VERIFYING)
        assertEquals("VERIFYING", store.load()[0].state)

        store.markState("op1", OperationState.DONE)
        assertEquals("DONE", store.load()[0].state)
    }
}
