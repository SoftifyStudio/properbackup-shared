package pl.danielniemiec.properbackup.shared.host

import java.io.InputStream
import java.io.OutputStream

/**
 * Filesystem abstraction. Each host implements this to map to its own fs semantics:
 * - VPS: standard java.nio.file operations in home dir
 * - MC Plugin: Bukkit's plugin.dataFolder
 * - Fabric: FabricLoader configDir
 *
 * write() MUST be atomic (write to .tmp, then rename) — partial writes are never visible.
 */
interface PlatformFs {
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray
    fun readStream(path: String): InputStream
    fun write(path: String, data: ByteArray)
    fun writeStream(path: String): OutputStream
    fun list(dir: String): List<String>
    fun mkdirs(dir: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
    fun size(path: String): Long
    fun mtime(path: String): Long
    fun lock(path: String): FsLock?
    fun freeSpace(dir: String): Long
}

interface FsLock : AutoCloseable
