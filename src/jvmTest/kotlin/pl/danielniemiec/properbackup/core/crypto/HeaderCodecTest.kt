package pl.danielniemiec.properbackup.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HeaderCodecTest {

    @Test
    fun roundTripWriteThenReadFieldsSurvive() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val authTag = ByteArray(16) { (it + 200).toByte() }
        val out = ByteArrayOutputStream()
        HeaderCodec.write(out, salt, nonce, authTag)
        assertEquals(HeaderCodec.HEADER_SIZE, out.size(), "header must be exactly 49 bytes")
        val h = HeaderCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(salt, h.salt)
        assertArrayEquals(nonce, h.nonce)
        assertArrayEquals(authTag, h.authTag)
    }

    @Test
    fun readWrongMagicThrows() {
        val bad = ByteArray(49) { 0 }
        assertThrows(Exception::class.java) {
            HeaderCodec.read(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun readWrongVersionThrows() {
        val data = ByteArray(49)
        System.arraycopy(HeaderCodec.MAGIC, 0, data, 0, 4)
        data[4] = 0x02
        assertThrows(Exception::class.java) {
            HeaderCodec.read(ByteArrayInputStream(data))
        }
    }

    @Test
    fun writeRejectsWrongSaltLength() {
        assertThrows(IllegalArgumentException::class.java) {
            HeaderCodec.write(ByteArrayOutputStream(), ByteArray(10), ByteArray(12), ByteArray(16))
        }
    }

    @Test
    fun writeRejectsWrongNonceLength() {
        assertThrows(IllegalArgumentException::class.java) {
            HeaderCodec.write(ByteArrayOutputStream(), ByteArray(16), ByteArray(8), ByteArray(16))
        }
    }

    @Test
    fun headerBytesStartWithPROP() {
        val out = ByteArrayOutputStream()
        HeaderCodec.write(out, ByteArray(16), ByteArray(12), ByteArray(16))
        val bytes = out.toByteArray()
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('R'.code.toByte(), bytes[1])
        assertEquals('O'.code.toByte(), bytes[2])
        assertEquals('P'.code.toByte(), bytes[3])
        assertEquals(0x01, bytes[4].toInt() and 0xFF)
    }
}
