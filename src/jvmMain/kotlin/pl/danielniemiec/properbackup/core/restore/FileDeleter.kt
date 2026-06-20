package pl.danielniemiec.properbackup.core.restore

import java.nio.file.Files
import java.nio.file.Path

/**
 * Safe file deletion with CriticalPathsGuard check and quarantine backup.
 * Files are quarantined (not permanently deleted) until recovery completes (HR-9).
 */
class FileDeleter(private val fileRestorer: FileRestorer) {

    fun deleteFile(path: Path, recoverySessionId: String): DeleteResult {
        val absolutePath = path.toAbsolutePath().toString()
        if (CriticalPathsGuard.isCritical(absolutePath)) {
            return DeleteResult.ProtectedCritical(absolutePath)
        }
        if (!Files.exists(path)) {
            return DeleteResult.AlreadyGone
        }
        return try {
            fileRestorer.quarantineExisting(path, recoverySessionId)
            DeleteResult.Success
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    sealed class DeleteResult {
        data object Success : DeleteResult()
        data object AlreadyGone : DeleteResult()
        data class ProtectedCritical(val path: String) : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }
}
