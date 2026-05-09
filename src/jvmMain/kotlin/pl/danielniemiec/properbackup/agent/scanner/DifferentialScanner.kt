package pl.danielniemiec.properbackup.agent.scanner

import net.openhft.hashing.LongHashFunction
import pl.danielniemiec.properbackup.core.filter.ExcludeFilter
import pl.danielniemiec.properbackup.core.filter.ExcludePatterns
import pl.danielniemiec.properbackup.core.filter.PrivacyAlertException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class DifferentialScanner(excludeGlobs: List<String>?) {

    data class ScanResult(
        val changedFiles: List<Path>,
        val deletedRelativePaths: List<String>,
        val currentState: Map<String, FileMetadata>,
    )

    private val excludeFilter: ExcludeFilter

    init {
        val merged = ArrayList(ExcludePatterns.DEFAULT_EXCLUDES)
        if (excludeGlobs != null) merged.addAll(excludeGlobs)
        excludeFilter = ExcludeFilter(merged)
    }

    @Throws(IOException::class)
    fun scan(rootDir: Path, previousState: Map<String, FileMetadata>): ScanResult {
        if (!Files.exists(rootDir)) {
            val deleted = ArrayList(previousState.keys)
            return ScanResult(emptyList(), deleted, emptyMap())
        }

        val entries = ArrayList<FileEntry>()
        try {
            Files.walkFileTree(rootDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootDir.relativize(file).toString()
                    val relNormalized = Path.of(rel.replace('\\', '/'))
                    if (excludeFilter.shouldExclude(relNormalized)) {
                        return FileVisitResult.CONTINUE
                    }
                    entries.add(FileEntry(file, rel, attrs.size(), attrs.lastModifiedTime().toMillis()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    System.err.println("WARN: cannot access $file: ${exc.message}")
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: PrivacyAlertException) {
            throw IOException(
                "Sensitive file matched privacy policy — scan stopped: ${e.filePath}",
                e,
            )
        }

        val current = HashMap<String, FileMetadata>(entries.size)
        val changed = ArrayList<Path>()

        for ((idx, e) in entries.withIndex()) {
            processEntry(e, previousState, current, changed)
            if (idx % 50 == 49) {
                Thread.yield()
                Thread.sleep(1)
            }
        }

        val deleted = previousState.keys.filter { !current.containsKey(it) }
        return ScanResult(changed, deleted, current)
    }

    private fun processEntry(
        entry: FileEntry,
        previousState: Map<String, FileMetadata>,
        current: MutableMap<String, FileMetadata>,
        changed: MutableList<Path>,
    ) {
        val prev = previousState[entry.rel]
        val needsHash = prev == null || prev.size != entry.size || prev.lastModified != entry.mtime
        val hash: Long = if (needsHash) {
            try {
                hashFileStreaming(entry.file)
            } catch (e: IOException) {
                -1L
            }
        } else {
            prev!!.xxHash
        }
        val meta = FileMetadata(entry.rel, entry.size, entry.mtime, hash)
        current[entry.rel] = meta
        if (prev == null || prev.xxHash != hash) {
            changed.add(entry.file)
        }
    }

    private fun hashFileStreaming(file: Path): Long {
        val size = Files.size(file)
        if (size <= STREAM_THRESHOLD) {
            return LongHashFunction.xx3().hashBytes(Files.readAllBytes(file))
        }
        Files.newInputStream(file).buffered(HASH_BUFFER_SIZE).use { input ->
            val buf = ByteArray(HASH_BUFFER_SIZE)
            val hasher = LongHashFunction.xx3()
            val chunks = ArrayList<ByteArray>()
            var totalRead = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                chunks.add(buf.copyOf(n))
                totalRead += n
                if (totalRead % (4L * 1024 * 1024) == 0L) {
                    Thread.yield()
                }
            }
            val full = ByteArray(totalRead.toInt())
            var off = 0
            for (c in chunks) { System.arraycopy(c, 0, full, off, c.size); off += c.size }
            return hasher.hashBytes(full)
        }
    }

    companion object {
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val STREAM_THRESHOLD = 256 * 1024L
    }

    private data class FileEntry(val file: Path, val rel: String, val size: Long, val mtime: Long)
}
