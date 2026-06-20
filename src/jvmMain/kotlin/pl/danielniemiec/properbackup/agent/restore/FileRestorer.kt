package pl.danielniemiec.properbackup.agent.restore

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FileRestorer {

    @Suppress("UNUSED_PARAMETER")
    fun restoreFile(
        targetPath: File,
        sourceStream: InputStream,
        expectedSha256: String?,
        sizeBytes: Long?,
    ): RestoreResult {
        val tmpFile = File(targetPath.parentFile, "${targetPath.name}.properbackup-tmp")
        try {
            targetPath.parentFile?.mkdirs()

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            tmpFile.outputStream().buffered().use { out ->
                sourceStream.use { input ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        bytesWritten += n
                    }
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            if (expectedSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                tmpFile.delete()
                return RestoreResult.ShaVerifyFailed(
                    expected = expectedSha256,
                    actual = actualSha256,
                    bytesWritten = bytesWritten,
                )
            }

            Files.move(tmpFile.toPath(), targetPath.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            return RestoreResult.Success(
                sha256 = actualSha256,
                bytesWritten = bytesWritten,
            )
        } catch (e: Exception) {
            tmpFile.delete()
            return RestoreResult.Error(e.message ?: "unknown error")
        }
    }

    sealed class RestoreResult {
        data class Success(val sha256: String, val bytesWritten: Long) : RestoreResult()
        data class ShaVerifyFailed(val expected: String, val actual: String, val bytesWritten: Long) : RestoreResult()
        data class Error(val message: String) : RestoreResult()
    }
}
