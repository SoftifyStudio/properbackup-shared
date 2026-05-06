package pl.danielniemiec.properbackup.core.codec

import net.openhft.hashing.LongHashFunction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object ProperCodec {

    const val ID_BYTES = 6
    const val ID_WIDTH = 8

    private const val SEED_HI = 0x50524F50_50415448L
    private const val SEED_LO = 0x49445832_36325630L

    @JvmStatic
    fun pathToId(path: String?): String {
        if (path == null) throw IllegalArgumentException("path is null")
        val normalized = normalize(path).toByteArray(StandardCharsets.UTF_8)
        val truncated = hash6Bytes(normalized)
        return Base62.encodeFixedWidth(truncated, ID_WIDTH)
    }

    private fun normalize(path: String): String {
        var p = path.trim().replace("/+".toRegex(), "/")
        if (p.length > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length - 1)
        }
        return p
    }

    private fun hash6Bytes(data: ByteArray): ByteArray {
        val h1 = LongHashFunction.xx3(SEED_HI).hashBytes(data)
        val h2 = LongHashFunction.xx3(SEED_LO).hashBytes(data)
        val buf = ByteBuffer.allocate(16).putLong(h1).putLong(h2)
        val out = ByteArray(ID_BYTES)
        System.arraycopy(buf.array(), 16 - ID_BYTES, out, 0, ID_BYTES)
        out[0] = (out[0].toInt() and 0x3F).toByte()
        return out
    }
}
