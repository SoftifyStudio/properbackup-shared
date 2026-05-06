package pl.danielniemiec.properbackup.agent.activation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object GlobalConfigWriter {

    @JvmStatic
    fun setServerId(globalYml: Path, serverId: String) {
        if (!Files.exists(globalYml)) {
            throw java.io.IOException("global.yml not found: $globalYml")
        }
        var content = Files.readString(globalYml)
        val newLine = "  server_id: $serverId"

        content = when {
            content.contains("  server_id:") ->
                content.replace("(?m)^  server_id:.*$".toRegex(), newLine)
            content.contains("agent:") ->
                content.replaceFirst("(agent:[\\s\\S]*?)(\\n(?!  ))".toRegex(), "$1\n$newLine$2")
            else -> content + "\nagent:\n$newLine\n"
        }

        Files.writeString(globalYml, content, StandardOpenOption.TRUNCATE_EXISTING)
        println("✓ Zapisano server_id do $globalYml")
    }
}
