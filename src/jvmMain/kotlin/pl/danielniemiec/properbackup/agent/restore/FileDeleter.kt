package pl.danielniemiec.properbackup.agent.restore

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileDeleter(
    private val quarantineBaseDir: File,
    private val guard: CriticalPathsGuard,
) {

    fun deleteFile(
        filePath: File,
        recoveryId: String,
    ): DeleteResult {
        if (guard.isCritical(filePath.absolutePath)) {
            return DeleteResult.Blocked("Critical path protected: ${filePath.absolutePath}")
        }

        if (!filePath.exists()) {
            return DeleteResult.AlreadyGone
        }

        val quarantineDir = File(quarantineBaseDir, ".quarantine/$recoveryId")
        quarantineDir.mkdirs()
        val quarantinePath = File(quarantineDir, filePath.name + ".${System.currentTimeMillis()}")

        try {
            Files.move(
                filePath.toPath(),
                quarantinePath.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return DeleteResult.MovedToQuarantine(quarantinePath.absolutePath)
        } catch (e: Exception) {
            return DeleteResult.Error(e.message ?: "unknown error")
        }
    }

    sealed class DeleteResult {
        data class MovedToQuarantine(val quarantinePath: String) : DeleteResult()
        data object AlreadyGone : DeleteResult()
        data class Blocked(val reason: String) : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }
}
