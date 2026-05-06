package pl.danielniemiec.properbackup.core.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class ProperCodecTest {

    @Test
    fun deterministicAcross10Calls() {
        val path = "/home/minecraft/world"
        val first = ProperCodec.pathToId(path)
        repeat(10) {
            assertEquals(first, ProperCodec.pathToId(path))
        }
    }

    @Test
    fun idHasFixedWidth() {
        assertEquals(ProperCodec.ID_WIDTH, ProperCodec.pathToId("/var/www/mojastrona").length)
        assertEquals(ProperCodec.ID_WIDTH, ProperCodec.pathToId("/").length)
        assertEquals(ProperCodec.ID_WIDTH, ProperCodec.pathToId("/a").length)
    }

    @Test
    fun differentPathsDifferentIds() {
        val ids = mutableSetOf(
            ProperCodec.pathToId("/home/minecraft/world"),
            ProperCodec.pathToId("/home/minecraft/plugins"),
            ProperCodec.pathToId("/var/www/mojastrona"),
            ProperCodec.pathToId("/var/lib/postgresql"),
            ProperCodec.pathToId("/etc/nginx"),
        )
        assertEquals(5, ids.size, "different paths must produce different IDs")
    }

    @Test
    fun normalizationIgnoresTrailingSlash() {
        assertEquals(
            ProperCodec.pathToId("/var/www/mojastrona"),
            ProperCodec.pathToId("/var/www/mojastrona/"),
        )
    }

    @Test
    fun normalizationCollapsesMultipleSlashes() {
        assertEquals(
            ProperCodec.pathToId("/var/www/mojastrona"),
            ProperCodec.pathToId("/var//www///mojastrona"),
        )
    }

    @Test
    fun rootPathStable() {
        assertEquals(ProperCodec.pathToId("/"), ProperCodec.pathToId("/"))
    }

    @Test
    fun noCollisionsIn10000RandomPaths() {
        val r = Random(7)
        val ids = HashSet<String>()
        var collisions = 0
        repeat(10_000) { i ->
            val path = "/srv/${r.nextInt()}/${r.nextInt()}/dir$i"
            if (!ids.add(ProperCodec.pathToId(path))) {
                collisions++
            }
        }
        assertTrue(collisions <= 1, "unexpected collisions: $collisions")
    }

    @Test
    fun rejectsNullPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ProperCodec.pathToId(null)
        }
    }

    @Test
    fun idIsBase62Charset() {
        val id = ProperCodec.pathToId("/some/random/path")
        for (c in id) {
            assertTrue(
                c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z',
                "non-base62 char in ID: $c",
            )
        }
    }
}
