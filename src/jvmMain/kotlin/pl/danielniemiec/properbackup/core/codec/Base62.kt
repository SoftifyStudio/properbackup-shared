package pl.danielniemiec.properbackup.core.codec

import java.math.BigInteger
import java.util.Arrays

object Base62 {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(62)
    private val DECODE_TABLE = buildDecodeTable()

    @JvmStatic
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        var leadingZeros = 0
        while (leadingZeros < data.size && data[leadingZeros].toInt() == 0) {
            leadingZeros++
        }

        val n = BigInteger(1, data)
        val sb = StringBuilder()
        var cur = n
        while (cur.signum() > 0) {
            val qr = cur.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            cur = qr[0]
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    @JvmStatic
    fun decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)

        var leadingZeros = 0
        while (leadingZeros < encoded.length && encoded[leadingZeros] == '0') {
            leadingZeros++
        }

        var n = BigInteger.ZERO
        for (c in encoded) {
            val digit = if (c.code < DECODE_TABLE.size) DECODE_TABLE[c.code] else -1
            require(digit >= 0) { "invalid base62 character: $c" }
            n = n.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        val raw = n.toByteArray()
        val rawStart = if (raw.isNotEmpty() && raw[0].toInt() == 0) 1 else 0
        val payloadLen = raw.size - rawStart

        val out = ByteArray(leadingZeros + payloadLen)
        System.arraycopy(raw, rawStart, out, leadingZeros, payloadLen)
        return out
    }

    @JvmStatic
    fun encodeFixedWidth(data: ByteArray, width: Int): String {
        val s = encode(data)
        require(s.length <= width) { "encoded length ${s.length} exceeds width $width" }
        if (s.length == width) return s
        val padded = CharArray(width) { ALPHABET[0] }
        s.toCharArray(padded, width - s.length, 0, s.length)
        return String(padded)
    }

    private fun buildDecodeTable(): IntArray {
        val t = IntArray(128) { -1 }
        for (i in ALPHABET.indices) {
            t[ALPHABET[i].code] = i
        }
        return t
    }
}
