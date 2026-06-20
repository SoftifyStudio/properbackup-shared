package pl.danielniemiec.properbackup.agent.restore

import java.security.MessageDigest

data class FileDiffEntry(
    val path: String,
    val pathHash: String,
    val action: DiffAction,
    val sha256Target: String?,
    val sizeBytes: Long?,
) {
    enum class DiffAction { RESTORE, DELETE, UNCHANGED }
}

class SnapshotDiff {

    fun compute(
        snapshotFiles: Map<String, SnapshotFileInfo>,
        currentFiles: Map<String, CurrentFileInfo>,
    ): DiffResult {
        val entries = mutableListOf<FileDiffEntry>()
        var filesToRestore = 0
        var filesToDelete = 0
        var filesUnchanged = 0
        var totalRestoreBytes = 0L

        for ((path, snapInfo) in snapshotFiles) {
            val currentInfo = currentFiles[path]
            val pathHash = sha256(path)

            if (currentInfo == null) {
                entries.add(FileDiffEntry(path, pathHash, FileDiffEntry.DiffAction.RESTORE, snapInfo.sha256, snapInfo.sizeBytes))
                filesToRestore++
                totalRestoreBytes += snapInfo.sizeBytes ?: 0
            } else if (currentInfo.sha256 != null && snapInfo.sha256 != null && currentInfo.sha256.equals(snapInfo.sha256, ignoreCase = true)) {
                entries.add(FileDiffEntry(path, pathHash, FileDiffEntry.DiffAction.UNCHANGED, snapInfo.sha256, snapInfo.sizeBytes))
                filesUnchanged++
            } else {
                entries.add(FileDiffEntry(path, pathHash, FileDiffEntry.DiffAction.RESTORE, snapInfo.sha256, snapInfo.sizeBytes))
                filesToRestore++
                totalRestoreBytes += snapInfo.sizeBytes ?: 0
            }
        }

        for ((path, _) in currentFiles) {
            if (path !in snapshotFiles) {
                val pathHash = sha256(path)
                entries.add(FileDiffEntry(path, pathHash, FileDiffEntry.DiffAction.DELETE, null, null))
                filesToDelete++
            }
        }

        return DiffResult(
            entries = entries,
            filesToRestore = filesToRestore,
            filesToDelete = filesToDelete,
            filesUnchanged = filesUnchanged,
            totalRestoreBytes = totalRestoreBytes,
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class SnapshotFileInfo(val sha256: String?, val sizeBytes: Long?)
data class CurrentFileInfo(val sha256: String?)

data class DiffResult(
    val entries: List<FileDiffEntry>,
    val filesToRestore: Int,
    val filesToDelete: Int,
    val filesUnchanged: Int,
    val totalRestoreBytes: Long,
) {
    val sampleRestore: List<String>
        get() = entries.filter { it.action == FileDiffEntry.DiffAction.RESTORE }.take(20).map { it.path }

    val sampleDelete: List<String>
        get() = entries.filter { it.action == FileDiffEntry.DiffAction.DELETE }.take(20).map { it.path }
}
