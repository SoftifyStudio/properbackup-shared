package pl.danielniemiec.properbackup.agent.activation

import java.security.SecureRandom

/**
 * Generates a human-readable access code (8-12 characters).
 * Displayed on first run when agent is not yet activated.
 * User enters this code in the web panel to link the agent.
 */
object AccessCodeGenerator {

    private const val CODE_LENGTH = 8
    private val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()
    private val rng = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            sb.append(ALPHABET[rng.nextInt(ALPHABET.size)])
        }
        return sb.toString()
    }

    fun format(code: String): String {
        if (code.length <= 4) return code
        return "${code.substring(0, 4)}-${code.substring(4)}"
    }
}
