package pl.danielniemiec.properbackup.core.restore

import java.security.MessageDigest

/**
 * Computes the diff between a target snapshot and the current local filesystem.
 * Used for DRY RUN preview (HR-4) and generating the operation plan.
 */
object SnapshotDiff {

    data class DiffResult(
        val filesToRestore: List<FileEntry>,
        val filesToDelete: List<FileEntry>,
        val filesUnchanged: List<FileEntry>,
        val criticalProtected: List<String>,
        val totalRestoreBytes: Long,
        val totalDeleteBytes: Long,
    )

    data class FileEntry(
        val path: String,
        val sha256: String?,
        val sizeBytes: Long,
    )

    fun compute(
        snapshotFiles: Map<String, FileEntry>,
        localFiles: Map<String, FileEntry>,
    ): DiffResult {
        val toRestore = mutableListOf<FileEntry>()
        val toDelete = mutableListOf<FileEntry>()
        val unchanged = mutableListOf<FileEntry>()
        val criticalProtected = mutableListOf<String>()

        for ((path, snapshotEntry) in snapshotFiles) {
            val localEntry = localFiles[path]
            if (localEntry == null) {
                toRestore.add(snapshotEntry)
            } else if (snapshotEntry.sha256 != null && snapshotEntry.sha256 != localEntry.sha256) {
                toRestore.add(snapshotEntry)
            } else {
                unchanged.add(snapshotEntry)
            }
        }

        for ((path, localEntry) in localFiles) {
            if (!snapshotFiles.containsKey(path)) {
                if (CriticalPathsGuard.isCritical(path)) {
                    criticalProtected.add(path)
                } else {
                    toDelete.add(localEntry)
                }
            }
        }

        return DiffResult(
            filesToRestore = toRestore,
            filesToDelete = toDelete,
            filesUnchanged = unchanged,
            criticalProtected = criticalProtected,
            totalRestoreBytes = toRestore.sumOf { it.sizeBytes },
            totalDeleteBytes = toDelete.sumOf { it.sizeBytes },
        )
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    fun pathHash(path: String): String = sha256Hex(path.toByteArray(Charsets.UTF_8))
}
