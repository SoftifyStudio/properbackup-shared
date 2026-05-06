package pl.danielniemiec.properbackup.core.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

object HeaderCodec {

    val MAGIC = byteArrayOf(0x50, 0x52, 0x4F, 0x50)
    const val VERSION: Byte = 0x01
    const val HEADER_SIZE = 49

    val CANARY_PLAINTEXT: ByteArray = "VERIFIED\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray()

    data class Header(val salt: ByteArray, val nonce: ByteArray, val authTag: ByteArray)

    @JvmStatic
    @Throws(IOException::class)
    fun write(out: OutputStream, salt: ByteArray, nonce: ByteArray, authTag: ByteArray) {
        require(salt.size == 16) { "salt must be 16 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        require(authTag.size == 16) { "authTag must be 16 bytes" }
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(salt)
        out.write(nonce)
        out.write(authTag)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(`in`: InputStream): Header {
        val magic = `in`.readNBytes(4)
        if (!Arrays.equals(magic, MAGIC)) {
            throw IOException("Not a ProperBackup file — magic mismatch")
        }
        val version = `in`.read()
        if (version != VERSION.toInt()) {
            throw IOException("Unsupported version: $version")
        }
        val salt = `in`.readNBytes(16)
        val nonce = `in`.readNBytes(12)
        val authTag = `in`.readNBytes(16)
        return Header(salt, nonce, authTag)
    }
}
