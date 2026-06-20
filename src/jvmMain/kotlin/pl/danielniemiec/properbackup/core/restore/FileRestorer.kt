package pl.danielniemiec.properbackup.core.restore

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Idempotent per-file restore: download → .tmp → verify SHA-256 → atomic rename.
 * On cancel, files are moved to .quarantine/<recovery_id>/ for rollback (HR-9).
 */
class FileRestorer(private val quarantineBase: Path) {

    fun restoreFile(
        targetPath: Path,
        dataStream: InputStream,
        expectedSha256: String?,
        recoverySessionId: String,
    ): RestoreResult {
        val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.tmp.recovery")
        try {
            Files.createDirectories(targetPath.parent)
            if (Files.exists(targetPath)) {
                quarantineExisting(targetPath, recoverySessionId)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            Files.newOutputStream(tmpPath).use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (dataStream.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    bytesWritten += n
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && actualSha256 != expectedSha256) {
                Files.deleteIfExists(tmpPath)
                return RestoreResult.HashMismatch(expectedSha256, actualSha256)
            }
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return RestoreResult.Success(bytesWritten)
        } catch (e: Exception) {
            Files.deleteIfExists(tmpPath)
            return RestoreResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun quarantineExisting(filePath: Path, recoverySessionId: String): Path {
        val quarantineDir = quarantineBase.resolve(recoverySessionId)
        Files.createDirectories(quarantineDir)
        val relativePath = filePath.toAbsolutePath().toString().replace("/", "_").removePrefix("_")
        val quarantinePath = quarantineDir.resolve(relativePath)
        Files.move(filePath, quarantinePath, StandardCopyOption.REPLACE_EXISTING)
        return quarantinePath
    }

    fun rollbackFromQuarantine(recoverySessionId: String): Int {
        val quarantineDir = quarantineBase.resolve(recoverySessionId)
        if (!Files.exists(quarantineDir)) return 0
        var restored = 0
        Files.walk(quarantineDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { quarantinedFile ->
                val originalPath = quarantinedFile.fileName.toString().replace("_", "/")
                val target = Path.of("/$originalPath")
                try {
                    Files.createDirectories(target.parent)
                    Files.move(quarantinedFile, target, StandardCopyOption.REPLACE_EXISTING)
                    restored++
                } catch (_: Exception) { }
            }
        }
        return restored
    }

    fun cleanupQuarantine(recoverySessionId: String) {
        val quarantineDir = quarantineBase.resolve(recoverySessionId)
        if (Files.exists(quarantineDir)) {
            quarantineDir.toFile().deleteRecursively()
        }
    }

    sealed class RestoreResult {
        data class Success(val bytesWritten: Long) : RestoreResult()
        data class HashMismatch(val expected: String, val actual: String) : RestoreResult()
        data class Error(val message: String) : RestoreResult()
    }
}
