package pl.danielniemiec.properbackup.agent.activation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessCodeGeneratorTest {

    @Test
    fun `generate returns 8-character code`() {
        val code = AccessCodeGenerator.generate()
        assertEquals(8, code.length, "Access code should be 8 characters")
    }

    @Test
    fun `generate returns uppercase alphanumeric without ambiguous chars`() {
        repeat(100) {
            val code = AccessCodeGenerator.generate()
            assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" },
                "Code '$code' contains invalid characters")
        }
    }

    @Test
    fun `generate returns unique codes`() {
        val codes = (1..1000).map { AccessCodeGenerator.generate() }.toSet()
        assertTrue(codes.size > 900, "Expected mostly unique codes, got ${codes.size} unique out of 1000")
    }

    @Test
    fun `format adds dash after 4th character`() {
        val formatted = AccessCodeGenerator.format("ABCD1234")
        assertEquals("ABCD-1234", formatted)
    }

    @Test
    fun `format handles short codes`() {
        assertEquals("ABC", AccessCodeGenerator.format("ABC"))
    }
}
