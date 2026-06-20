package pl.danielniemiec.properbackup.shared.host.jvm

import pl.danielniemiec.properbackup.shared.host.FsLock
import pl.danielniemiec.properbackup.shared.host.PlatformFs
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Standard JVM filesystem implementation using java.nio.file.
 * Writes are atomic: data is written to a .tmp file, then renamed to final destination.
 */
class JvmPlatformFs : PlatformFs {

    override fun exists(path: String): Boolean =
        Files.exists(Path.of(path))

    override fun read(path: String): ByteArray =
        Files.readAllBytes(Path.of(path))

    override fun readStream(path: String): InputStream =
        Files.newInputStream(Path.of(path))

    override fun write(path: String, data: ByteArray) {
        val target = Path.of(path)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.createDirectories(target.parent)
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun writeStream(path: String): OutputStream {
        val target = Path.of(path)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.createDirectories(target.parent)
        return AtomicOutputStream(tmp, target)
    }

    override fun list(dir: String): List<String> {
        val dirPath = Path.of(dir)
        if (!Files.isDirectory(dirPath)) return emptyList()
        return Files.list(dirPath).use { stream ->
            stream.map { it.toString() }.sorted().toList()
        }
    }

    override fun mkdirs(dir: String) {
        Files.createDirectories(Path.of(dir))
    }

    override fun delete(path: String) {
        Files.deleteIfExists(Path.of(path))
    }

    override fun rename(from: String, to: String) {
        Files.move(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun size(path: String): Long =
        Files.size(Path.of(path))

    override fun mtime(path: String): Long =
        Files.getLastModifiedTime(Path.of(path)).toMillis()

    override fun lock(path: String): FsLock? {
        return try {
            val p = Path.of(path)
            Files.createDirectories(p.parent)
            val channel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock() ?: run {
                channel.close()
                return null
            }
            JvmFsLock(channel, lock)
        } catch (_: Exception) {
            null
        }
    }

    override fun freeSpace(dir: String): Long =
        Path.of(dir).toFile().usableSpace
}

private class JvmFsLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) : FsLock {
    override fun close() {
        lock.release()
        channel.close()
    }
}

private class AtomicOutputStream(
    private val tmpPath: Path,
    private val targetPath: Path,
) : OutputStream() {
    private val delegate = Files.newOutputStream(tmpPath)

    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()

    override fun close() {
        delegate.close()
        Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
