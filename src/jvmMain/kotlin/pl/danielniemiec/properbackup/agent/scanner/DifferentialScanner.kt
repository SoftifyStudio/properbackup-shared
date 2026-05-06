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
import java.util.concurrent.atomic.AtomicInteger

fun interface ScanProgressListener {
    fun onProgress(scannedFiles: Int, currentFile: String)
}

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
        return scan(rootDir, previousState, null)
    }

    @Throws(IOException::class)
    fun scan(rootDir: Path, previousState: Map<String, FileMetadata>, progressListener: ScanProgressListener?): ScanResult {
        if (!Files.exists(rootDir)) {
            val deleted = ArrayList(previousState.keys)
            return ScanResult(emptyList(), deleted, emptyMap())
        }

        val current = HashMap<String, FileMetadata>()
        val changed = ArrayList<Path>()
        val counter = AtomicInteger(0)

        try {
            Files.walkFileTree(rootDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootDir.relativize(file).toString()
                    val relNormalized = Path.of(rel.replace('\\', '/'))

                    if (excludeFilter.shouldExclude(relNormalized)) {
                        return FileVisitResult.CONTINUE
                    }

                    val size = attrs.size()
                    val mtime = attrs.lastModifiedTime().toMillis()
                    val prev = previousState[rel]

                    val needsHash = prev == null || prev.size != size || prev.lastModified != mtime
                    val hash: Long = if (needsHash) {
                        try {
                            val bytes = Files.readAllBytes(file)
                            LongHashFunction.xx3().hashBytes(bytes)
                        } catch (e: IOException) {
                            -1L
                        }
                    } else {
                        prev!!.xxHash
                    }

                    val meta = FileMetadata(rel, size, mtime, hash)
                    current[rel] = meta

                    val changedFile = prev == null || prev.xxHash != hash
                    if (changedFile) changed.add(file)

                    val count = counter.incrementAndGet()
                    if (progressListener != null && count % 50 == 0) {
                        progressListener.onProgress(count, rel)
                    }
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

        progressListener?.onProgress(current.size, "")

        val deleted = previousState.keys.filter { !current.containsKey(it) }

        return ScanResult(changed, deleted, current)
    }
}
