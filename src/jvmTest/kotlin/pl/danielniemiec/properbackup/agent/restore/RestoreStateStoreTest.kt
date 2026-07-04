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
    fun `loadOperations reconstructs full RecoveryOperation objects`() {
        val store = RestoreStateStore(tempDir.resolve("full_ops.json"))
        val ops = listOf(
            RecoveryOperation("op1", OperationType.RESTORE, "/a/file.txt",
                sha256Target = "abc123", sha256Local = "def456", sizeBytes = 1024),
            RecoveryOperation("op2", OperationType.DELETE, "/b/old.txt",
                sha256Local = "ghi789", sizeBytes = 512),
            RecoveryOperation("op3", OperationType.SKIP, "/c/skip.txt",
                state = OperationState.DONE),
        )
        store.save(ops)

        // Simulate crash: mark first op as DOWNLOADING (mid-progress)
        store.markState("op1", OperationState.DOWNLOADING)

        // Reconstruct from file (simulates restart)
        val reconstructed = store.loadOperations()!!
        assertEquals(3, reconstructed.size)

        // First op should retain DOWNLOADING state from crash
        assertEquals("op1", reconstructed[0].id)
        assertEquals(OperationType.RESTORE, reconstructed[0].type)
        assertEquals("/a/file.txt", reconstructed[0].path)
        assertEquals("abc123", reconstructed[0].sha256Target)
        assertEquals(OperationState.DOWNLOADING, reconstructed[0].state)
        assertEquals(1024L, reconstructed[0].sizeBytes)

        // Second op should be PENDING (not started before crash)
        assertEquals("op2", reconstructed[1].id)
        assertEquals(OperationType.DELETE, reconstructed[1].type)
        assertEquals(OperationState.PENDING, reconstructed[1].state)

        // Third op should be DONE (SKIP)
        assertEquals("op3", reconstructed[2].id)
        assertEquals(OperationType.SKIP, reconstructed[2].type)
        assertEquals(OperationState.DONE, reconstructed[2].state)
    }

    @Test
    fun `loadOperations returns null for empty or legacy state`() {
        val store = RestoreStateStore(tempDir.resolve("empty.json"))
        assertNull(store.loadOperations())
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
