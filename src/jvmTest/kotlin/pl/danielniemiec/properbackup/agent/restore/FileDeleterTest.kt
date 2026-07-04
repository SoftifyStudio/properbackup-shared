package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileDeleterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createDeleter(sessionId: String = "session-001"): Triple<FileDeleter, RestoreStateStore, Path> {
        val quarantineDir = tempDir.resolve("quarantine")
        val stateStore = RestoreStateStore(tempDir.resolve("state.json"))
        val deleter = FileDeleter(quarantineDir, sessionId, stateStore)
        return Triple(deleter, stateStore, quarantineDir)
    }

    @Test
    fun `moves file to quarantine`() {
        val (deleter, stateStore, quarantineDir) = createDeleter()
        val file = tempDir.resolve("data/file.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "to be quarantined")

        val op = RecoveryOperation("op1", OperationType.DELETE, file.toString())
        stateStore.save(listOf(op))

        deleter.delete(op)

        assertFalse(Files.exists(file), "Original file should be removed")
        assertEquals(OperationState.DONE, op.state)

        // Quarantine directory should contain the file
        val quarantineFiles = Files.list(quarantineDir.resolve("session-001"))
            .filter { !it.toString().endsWith(".meta") }
            .count()
        assertEquals(1, quarantineFiles)
    }

    @Test
    fun `creates metadata file in quarantine`() {
        val (deleter, stateStore, quarantineDir) = createDeleter()
        val file = tempDir.resolve("data/important.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "important data")

        val op = RecoveryOperation("op1", OperationType.DELETE, file.toString())
        stateStore.save(listOf(op))

        deleter.delete(op)

        val metaFiles = Files.list(quarantineDir.resolve("session-001"))
            .filter { it.toString().endsWith(".meta") }
            .count()
        assertEquals(1, metaFiles)
    }

    @Test
    fun `idempotent - marks done if file does not exist`() {
        val (deleter, stateStore, _) = createDeleter()
        val op = RecoveryOperation("op1", OperationType.DELETE, tempDir.resolve("nonexistent.txt").toString())
        stateStore.save(listOf(op))

        deleter.delete(op)
        assertEquals(OperationState.DONE, op.state)
    }

    @Test
    fun `rollback restores quarantined files`() {
        val (deleter, stateStore, _) = createDeleter()
        val file = tempDir.resolve("rollback-test/file.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "restore me")

        val op = RecoveryOperation("op1", OperationType.DELETE, file.toString())
        stateStore.save(listOf(op))

        deleter.delete(op)
        assertFalse(Files.exists(file))

        deleter.rollback()
        assertTrue(Files.exists(file), "File should be restored after rollback")
        assertEquals("restore me", Files.readString(file))
    }

    @Test
    fun `multiple files quarantined and rolled back`() {
        val (deleter, stateStore, _) = createDeleter()
        val file1 = tempDir.resolve("multi/a.txt")
        val file2 = tempDir.resolve("multi/b.txt")
        Files.createDirectories(file1.parent)
        Files.writeString(file1, "content a")
        Files.writeString(file2, "content b")

        val op1 = RecoveryOperation("op1", OperationType.DELETE, file1.toString())
        val op2 = RecoveryOperation("op2", OperationType.DELETE, file2.toString())
        stateStore.save(listOf(op1, op2))

        deleter.delete(op1)
        deleter.delete(op2)

        assertFalse(Files.exists(file1))
        assertFalse(Files.exists(file2))

        deleter.rollback()

        assertTrue(Files.exists(file1))
        assertTrue(Files.exists(file2))
        assertEquals("content a", Files.readString(file1))
        assertEquals("content b", Files.readString(file2))
    }

    @Test
    fun `quarantine uses unique path hash per file`() {
        val (deleter, stateStore, quarantineDir) = createDeleter()
        val file1 = tempDir.resolve("unique/a.txt")
        val file2 = tempDir.resolve("unique/b.txt")
        Files.createDirectories(file1.parent)
        Files.writeString(file1, "a")
        Files.writeString(file2, "b")

        val op1 = RecoveryOperation("op1", OperationType.DELETE, file1.toString())
        val op2 = RecoveryOperation("op2", OperationType.DELETE, file2.toString())
        stateStore.save(listOf(op1, op2))

        deleter.delete(op1)
        deleter.delete(op2)

        // Should have 2 data files + 2 meta files = 4 entries
        val entries = Files.list(quarantineDir.resolve("session-001")).count()
        assertEquals(4, entries)
    }
}
