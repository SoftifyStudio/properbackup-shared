package pl.danielniemiec.properbackup.shared.restore

/**
 * DRY RUN computation per user-facing-recovery-spec.md §5.5 step 5.
 * Computes diff between target snapshot manifest and local filesystem state.
 */
data class DiffResult(
    val filesToRestore: List<FileOperation>,
    val filesToDelete: List<FileOperation>,
    val filesUnchanged: Int,
    val criticalProtected: Int,
    val totalRestoreBytes: Long,
    val totalDeleteBytes: Long,
)

data class FileOperation(
    val path: String,
    val pathHash: String,
    val sha256Target: String? = null,
    val sha256Local: String? = null,
    val sizeBytes: Long = 0,
    val type: OperationType,
)

enum class OperationType {
    RESTORE,
    DELETE,
    SKIP,
}

data class SnapshotManifestEntry(
    val pathId: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class LocalFileEntry(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

class SnapshotDiff(
    private val criticalPaths: CriticalPathsGuard = CriticalPathsGuard,
) {
    /**
     * Compute the diff between snapshot and local state.
     * @param snapshotEntries files in the target snapshot (what we want the system to look like)
     * @param localEntries files currently on the local filesystem
     * @param backupRoots the configured backup root directories (for critical path filtering)
     */
    fun compute(
        snapshotEntries: List<SnapshotManifestEntry>,
        localEntries: List<LocalFileEntry>,
        backupRoots: List<String> = emptyList(),
    ): DiffResult {
        val localByPath = localEntries.associateBy { it.path }
        val snapshotByPath = snapshotEntries.associateBy { it.path }

        val filesToRestore = mutableListOf<FileOperation>()
        val filesToDelete = mutableListOf<FileOperation>()
        var filesUnchanged = 0
        var criticalProtected = 0

        // Files in snapshot: restore if missing or different sha256
        for (entry in snapshotEntries) {
            if (criticalPaths.isProtected(entry.path, backupRoots)) {
                criticalProtected++
                continue
            }
            val local = localByPath[entry.path]
            if (local == null || local.sha256 != entry.sha256) {
                filesToRestore.add(FileOperation(
                    path = entry.path,
                    pathHash = sha256Hash(entry.path),
                    sha256Target = entry.sha256,
                    sha256Local = local?.sha256,
                    sizeBytes = entry.sizeBytes,
                    type = OperationType.RESTORE,
                ))
            } else {
                filesUnchanged++
            }
        }

        // Files on local that are NOT in snapshot: delete
        for (local in localEntries) {
            if (snapshotByPath.containsKey(local.path)) continue
            if (criticalPaths.isProtected(local.path, backupRoots)) {
                criticalProtected++
                continue
            }
            filesToDelete.add(FileOperation(
                path = local.path,
                pathHash = sha256Hash(local.path),
                sha256Local = local.sha256,
                sizeBytes = local.sizeBytes,
                type = OperationType.DELETE,
            ))
        }

        return DiffResult(
            filesToRestore = filesToRestore,
            filesToDelete = filesToDelete,
            filesUnchanged = filesUnchanged,
            criticalProtected = criticalProtected,
            totalRestoreBytes = filesToRestore.sumOf { it.sizeBytes },
            totalDeleteBytes = filesToDelete.sumOf { it.sizeBytes },
        )
    }

    private fun sha256Hash(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
