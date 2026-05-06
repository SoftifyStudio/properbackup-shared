package pl.danielniemiec.properbackup.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays

class ProperCryptoTest {

    private val password = "tajne_haslo_2026"

    @Test
    fun encryptDecryptSmallPayloadRoundTrip() {
        val plain = "Hello, ProperBackup!".toByteArray(StandardCharsets.UTF_8)
        val recovered = encryptThenDecrypt(plain, password)
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun encryptDecryptEmptyPayloadRoundTrip() {
        val recovered = encryptThenDecrypt(ByteArray(0), password)
        assertArrayEquals(ByteArray(0), recovered)
    }

    @Test
    fun encryptDecryptExactlyOneChunkRoundTrip() {
        val plain = randomBytes(ProperCrypto.CHUNK_SIZE)
        val recovered = encryptThenDecrypt(plain, password)
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun encryptDecryptMultipleChunksRoundTrip() {
        val plain = randomBytes(ProperCrypto.CHUNK_SIZE * 3 + 1337)
        val recovered = encryptThenDecrypt(plain, password)
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun decryptWrongPasswordThrowsBadPasswordException() {
        val enc = encrypt("correct_password", "some data".toByteArray(StandardCharsets.UTF_8))
        assertThrows(ProperCrypto.BadPasswordException::class.java) {
            decrypt("wrong_password", enc)
        }
    }

    @Test
    fun twoEncryptsSameDataProduceDifferentCiphertext() {
        val plain = "identical".toByteArray(StandardCharsets.UTF_8)
        val enc1 = encrypt(password, plain)
        val enc2 = encrypt(password, plain)
        assertFalse(Arrays.equals(enc1, enc2))
    }

    @Test
    fun verifyPasswordCorrectPasswordNoException() {
        val enc = encrypt(password, "data".toByteArray(StandardCharsets.UTF_8))
        val header = HeaderCodec.read(ByteArrayInputStream(enc))
        assertDoesNotThrow { ProperCrypto.verifyPassword(password.toCharArray(), header) }
    }

    @Test
    fun verifyPasswordWrongPasswordThrowsBadPasswordException() {
        val enc = encrypt(password, "data".toByteArray(StandardCharsets.UTF_8))
        val header = HeaderCodec.read(ByteArrayInputStream(enc))
        assertThrows(ProperCrypto.BadPasswordException::class.java) {
            ProperCrypto.verifyPassword("wrong".toCharArray(), header)
        }
    }

    @Test
    fun encryptedFileStartsWithPROPHeader() {
        val enc = encrypt(password, "test".toByteArray(StandardCharsets.UTF_8))
        assertEquals('P'.code.toByte(), enc[0])
        assertEquals('R'.code.toByte(), enc[1])
        assertEquals('O'.code.toByte(), enc[2])
        assertEquals('P'.code.toByte(), enc[3])
        assertEquals(0x01, enc[4].toInt() and 0xFF)
    }

    private fun encryptThenDecrypt(plain: ByteArray, pwd: String): ByteArray {
        val enc = encrypt(pwd, plain)
        return decrypt(pwd, enc)
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
