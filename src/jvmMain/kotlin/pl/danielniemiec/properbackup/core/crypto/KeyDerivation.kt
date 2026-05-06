package pl.danielniemiec.properbackup.core.crypto

import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object KeyDerivation {

    const val ITERATIONS = 200_000
    const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    @JvmStatic
    fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(salt.size == 16) { "salt must be 16 bytes" }
        return try {
            val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
            try {
                val skf = SecretKeyFactory.getInstance(ALGORITHM)
                val keyBytes = skf.generateSecret(spec).encoded
                SecretKeySpec(keyBytes, "AES")
            } finally {
                spec.clearPassword()
            }
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("PBKDF2 unavailable", e)
        } catch (e: InvalidKeySpecException) {
            throw IllegalStateException("PBKDF2 unavailable", e)
        }
    }

    @JvmStatic
    fun derive(password: String, salt: ByteArray): SecretKeySpec =
        derive(password.toCharArray(), salt)
}
