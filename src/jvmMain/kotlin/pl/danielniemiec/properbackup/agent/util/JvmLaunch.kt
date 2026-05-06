package pl.danielniemiec.properbackup.agent.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object JvmLaunch {

    @JvmStatic
    fun javaExecutableName(): String =
        if (System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")) "java.exe" else "java"

    @JvmStatic
    fun javaExecutablePathOrPathLookup(): String {
        val fromHome = Path.of(System.getProperty("java.home", "."), "bin", javaExecutableName())
        return if (Files.isRegularFile(fromHome) && Files.isExecutable(fromHome)) {
            fromHome.toAbsolutePath().normalize().toString()
        } else {
            javaExecutableName()
        }
    }
}
