package pl.danielniemiec.properbackup.agent.util

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale
import java.util.Optional

object StartupTrace {

    @Volatile
    private var logFile: Path? = null

    @JvmStatic
    fun initFromClassJar(mainClass: Class<*>) {
        try {
            val loc = mainClass.protectionDomain.codeSource?.location ?: return
            val p = Paths.get(loc.toURI())
            if (!p.toString().lowercase(Locale.ROOT).endsWith(".jar")) return
            val parent = p.parent ?: return
            logFile = parent.resolve("properbackup-launch.log")
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun logFile(): Optional<Path> = Optional.ofNullable(logFile)

    @JvmStatic
    fun line(message: String) {
        val ts = Instant.now().toString()
        val full = "$ts $message"
        System.err.println("[properbackup] $message")
        val f = logFile
        if (f != null) {
            try {
                Files.writeString(
                    f,
                    full + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (ignored: Exception) {
            }
        }
    }
}
