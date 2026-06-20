package pl.danielniemiec.properbackup.core.restore

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class FileRestorerTest {

    companion object {
        private val tmpDir = Files.createTempDirectory("file-restorer-test")
        private val quarantineDir = tmpDir.resolve(".quarantine")
        private val restorer = FileRestorer(quarantineDir)

        @JvmStatic
        @AfterAll
        fun cleanup() {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @BeforeEach
    fun cleanDirs() {
        val dataDir = tmpDir.resolve("data")
        if (Files.exists(dataDir)) dataDir.toFile().deleteRecursively()
        Files.createDirectories(dataDir)
        if (Files.exists(quarantineDir)) quarantineDir.toFile().deleteRecursively()
    }

    @Test
    fun `restore file creates file with correct content`() {
        val targetPath = tmpDir.resolve("data/test.txt")
        val content = "hello world".toByteArray()
        val sha256 = SnapshotDiff.sha256Hex(content)
        val result = restorer.restoreFile(targetPath, ByteArrayInputStream(content), sha256, "session-1")
        assertTrue(result is FileRestorer.RestoreResult.Success)
        assertEquals(content.size.toLong(), (result as FileRestorer.RestoreResult.Success).bytesWritten)
        assertEquals("hello world", Files.readString(targetPath))
    }

    @Test
    fun `restore file with wrong sha256 fails`() {
        val targetPath = tmpDir.resolve("data/bad-hash.txt")
        val content = "hello world".toByteArray()
        val result = restorer.restoreFile(targetPath, ByteArrayInputStream(content), "wrong_hash", "session-1")
        assertTrue(result is FileRestorer.RestoreResult.HashMismatch)
        assertFalse(Files.exists(targetPath))
    }

    @Test
    fun `restore file without sha256 check succeeds`() {
        val targetPath = tmpDir.resolve("data/no-hash.txt")
        val content = "data".toByteArray()
        val result = restorer.restoreFile(targetPath, ByteArrayInputStream(content), null, "session-1")
        assertTrue(result is FileRestorer.RestoreResult.Success)
        assertTrue(Files.exists(targetPath))
    }

    @Test
    fun `existing file is quarantined before restore`() {
        val targetPath = tmpDir.resolve("data/existing.txt")
        Files.createDirectories(targetPath.parent)
        Files.writeString(targetPath, "old content")
        val content = "new content".toByteArray()
        val result = restorer.restoreFile(targetPath, ByteArrayInputStream(content), null, "session-1")
        assertTrue(result is FileRestorer.RestoreResult.Success)
        assertEquals("new content", Files.readString(targetPath))
        assertTrue(Files.exists(quarantineDir.resolve("session-1")))
    }

    @Test
    fun `quarantine and rollback round-trip`() {
        val targetPath = tmpDir.resolve("data/rollback.txt")
        Files.createDirectories(targetPath.parent)
        Files.writeString(targetPath, "original")
        restorer.quarantineExisting(targetPath, "session-2")
        assertFalse(Files.exists(targetPath))
        assertTrue(Files.exists(quarantineDir.resolve("session-2")))
    }

    @Test
    fun `cleanup quarantine removes directory`() {
        val targetPath = tmpDir.resolve("data/cleanup.txt")
        Files.createDirectories(targetPath.parent)
        Files.writeString(targetPath, "cleanup test")
        restorer.quarantineExisting(targetPath, "session-3")
        assertTrue(Files.exists(quarantineDir.resolve("session-3")))
        restorer.cleanupQuarantine("session-3")
        assertFalse(Files.exists(quarantineDir.resolve("session-3")))
    }
}
