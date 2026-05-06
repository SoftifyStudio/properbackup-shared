package pl.danielniemiec.properbackup.agent.util

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AgentInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun tryAcquire(lockFile: Path): AgentInstanceLock? {
            lockFile.parent?.let { Files.createDirectories(it) }
            val ch = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val fl = ch.tryLock() ?: run {
                ch.close()
                return null
            }
            return AgentInstanceLock(ch, fl)
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
