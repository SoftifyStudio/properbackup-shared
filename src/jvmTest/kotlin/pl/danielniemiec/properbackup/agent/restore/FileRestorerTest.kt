package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class FileRestorerTest {

    private val restorer = FileRestorer()

    @Test
    fun `restores file with correct SHA-256`(@TempDir tmpDir: File) {
        val content = "hello world".toByteArray()
        val sha256 = sha256Hex(content)
        val target = File(tmpDir, "test.txt")

        val result = restorer.restoreFile(
            targetPath = target,
            sourceStream = ByteArrayInputStream(content),
            expectedSha256 = sha256,
            sizeBytes = content.size.toLong(),
        )

        assertTrue(result is FileRestorer.RestoreResult.Success)
        val success = result as FileRestorer.RestoreResult.Success
        assertEquals(sha256, success.sha256)
        assertEquals(content.size.toLong(), success.bytesWritten)
        assertTrue(target.exists())
        assertEquals("hello world", target.readText())
    }

    @Test
    fun `fails on SHA-256 mismatch`(@TempDir tmpDir: File) {
        val content = "hello world".toByteArray()
        val target = File(tmpDir, "test2.txt")

        val result = restorer.restoreFile(
            targetPath = target,
            sourceStream = ByteArrayInputStream(content),
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = content.size.toLong(),
        )

        assertTrue(result is FileRestorer.RestoreResult.ShaVerifyFailed)
        assertFalse(target.exists())
    }

    @Test
    fun `succeeds without SHA-256 check`(@TempDir tmpDir: File) {
        val content = "no hash check".toByteArray()
        val target = File(tmpDir, "test3.txt")

        val result = restorer.restoreFile(
            targetPath = target,
            sourceStream = ByteArrayInputStream(content),
            expectedSha256 = null,
            sizeBytes = null,
        )

        assertTrue(result is FileRestorer.RestoreResult.Success)
        assertTrue(target.exists())
    }

    @Test
    fun `creates parent directories`(@TempDir tmpDir: File) {
        val target = File(tmpDir, "sub/dir/file.txt")
        val content = "nested".toByteArray()

        val result = restorer.restoreFile(
            targetPath = target,
            sourceStream = ByteArrayInputStream(content),
            expectedSha256 = null,
            sizeBytes = null,
        )

        assertTrue(result is FileRestorer.RestoreResult.Success)
        assertTrue(target.exists())
        assertEquals("nested", target.readText())
    }

    @Test
    fun `cleans up tmp file on SHA mismatch`(@TempDir tmpDir: File) {
        val content = "data".toByteArray()
        val target = File(tmpDir, "clean.txt")

        restorer.restoreFile(
            targetPath = target,
            sourceStream = ByteArrayInputStream(content),
            expectedSha256 = "bad_hash",
            sizeBytes = null,
        )

        val tmpFile = File(tmpDir, "clean.txt.properbackup-tmp")
        assertFalse(tmpFile.exists())
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
