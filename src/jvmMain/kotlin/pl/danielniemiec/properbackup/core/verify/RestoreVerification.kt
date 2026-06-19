package pl.danielniemiec.properbackup.core.verify

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Real auto-test restore: proves a sealed backup is actually recoverable,
 * not merely that some bytes were stored.
 *
 * The check decrypts the archive with the owner's password — exercising
 * AES-GCM authentication on every chunk (cryptographic integrity) — and then
 * fully unpacks the resulting tar.gz, reading every entry end to end
 * (gzip CRC + tar framing = structural integrity). If any byte of the
 * ciphertext was corrupted or truncated, GCM authentication fails; if the
 * container itself is damaged, unpacking fails.
 *
 * This must run where the password lives (the agent): the buffer is
 * zero-knowledge and holds only ciphertext, so it cannot perform a real
 * restore — only a container-format sanity check.
 */
object RestoreVerification {

    const val MANIFEST_ENTRY = ".properbackup-idx"

    private const val READ_BUFFER = 64 * 1024

    enum class FailureKind { WRONG_PASSWORD, CIPHERTEXT_CORRUPT, ARCHIVE_CORRUPT }

    data class Report(
        val ok: Boolean,
        val isTombstone: Boolean,
        val fileCount: Int,
        val totalBytes: Long,
        val hasManifest: Boolean,
        val detail: String,
        val failureKind: FailureKind? = null,
    )

    /**
     * Verify a sealed (encrypted) backup object.
     *
     * @param password the owner's encryption password (never leaves the agent).
     * @param encrypted the raw bytes of the sealed object, as stored remotely.
     */
    @JvmStatic
    fun verify(password: CharArray, encrypted: InputStream): Report {
        val input = if (encrypted.markSupported()) encrypted else BufferedInputStream(encrypted)
        input.mark(1)
        if (input.read() == -1) {
            return Report(
                ok = true,
                isTombstone = true,
                fileCount = 0,
                totalBytes = 0,
                hasManifest = false,
                detail = "Empty object (deletion tombstone) — nothing to restore",
            )
        }
        input.reset()

        val plain = Files.createTempFile("properbackup-verify", ".tar.gz")
        try {
            try {
                Files.newOutputStream(plain).use { out ->
                    ProperCrypto.decrypt(password, input, out)
                }
            } catch (e: ProperCrypto.BadPasswordException) {
                return fail(FailureKind.WRONG_PASSWORD, "Wrong password — backup cannot be decrypted")
            } catch (e: Exception) {
                // AEADBadTagException / EOF / malformed header → ciphertext tampered or truncated
                return fail(FailureKind.CIPHERTEXT_CORRUPT, "Ciphertext failed integrity check: ${e.message}")
            }

            var fileCount = 0
            var totalBytes = 0L
            var hasManifest = false
            try {
                Files.newInputStream(plain).use { fin ->
                    GzipCompressorInputStream(BufferedInputStream(fin)).use { gz ->
                        TarArchiveInputStream(gz).use { tar ->
                            val buf = ByteArray(READ_BUFFER)
                            var entry = tar.nextEntry
                            while (entry != null) {
                                if (entry.name == MANIFEST_ENTRY) hasManifest = true
                                var entryBytes = 0L
                                while (true) {
                                    val r = tar.read(buf)
                                    if (r == -1) break
                                    entryBytes += r
                                }
                                if (!entry.isDirectory && entry.name != MANIFEST_ENTRY) {
                                    fileCount++
                                    totalBytes += entryBytes
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                return fail(FailureKind.ARCHIVE_CORRUPT, "Archive corrupted while unpacking: ${e.message}")
            }

            return Report(
                ok = true,
                isTombstone = false,
                fileCount = fileCount,
                totalBytes = totalBytes,
                hasManifest = hasManifest,
                detail = "Restore verified: $fileCount file(s), $totalBytes byte(s) unpacked",
            )
        } finally {
            Files.deleteIfExists(plain)
        }
    }

    private fun fail(kind: FailureKind, detail: String): Report =
        Report(
            ok = false,
            isTombstone = false,
            fileCount = 0,
            totalBytes = 0,
            hasManifest = false,
            detail = detail,
            failureKind = kind,
        )
}
