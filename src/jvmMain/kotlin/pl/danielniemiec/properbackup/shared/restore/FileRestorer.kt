package pl.danielniemiec.properbackup.shared.restore

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * HR-7: Idempotent per-file restore.
 * download(sha256) → write to .tmp → verify sha256 == expected → atomic rename .tmp → final path
 */
class FileRestorer(
    private val downloadChunk: (sha256: String) -> InputStream,
) {
    data class RestoreResult(
        val path: String,
        val success: Boolean,
        val sha256Verified: Boolean = false,
        val bytesWritten: Long = 0,
        val error: String? = null,
    )

    /**
     * Restore a single file: download, verify sha256, atomic write.
     * Idempotent: if file already exists with correct sha256, skip.
     */
    fun restore(operation: FileOperation, targetDir: Path): RestoreResult {
        val targetPath = targetDir.resolve(operation.path)
        val expectedSha256 = operation.sha256Target
            ?: return RestoreResult(operation.path, false, error = "No target sha256")

        // Idempotent: check if already correct
        if (Files.exists(targetPath)) {
            val existingSha = computeSha256(targetPath)
            if (existingSha == expectedSha256) {
                return RestoreResult(operation.path, true, sha256Verified = true, bytesWritten = 0)
            }
        }

        // Ensure parent dirs exist
        targetPath.parent?.let { Files.createDirectories(it) }

        // Write to temp file
        val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.properbackup.tmp")
        try {
            var bytesWritten = 0L
            downloadChunk(expectedSha256).use { input ->
                Files.newOutputStream(tmpPath).use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                    }
                }
            }

            // Verify sha256
            val actualSha = computeSha256(tmpPath)
            if (actualSha != expectedSha256) {
                Files.deleteIfExists(tmpPath)
                return RestoreResult(operation.path, false, error = "SHA256 mismatch: expected=$expectedSha256, got=$actualSha")
            }

            // Atomic rename
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return RestoreResult(operation.path, true, sha256Verified = true, bytesWritten = bytesWritten)
        } catch (e: Exception) {
            Files.deleteIfExists(tmpPath)
            return RestoreResult(operation.path, false, error = e.message)
        }
    }

    private fun computeSha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
