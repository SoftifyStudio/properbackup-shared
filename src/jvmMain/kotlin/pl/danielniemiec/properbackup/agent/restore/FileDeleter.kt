package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Quarantine-based file deletion (HR-9).
 * Files are moved to .quarantine/<recoverySessionId>/<pathHash> with metadata for rollback.
 * 30-day TTL before permanent deletion.
 */
class FileDeleter(
    private val quarantineBaseDir: Path,
    private val recoverySessionId: String,
    private val stateStore: RestoreStateStore,
) {
    private val gson = Gson()
    private val quarantineDir: Path = quarantineBaseDir.resolve(recoverySessionId)

    fun delete(op: RecoveryOperation) {
        val filePath = Path.of(op.path)

        // Idempotent: if file doesn't exist, mark done
        if (!Files.exists(filePath)) {
            op.state = OperationState.DONE
            stateStore.markState(op.id, OperationState.DONE)
            return
        }

        op.state = OperationState.DOWNLOADING // phase: moving to quarantine
        stateStore.markState(op.id, OperationState.DOWNLOADING)

        try {
            Files.createDirectories(quarantineDir)
            val pathHash = sha256(op.path.toByteArray()).substring(0, 32)
            val quarantinePath = quarantineDir.resolve(pathHash)

            Files.move(filePath, quarantinePath, StandardCopyOption.REPLACE_EXISTING)

            // Save metadata for rollback
            val meta = mapOf("originalPath" to op.path, "sha256" to (op.sha256Local ?: ""))
            Files.writeString(quarantineDir.resolve("$pathHash.meta"), gson.toJson(meta))

            op.state = OperationState.DONE
            stateStore.markState(op.id, OperationState.DONE)
        } catch (e: Exception) {
            op.state = OperationState.FAILED
            op.error = "Quarantine failed: ${e.message}"
            stateStore.markState(op.id, OperationState.FAILED, op.error)
            throw RestoreException("Quarantine failed for ${op.path}: ${e.message}", e)
        }
    }

    /**
     * Rollback: restore all quarantined files to their original paths.
     */
    fun rollback() {
        if (!Files.exists(quarantineDir)) return
        try {
            Files.list(quarantineDir)
                .filter { it.toString().endsWith(".meta") }
                .forEach { metaFile ->
                    try {
                        val meta = gson.fromJson(Files.readString(metaFile), Map::class.java)
                        val originalPath = meta["originalPath"] as? String ?: return@forEach
                        val dataFile = metaFile.resolveSibling(metaFile.fileName.toString().removeSuffix(".meta"))
                        if (Files.exists(dataFile)) {
                            Files.createDirectories(Path.of(originalPath).parent)
                            Files.move(dataFile, Path.of(originalPath), StandardCopyOption.REPLACE_EXISTING)
                        }
                        Files.deleteIfExists(metaFile)
                    } catch (e: Exception) {
                        System.err.println("[restore] rollback error for $metaFile: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            System.err.println("[restore] rollback error: ${e.message}")
        }
    }

    companion object {
        fun sha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
