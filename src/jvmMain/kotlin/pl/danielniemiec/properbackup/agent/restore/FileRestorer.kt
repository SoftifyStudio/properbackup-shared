package pl.danielniemiec.properbackup.agent.restore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Atomic file restore: download encrypted bytes → decrypt → write .tmp → verify SHA-256 → rename.
 * Idempotent: re-running on already-restored file is a no-op if SHA-256 matches.
 */
class FileRestorer(
    private val stateStore: RestoreStateStore,
    private val decryptFn: (ByteArray) -> ByteArray,
) {
    fun restore(op: RecoveryOperation, encryptedBytes: ByteArray) {
        val targetPath = Path.of(op.path)

        // Idempotent: if file already exists with correct hash, skip
        if (Files.exists(targetPath) && op.sha256Target != null) {
            val existingHash = sha256(Files.readAllBytes(targetPath))
            if (existingHash == op.sha256Target) {
                op.state = OperationState.DONE
                stateStore.markState(op.id, OperationState.DONE)
                return
            }
        }

        // Phase 1: decrypt
        op.state = OperationState.DOWNLOADING
        stateStore.markState(op.id, OperationState.DOWNLOADING)
        val plaintext: ByteArray
        try {
            plaintext = decryptFn(encryptedBytes)
        } catch (e: Exception) {
            op.state = OperationState.FAILED
            op.error = "Decrypt failed: ${e.message}"
            stateStore.markState(op.id, OperationState.FAILED, op.error)
            throw RestoreException("Decrypt failed for ${op.path}: ${e.message}", e)
        }

        // Phase 2: write to .tmp
        op.state = OperationState.WRITING
        stateStore.markState(op.id, OperationState.WRITING)
        val tmpFile = targetPath.resolveSibling(".recovery-${UUID.randomUUID()}.tmp")
        try {
            Files.createDirectories(targetPath.parent)
            Files.write(tmpFile, plaintext)
        } catch (e: Exception) {
            Files.deleteIfExists(tmpFile)
            op.state = OperationState.FAILED
            op.error = "Write failed: ${e.message}"
            stateStore.markState(op.id, OperationState.FAILED, op.error)
            throw RestoreException("Write failed for ${op.path}: ${e.message}", e)
        }

        // Phase 3: verify SHA-256
        op.state = OperationState.VERIFYING
        stateStore.markState(op.id, OperationState.VERIFYING)
        if (op.sha256Target != null) {
            val writtenHash = sha256(Files.readAllBytes(tmpFile))
            if (writtenHash != op.sha256Target) {
                Files.deleteIfExists(tmpFile)
                op.state = OperationState.FAILED
                op.error = "SHA-256 mismatch: expected=${op.sha256Target}, got=$writtenHash"
                stateStore.markState(op.id, OperationState.FAILED, op.error)
                throw RestoreException("SHA-256 mismatch for ${op.path}: expected=${op.sha256Target}, got=$writtenHash")
            }
        }

        // Phase 4: atomic rename
        Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        op.state = OperationState.DONE
        stateStore.markState(op.id, OperationState.DONE)
    }

    companion object {
        fun sha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
