package pl.danielniemiec.properbackup.agent.scanner

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DifferentialScannerTest {

    @Test
    fun scanAbortsOnSensitiveFileName(@TempDir root: Path) {
        Files.writeString(root.resolve(".env"), "SECRET=x")
        val scanner = DifferentialScanner(emptyList())
        val ex = kotlin.runCatching { scanner.scan(root, emptyMap()) }.exceptionOrNull() as? java.io.IOException
        assertNotNull(ex)
        val msg = ex!!.message!!
        assertTrue(msg.contains("Sensitive file") || msg.contains("privacy"))
        assertNotNull(ex.cause)
    }

    @Test
    fun scanSkipsDefaultExcluded(@TempDir root: Path) {
        Files.createDirectories(root.resolve("logs"))
        Files.writeString(root.resolve("logs").resolve("a.log"), "noise")
        val scanner = DifferentialScanner(emptyList())
        val result = scanner.scan(root, emptyMap())
        assertTrue(result.currentState.isEmpty())
        assertTrue(result.changedFiles.isEmpty())
    }
}
