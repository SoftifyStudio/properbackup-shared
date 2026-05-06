package pl.danielniemiec.properbackup.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Arrays

class KeyDerivationTest {

    @Test
    fun deriveIsDeterministic() {
        val salt = ByteArray(16) { 0x55.toByte() }
        val k1 = KeyDerivation.derive("haslo123", salt)
        val k2 = KeyDerivation.derive("haslo123", salt)
        assertArrayEquals(k1.encoded, k2.encoded, "same password+salt must produce same key")
    }

    @Test
    fun deriveDifferentPasswordsDifferentKeys() {
        val salt = ByteArray(16)
        val k1 = KeyDerivation.derive("haslo123", salt)
        val k2 = KeyDerivation.derive("inne_haslo", salt)
        assertFalse(Arrays.equals(k1.encoded, k2.encoded))
    }

    @Test
    fun deriveDifferentSaltsDifferentKeys() {
        val salt1 = ByteArray(16)
        val salt2 = ByteArray(16) { 0xFF.toByte() }
        val k1 = KeyDerivation.derive("haslo123", salt1)
        val k2 = KeyDerivation.derive("haslo123", salt2)
        assertFalse(Arrays.equals(k1.encoded, k2.encoded))
    }

    @Test
    fun deriveProduces256BitKey() {
        val salt = ByteArray(16)
        val key = KeyDerivation.derive("test", salt)
        assertEquals(32, key.encoded.size, "key must be 256 bits = 32 bytes")
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun deriveRejectsBadSaltLength() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.derive("pass", ByteArray(8))
        }
    }
}
