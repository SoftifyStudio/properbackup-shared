package pl.danielniemiec.properbackup.agent.activation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccessCodeGeneratorTest {

    @Test
    fun `generated code has 8 characters`() {
        val code = AccessCodeGenerator.generate()
        assertEquals(8, code.length)
    }

    @Test
    fun `generated code contains only allowed characters`() {
        val allowed = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet()
        repeat(100) {
            val code = AccessCodeGenerator.generate()
            for (ch in code) {
                assertTrue(ch in allowed, "Unexpected character '$ch' in code '$code'")
            }
        }
    }

    @Test
    fun `generated codes are unique`() {
        val codes = (1..1000).map { AccessCodeGenerator.generate() }.toSet()
        assertTrue(codes.size > 950, "Expected mostly unique codes, got ${codes.size} unique out of 1000")
    }

    @Test
    fun `no ambiguous characters in generated codes`() {
        val ambiguous = setOf('0', 'O', '1', 'I', 'l')
        repeat(100) {
            val code = AccessCodeGenerator.generate()
            for (ch in code) {
                assertTrue(ch !in ambiguous, "Ambiguous character '$ch' found in code '$code'")
            }
        }
    }

    @Test
    fun `formatForDisplay adds hyphen in middle`() {
        assertEquals("ABCD-EFGH", AccessCodeGenerator.formatForDisplay("ABCDEFGH"))
        assertEquals("AB-CD", AccessCodeGenerator.formatForDisplay("ABCD"))
        assertEquals("ABC", AccessCodeGenerator.formatForDisplay("ABC"))
    }
}
