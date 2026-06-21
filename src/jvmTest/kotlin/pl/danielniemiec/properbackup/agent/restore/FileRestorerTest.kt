package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FileRestorerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun createRestorer(): Pair<FileRestorer, RestoreStateStore> {
        val stateStore = RestoreStateStore(tempDir.resolve("state.json"))
        val restorer = FileRestorer(stateStore) { it } // identity decrypt
        return restorer to stateStore
    }

    @Test
    fun `restores file atomically with correct SHA-256`() {
        val (restorer, stateStore) = createRestorer()
        val content = "Hello, World!".toByteArray()
        val hash = sha256(content)
        val targetPath = tempDir.resolve("output/file.txt")

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = hash)
        stateStore.save(listOf(op))

        restorer.restore(op, content)

        assertTrue(Files.exists(targetPath))
        assertEquals("Hello, World!", Files.readString(targetPath))
        assertEquals(OperationState.DONE, op.state)
    }

    @Test
    fun `fails on SHA-256 mismatch`() {
        val (restorer, stateStore) = createRestorer()
        val content = "correct data".toByteArray()
        val targetPath = tempDir.resolve("mismatch/file.txt")

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = "wrong_hash")
        stateStore.save(listOf(op))

        assertThrows(RestoreException::class.java) {
            restorer.restore(op, content)
        }
        assertFalse(Files.exists(targetPath))
        assertEquals(OperationState.FAILED, op.state)
        assertTrue(op.error!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun `cleans up tmp file on SHA-256 mismatch`() {
        val (restorer, stateStore) = createRestorer()
        val content = "data".toByteArray()
        val targetPath = tempDir.resolve("cleanup/file.txt")
        Files.createDirectories(targetPath.parent)

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = "bad_hash")
        stateStore.save(listOf(op))

        assertThrows(RestoreException::class.java) {
            restorer.restore(op, content)
        }

        // No .tmp files should remain
        val tmpFiles = Files.list(targetPath.parent)
            .filter { it.fileName.toString().contains(".recovery-") }
            .count()
        assertEquals(0, tmpFiles)
    }

    @Test
    fun `idempotent - skip if file already has correct hash`() {
        val (restorer, stateStore) = createRestorer()
        val content = "existing content".toByteArray()
        val hash = sha256(content)
        val targetPath = tempDir.resolve("idempotent/file.txt")
        Files.createDirectories(targetPath.parent)
        Files.write(targetPath, content)

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = hash)
        stateStore.save(listOf(op))

        restorer.restore(op, content) // should be a no-op
        assertEquals(OperationState.DONE, op.state)
    }

    @Test
    fun `overwrites existing file with wrong hash`() {
        val (restorer, stateStore) = createRestorer()
        val oldContent = "old data".toByteArray()
        val newContent = "new data".toByteArray()
        val newHash = sha256(newContent)
        val targetPath = tempDir.resolve("overwrite/file.txt")
        Files.createDirectories(targetPath.parent)
        Files.write(targetPath, oldContent)

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = newHash)
        stateStore.save(listOf(op))

        restorer.restore(op, newContent)
        assertEquals("new data", Files.readString(targetPath))
        assertEquals(OperationState.DONE, op.state)
    }

    @Test
    fun `creates parent directories if missing`() {
        val (restorer, stateStore) = createRestorer()
        val content = "deep content".toByteArray()
        val hash = sha256(content)
        val targetPath = tempDir.resolve("a/b/c/d/file.txt")

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = hash)
        stateStore.save(listOf(op))

        restorer.restore(op, content)
        assertTrue(Files.exists(targetPath))
        assertEquals("deep content", Files.readString(targetPath))
    }

    @Test
    fun `restores binary content correctly`() {
        val (restorer, stateStore) = createRestorer()
        val content = ByteArray(512 * 1024) { (it % 256).toByte() }
        val hash = sha256(content)
        val targetPath = tempDir.resolve("binary/data.bin")

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = hash)
        stateStore.save(listOf(op))

        restorer.restore(op, content)
        assertTrue(Files.exists(targetPath))
        assertArrayEquals(content, Files.readAllBytes(targetPath))
    }

    @Test
    fun `sha256 utility produces correct hash`() {
        val data = "test".toByteArray()
        val hash = FileRestorer.sha256(data)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", hash)
    }

    @Test
    fun `restores file without sha256 target check`() {
        val (restorer, stateStore) = createRestorer()
        val content = "no hash check".toByteArray()
        val targetPath = tempDir.resolve("nohash/file.txt")

        val op = RecoveryOperation("op1", OperationType.RESTORE, targetPath.toString(), sha256Target = null)
        stateStore.save(listOf(op))

        restorer.restore(op, content)
        assertTrue(Files.exists(targetPath))
        assertEquals(OperationState.DONE, op.state)
    }
}
