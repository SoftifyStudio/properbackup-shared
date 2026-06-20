package pl.danielniemiec.properbackup.shared.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.danielniemiec.properbackup.shared.host.jvm.JvmPlatformFs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for JvmPlatformFs — verifies cross-host parity contract (SHC-B group).
 */
class JvmPlatformFsTest {

    private val fs: PlatformFs = JvmPlatformFs()

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `write is atomic — partial file never visible`() {
        val path = tmpDir.resolve("atomic-test.txt").toString()
        val data = "Hello ProperBackup".toByteArray()

        fs.write(path, data)

        assertTrue(fs.exists(path))
        assertArrayEquals(data, fs.read(path))
        // .tmp file should not remain
        assertFalse(Files.exists(Path.of("$path.tmp")))
    }

    @Test
    fun `write creates parent directories`() {
        val path = tmpDir.resolve("deep/nested/dir/file.txt").toString()
        fs.write(path, "data".toByteArray())
        assertTrue(fs.exists(path))
    }

    @Test
    fun `list returns sorted entries`() {
        val dir = tmpDir.resolve("listtest").toString()
        fs.mkdirs(dir)
        fs.write("$dir/c.txt", "c".toByteArray())
        fs.write("$dir/a.txt", "a".toByteArray())
        fs.write("$dir/b.txt", "b".toByteArray())

        val result = fs.list(dir)
        assertEquals(3, result.size)
        assertTrue(result[0].endsWith("a.txt"))
        assertTrue(result[1].endsWith("b.txt"))
        assertTrue(result[2].endsWith("c.txt"))
    }

    @Test
    fun `list returns empty for non-existent directory`() {
        val result = fs.list(tmpDir.resolve("nonexistent").toString())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `size and mtime work correctly`() {
        val path = tmpDir.resolve("size-test.bin").toString()
        val data = ByteArray(1024) { it.toByte() }
        fs.write(path, data)

        assertEquals(1024L, fs.size(path))
        assertTrue(fs.mtime(path) > 0)
    }

    @Test
    fun `delete removes file`() {
        val path = tmpDir.resolve("delete-me.txt").toString()
        fs.write(path, "bye".toByteArray())
        assertTrue(fs.exists(path))

        fs.delete(path)
        assertFalse(fs.exists(path))
    }

    @Test
    fun `rename moves file atomically`() {
        val from = tmpDir.resolve("orig.txt").toString()
        val to = tmpDir.resolve("renamed.txt").toString()
        fs.write(from, "content".toByteArray())

        fs.rename(from, to)

        assertFalse(fs.exists(from))
        assertTrue(fs.exists(to))
        assertArrayEquals("content".toByteArray(), fs.read(to))
    }

    @Test
    fun `lock returns non-null on JVM and is closeable`() {
        val lockPath = tmpDir.resolve("test.lock").toString()
        val lock = fs.lock(lockPath)

        assertNotNull(lock)
        lock!!.close()
    }

    @Test
    fun `freeSpace returns positive value`() {
        val space = fs.freeSpace(tmpDir.toString())
        assertTrue(space > 0)
    }

    @Test
    fun `writeStream produces atomic file`() {
        val path = tmpDir.resolve("stream-test.txt").toString()
        val data = "streamed content"

        fs.writeStream(path).use { out ->
            out.write(data.toByteArray())
        }

        assertTrue(fs.exists(path))
        assertEquals(data, String(fs.read(path)))
    }

    @Test
    fun `readStream returns correct content`() {
        val path = tmpDir.resolve("read-stream.txt").toString()
        fs.write(path, "stream read test".toByteArray())

        val content = fs.readStream(path).use { it.readAllBytes() }
        assertEquals("stream read test", String(content))
    }
}
