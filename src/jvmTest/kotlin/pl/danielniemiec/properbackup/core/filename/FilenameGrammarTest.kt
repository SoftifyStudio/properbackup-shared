package pl.danielniemiec.properbackup.core.filename

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FilenameGrammarTest {

    @Test
    fun parsesCanonicalExample() {
        val p = FilenameGrammar.parse("U1-2605021200-aB3x9Q2k-A-f8c2.enc")
        assertEquals("U1", p.userId)
        assertEquals(LocalDateTime.of(2026, 5, 2, 12, 0), p.timestamp)
        assertEquals("aB3x9Q2k", p.pathId)
        assertEquals(ChangeFlag.A, p.flag)
        assertEquals("f8c2", p.shortHash)
    }

    @Test
    fun buildRoundTrip() {
        val original = ParsedFilename(
            "U7",
            LocalDateTime.of(2026, 12, 31, 23, 59),
            "abcdEFGH",
            ChangeFlag.D,
            "0000",
        )
        val filename = FilenameGrammar.build(original)
        assertEquals("U7-2612312359-abcdEFGH-D-0000.enc", filename)
        val roundTrip = FilenameGrammar.parse(filename)
        assertEquals(original, roundTrip)
    }

    @Test
    fun deletedFlagParses() {
        val p = FilenameGrammar.parse("U1-2605021200-aB3x9Q2k-D-0001.enc")
        assertEquals(ChangeFlag.D, p.flag)
    }

    @Test
    fun rejectsInvalidStructure() {
        val invalid = listOf(
            "U1-2605021200-aB3x9Q2k-A-f8c2",
            "U1-260502120-aB3x9Q2k-A-f8c2.enc",
            "U1-2605021200-aB3x9Q2-A-f8c2.enc",
            "U1-2605021200-aB3x9Q2kk-A-f8c2.enc",
            "U-2605021200-aB3x9Q2k-A-f8c2.enc",
            "U1-2605021200-aB3x9Q2k-X-f8c2.enc",
            "U1-2605021200-aB3x9Q2k-A-F8C2.enc",
            "U1-2605021200-aB3x9Q2k-A-f8cz.enc",
            "U1-2605021200-aB3x9Q2k-A-f8c.enc",
            "user1-2605021200-aB3x9Q2k-A-f8c2.enc",
        )
        for (f in invalid) {
            assertFalse(FilenameGrammar.matches(f), "should not match: $f")
            assertThrows(IllegalArgumentException::class.java, { FilenameGrammar.parse(f) })
        }
    }

    @Test
    fun rejectsNull() {
        assertFalse(FilenameGrammar.matches(null))
        assertThrows(IllegalArgumentException::class.java) {
            FilenameGrammar.parse(null)
        }
    }

    @Test
    fun multiUserSupport() {
        for (user in listOf("U1", "U2", "U99", "U1234")) {
            val f = "$user-2605021200-aB3x9Q2k-A-f8c2.enc"
            assertTrue(FilenameGrammar.matches(f))
            assertEquals(user, FilenameGrammar.parse(f).userId)
        }
    }
}
