package pl.danielniemiec.properbackup.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class ProperCryptoEdgeCaseTest {

    private val password = "edge_case_password_2026"

    @Test
    fun decryptTruncatedArchiveThrows() {
        val enc = encrypt(password, "valid payload".toByteArray())
        val truncated = enc.copyOfRange(0, enc.size / 2)
        assertThrows(Exception::class.java) {
            decrypt(password, truncated)
        }
    }

    @Test
    fun decryptTamperedCiphertextThrows() {
        val enc = encrypt(password, "original data".toByteArray())
        // Flip a byte in the encrypted frame area (after 49-byte header)
        if (enc.size > 60) {
            enc[55] = (enc[55].toInt() xor 0xFF).toByte()
        }
        assertThrows(Exception::class.java) {
            decrypt(password, enc)
        }
    }

    @Test
    fun decryptTamperedHeaderAuthTagThrows() {
        val enc = encrypt(password, "data".toByteArray())
        // Tamper with authTag (bytes 33..48 in header)
        enc[40] = (enc[40].toInt() xor 0xFF).toByte()
        assertThrows(ProperCrypto.BadPasswordException::class.java) {
            decrypt(password, enc)
        }
    }

    @Test
    fun encryptDecryptLargeMultiChunkPayload() {
        val size = ProperCrypto.CHUNK_SIZE * 5 + 7919
        val plain = randomBytes(size)
        val recovered = decrypt(password, encrypt(password, plain))
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun encryptDecryptSingleBytePayload() {
        val plain = byteArrayOf(42)
        val recovered = decrypt(password, encrypt(password, plain))
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun encryptedSizeAlwaysLargerThanPlain() {
        for (size in listOf(0, 1, 100, ProperCrypto.CHUNK_SIZE, ProperCrypto.CHUNK_SIZE + 1)) {
            val plain = randomBytes(size)
            val enc = encrypt(password, plain)
            assertTrue(enc.size >= plain.size + HeaderCodec.HEADER_SIZE, "encrypted must be larger for size=$size")
        }
    }

    @Test
    fun encryptDecryptUnicodePassword() {
        val unicodePwd = "Zażółć gęślą jaźń 🔐"
        val plain = "dane do zaszyfrowania".toByteArray()
        val recovered = decrypt(unicodePwd, encrypt(unicodePwd, plain))
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun headerPreservedAcrossEncryptDecrypt() {
        val plain = "test header".toByteArray()
        val enc = encrypt(password, plain)
        val header = HeaderCodec.read(ByteArrayInputStream(enc))
        assertEquals(16, header.salt.size)
        assertEquals(12, header.nonce.size)
        assertEquals(16, header.authTag.size)
    }

    private fun encrypt(pwd: String, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.encrypt(pwd.toCharArray(), ByteArrayInputStream(plain), out)
        return out.toByteArray()
    }

    private fun decrypt(pwd: String, enc: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.decrypt(pwd.toCharArray(), ByteArrayInputStream(enc), out)
        return out.toByteArray()
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
