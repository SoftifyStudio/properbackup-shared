package pl.danielniemiec.properbackup.core.restore

import java.io.InputStream

/**
 * Abstraction for file content retrieval during restore.
 * VPS agent implements this by downloading from buffer;
 * MC plugin by reading from local backup storage.
 * Cross-host parity (HR-10): RestoreOrchestrator uses this interface.
 */
interface RestoreFileProvider {
    fun openFileStream(path: String, sha256: String?): InputStream?
}
