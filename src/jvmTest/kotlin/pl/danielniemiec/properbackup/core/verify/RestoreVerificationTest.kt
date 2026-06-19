package pl.danielniemiec.properbackup.core.verify

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.agent.packer.TarGzPacker
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

class RestoreVerificationTest {

    private val password = "tajne_haslo_2026"

    @Test
    fun verifiesHealthyBackupAndCountsFiles() {
        val root = makeTree(
            "notes.txt" to "hello".toByteArray(),
            "data/big.bin" to randomBytes(200_000),
            "data/empty.txt" to ByteArray(0),
        )
        val enc = sealTree(root, manifest = """{"paths":["notes.txt"]}""".toByteArray())

        val report = RestoreVerification.verify(password.toCharArray(), ByteArrayInputStream(enc))

        assertTrue(report.ok, report.detail)
        assertFalse(report.isTombstone)
        assertTrue(report.hasManifest)
        assertEquals(3, report.fileCount)
        assertEquals(200_005L, report.totalBytes)
    }

    @Test
    fun roundTripUnpacksIdenticalContent() {
        val payload = randomBytes(ProperCrypto.CHUNK_SIZE * 2 + 123)
        val root = makeTree("world/region.bin" to payload)
        val enc = sealTree(root, manifest = null)

        // verify() must pass...
        assertTrue(RestoreVerification.verify(password.toCharArray(), ByteArrayInputStream(enc)).ok)

        // ...and the actual restored bytes must be identical to the source.
        val restored = decryptAndExtract(enc)["world/region.bin"]
        assertEquals(payload.toList(), restored?.toList())
    }

    @Test
    fun wrongPasswordReported() {
        val enc = sealTree(makeTree("a.txt" to "x".toByteArray()), manifest = null)
        val report = RestoreVerification.verify("zle_haslo".toCharArray(), ByteArrayInputStream(enc))
        assertFalse(report.ok)
        assertEquals(RestoreVerification.FailureKind.WRONG_PASSWORD, report.failureKind)
    }

    @Test
    fun corruptedCiphertextReported() {
        val enc = sealTree(makeTree("a.bin" to randomBytes(100_000)), manifest = null)
        // Flip a byte well past the 49-byte header, inside an encrypted frame.
        enc[enc.size / 2] = (enc[enc.size / 2].toInt() xor 0xFF).toByte()
        val report = RestoreVerification.verify(password.toCharArray(), ByteArrayInputStream(enc))
        assertFalse(report.ok)
        assertEquals(RestoreVerification.FailureKind.CIPHERTEXT_CORRUPT, report.failureKind)
    }

    @Test
    fun truncatedCiphertextReported() {
        val enc = sealTree(makeTree("a.bin" to randomBytes(100_000)), manifest = null)
        val truncated = enc.copyOf(enc.size - 5000)
        val report = RestoreVerification.verify(password.toCharArray(), ByteArrayInputStream(truncated))
        assertFalse(report.ok)
    }

    @Test
    fun emptyObjectTreatedAsTombstone() {
        val report = RestoreVerification.verify(password.toCharArray(), ByteArrayInputStream(ByteArray(0)))
        assertTrue(report.ok)
        assertTrue(report.isTombstone)
        assertEquals(0, report.fileCount)
    }

    // --- helpers ---

    private fun makeTree(vararg files: Pair<String, ByteArray>): Path {
        val root = Files.createTempDirectory("properbackup-src")
        for ((rel, bytes) in files) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.write(p, bytes)
        }
        return root
    }

    private fun sealTree(root: Path, manifest: ByteArray?): ByteArray {
        val files = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().toList()
        }
        val tarGz = TarGzPacker.packToBytes(root, files, manifest)
        val out = ByteArrayOutputStream()
        ProperCrypto.encrypt(password.toCharArray(), ByteArrayInputStream(tarGz), out)
        return out.toByteArray()
    }

    private fun decryptAndExtract(enc: ByteArray): Map<String, ByteArray> {
        val plain = ByteArrayOutputStream()
        ProperCrypto.decrypt(password.toCharArray(), ByteArrayInputStream(enc), plain)
        val result = HashMap<String, ByteArray>()
        GzipCompressorInputStream(ByteArrayInputStream(plain.toByteArray())).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        result[entry.name] = tar.readBytes()
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return result
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    @Suppress("unused")
    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)
}
