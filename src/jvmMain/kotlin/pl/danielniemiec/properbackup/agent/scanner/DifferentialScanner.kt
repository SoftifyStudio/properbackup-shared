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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Progress callback fired periodically during scan.
     * @param phase "discovering" while walking the file tree, "processing" while hashing
     * @param current files discovered or processed so far
     * @param total 0 during discovery (unknown), entries.size during processing
     */
    fun interface ProgressListener {
        fun onProgress(phase: String, current: Int, total: Int)
    }

    @Throws(IOException::class)
    fun scan(rootDir: Path, previousState: Map<String, FileMetadata>): ScanResult =
        scan(rootDir, previousState, null)

    @Throws(IOException::class)
    fun scan(rootDir: Path, previousState: Map<String, FileMetadata>, progress: ProgressListener?): ScanResult {
        if (!Files.exists(rootDir)) {
            val deleted = ArrayList(previousState.keys)
            return ScanResult(emptyList(), deleted, emptyMap())
        }

        val entries = ArrayList<FileEntry>()
        val lastProgressMs = AtomicLong(0)
        val progressIntervalMs = 300L
        try {
            Files.walkFileTree(rootDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootDir.relativize(file).toString()
                    val relNormalized = Path.of(rel.replace('\\', '/'))
                    if (excludeFilter.shouldExclude(relNormalized)) {
                        return FileVisitResult.CONTINUE
                    }
                    entries.add(FileEntry(file, rel, attrs.size(), attrs.lastModifiedTime().toMillis()))
                    if (progress != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs.get() >= progressIntervalMs) {
                            lastProgressMs.set(now)
                            progress.onProgress("discovering", entries.size, 0)
                        }
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

        val current = ConcurrentHashMap<String, FileMetadata>(entries.size)
        val changed = ConcurrentHashMap.newKeySet<Path>()
        val firstError = AtomicReference<Throwable?>()

        val cores = Runtime.getRuntime().availableProcessors()
        val parallelism = maxOf(1, cores - 1)

        val total = entries.size
        progress?.onProgress("processing", 0, total)
        lastProgressMs.set(System.currentTimeMillis())
        val processed = AtomicInteger(0)

        if (total < 200 || parallelism <= 1) {
            for (e in entries) {
                processEntry(e, previousState, current, changed)
                if (progress != null) {
                    val done = processed.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (now - lastProgressMs.get() >= progressIntervalMs) {
                        lastProgressMs.set(now)
                        progress.onProgress("processing", done, total)
                    }
                }
            }
        } else {
            val pool = ForkJoinPool(parallelism)
            try {
                pool.submit {
                    entries.parallelStream().forEach { e ->
                        try {
                            processEntry(e, previousState, current, changed)
                            if (progress != null) {
                                val done = processed.incrementAndGet()
                                val now = System.currentTimeMillis()
                                if (now - lastProgressMs.get() >= progressIntervalMs) {
                                    lastProgressMs.set(now)
                                    progress.onProgress("processing", done, total)
                                }
                            }
                        } catch (t: Throwable) {
                            firstError.compareAndSet(null, t)
                        }
                    }
                }.get()
            } finally {
                pool.shutdown()
            }
            firstError.get()?.let { throw IOException("Parallel scan error", it) }
        }
        progress?.onProgress("processing", total, total)

        val deleted = previousState.keys.filter { !current.containsKey(it) }
        return ScanResult(ArrayList(changed), deleted, HashMap(current))
    }

    private fun processEntry(
        entry: FileEntry,
        previousState: Map<String, FileMetadata>,
        current: ConcurrentHashMap<String, FileMetadata>,
        changed: MutableSet<Path>,
    ) {
        val prev = previousState[entry.rel]
        val needsHash = prev == null || prev.size != entry.size || prev.lastModified != entry.mtime
        val hash: Long = if (needsHash) {
            try {
                val bytes = Files.readAllBytes(entry.file)
                LongHashFunction.xx3().hashBytes(bytes)
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

    private data class FileEntry(val file: Path, val rel: String, val size: Long, val mtime: Long)
}
