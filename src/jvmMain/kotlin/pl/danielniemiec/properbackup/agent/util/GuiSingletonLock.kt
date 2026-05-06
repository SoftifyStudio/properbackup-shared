package pl.danielniemiec.properbackup.agent.util

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

class GuiSingletonLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    companion object {
        @JvmStatic
        fun supportDirectory(): Path {
            val override = System.getProperty("properbackup.support.dir.override")
            if (!override.isNullOrBlank()) {
                return Path.of(override)
            }
            val home = System.getProperty("user.home", ".")
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return when {
                os.contains("mac") || os.contains("darwin") ->
                    Path.of(home, "Library", "Application Support", "ProperBackup")
                os.contains("win") -> {
                    val appData = System.getenv("APPDATA") ?: home
                    Path.of(appData, "ProperBackup")
                }
                else -> Path.of(home, ".local", "share", "properbackup")
            }
        }

        @JvmStatic
        fun lockFilePath(): Path = supportDirectory().resolve(".gui-agent.lock")

        @JvmStatic
        @Throws(IOException::class)
        fun tryAcquire(): GuiSingletonLock? {
            val f = lockFilePath()
            Files.createDirectories(f.parent)
            val ch = FileChannel.open(
                f,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val fl = try {
                ch.tryLock()
            } catch (e: OverlappingFileLockException) {
                ch.close()
                return null
            }
            if (fl == null) {
                ch.close()
                return null
            }
            return GuiSingletonLock(ch, fl)
        }
    }

    override fun close() {
        try {
            if (lock.isValid) {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }
}
