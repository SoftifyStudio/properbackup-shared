package pl.danielniemiec.properbackup.core.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.Random

class Base62Test {

    @Test
    fun emptyInputRoundTrips() {
        assertEquals("", Base62.encode(ByteArray(0)))
        assertContentEquals(ByteArray(0), Base62.decode(""))
    }

    @Test
    fun knownVectors() {
        assertEquals("0", Base62.encode(byteArrayOf(0)))
        assertEquals("00", Base62.encode(byteArrayOf(0, 0)))
        assertEquals("47", Base62.encode(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun randomRoundTrip1000() {
        val r = Random(42)
        repeat(1000) { i ->
            val len = 1 + r.nextInt(32)
            val data = ByteArray(len).also { r.nextBytes(it) }
            val enc = Base62.encode(data)
            val dec = Base62.decode(enc)
            assertContentEquals(data, dec, "round-trip mismatch for run $i")
        }
    }

    @Test
    fun leadingZerosPreserved() {
        val data = byteArrayOf(0, 0, 0, 1, 2, 3)
        val enc = Base62.encode(data)
        assertTrue(enc.startsWith("000"))
        assertContentEquals(data, Base62.decode(enc))
    }

    @Test
    fun invalidCharacterThrows() {
        assertFailsWith<IllegalArgumentException> { Base62.decode("abc!") }
        assertFailsWith<IllegalArgumentException> { Base62.decode("ąż") }
    }

    @Test
    fun fixedWidthPadsShortInputs() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val id = Base62.encodeFixedWidth(data, 8)
        assertEquals(8, id.length)
        val decoded = Base62.decode(id)
        val offset = decoded.size - data.size
        assertTrue(offset >= 0)
        for (i in data.indices) {
            assertEquals(data[i], decoded[offset + i])
        }
    }

    @Test
    fun fixedWidthRejectsOverflow() {
        val data = ByteArray(16) { 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> {
            Base62.encodeFixedWidth(data, 8)
        }
    }
}
