package pl.danielniemiec.properbackup.core.crypto

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProperCrypto {

    const val CHUNK_SIZE = 64 * 1024
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val RNG = SecureRandom()

    class BadPasswordException : Exception("Wrong password — PROP canary mismatch")

    @JvmStatic
    @Throws(Exception::class)
    fun encrypt(password: CharArray, plaintext: InputStream, ciphertext: OutputStream) {
        val salt = randomBytes(16)
        val canaryNonce = randomBytes(12)
        val key = KeyDerivation.derive(password, salt)
        val canaryTag = encryptCanary(key, canaryNonce)
        HeaderCodec.write(ciphertext, salt, canaryNonce, canaryTag)

        val buf = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = plaintext.read(buf)
            if (read == -1) break
            val chunkNonce = randomBytes(12)
            val plainChunk = if (read == buf.size) buf else buf.copyOf(read)
            val encChunk = gcmEncrypt(key, chunkNonce, plainChunk)
            val frameLen = 12 + encChunk.size
            writeInt(ciphertext, frameLen)
            ciphertext.write(chunkNonce)
            ciphertext.write(encChunk)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun decrypt(password: CharArray, ciphertext: InputStream, plaintext: OutputStream) {
        val header = HeaderCodec.read(ciphertext)
        val key = KeyDerivation.derive(password, header.salt)
        verifyCanary(key, header.nonce, header.authTag)

        val dis = DataInputStream(ciphertext)
        while (dis.available() > 0) {
            val frameLen = dis.readInt()
            val chunkNonce = dis.readNBytes(12)
            val encChunk = dis.readNBytes(frameLen - 12)
            val plain = gcmDecrypt(key, chunkNonce, encChunk)
            plaintext.write(plain)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun verifyPassword(password: CharArray, header: HeaderCodec.Header) {
        val key = KeyDerivation.derive(password, header.salt)
        verifyCanary(key, header.nonce, header.authTag)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun encryptCanary(key: SecretKeySpec, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val out = cipher.doFinal(HeaderCodec.CANARY_PLAINTEXT)
        val tag = ByteArray(16)
        System.arraycopy(out, out.size - 16, tag, 0, 16)
        return tag
    }

    private fun verifyCanary(key: SecretKeySpec, nonce: ByteArray, storedTag: ByteArray) {
        val computedTag = encryptCanary(key, nonce)
        if (!constantTimeEquals(computedTag, storedTag)) {
            throw BadPasswordException()
        }
    }

    private fun gcmEncrypt(key: SecretKeySpec, nonce: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plain)
    }

    private fun gcmDecrypt(key: SecretKeySpec, nonce: ByteArray, cipherAndTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(cipherAndTag)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { RNG.nextBytes(it) }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
