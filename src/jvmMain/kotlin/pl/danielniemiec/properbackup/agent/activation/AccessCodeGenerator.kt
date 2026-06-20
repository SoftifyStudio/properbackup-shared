package pl.danielniemiec.properbackup.agent.activation

import java.security.SecureRandom

/**
 * Generates a human-readable access code (8-12 characters) displayed on first
 * agent run. The user enters this code in the web UI to link the agent.
 *
 * Format: uppercase alphanumeric, easy to read/type (no ambiguous chars like 0/O, 1/I/l).
 */
object AccessCodeGenerator {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 8
    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    fun formatForDisplay(code: String): String {
        if (code.length <= 3) return code
        val mid = code.length / 2
        return "${code.substring(0, mid)}-${code.substring(mid)}"
    }
}
