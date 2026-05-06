package pl.danielniemiec.properbackup.agent.util

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

object MachineIdentity {

    @JvmStatic
    @Throws(IOException::class)
    fun getOrCreate(deviceIdFile: Path): String {
        if (Files.exists(deviceIdFile)) {
            val s = Files.readString(deviceIdFile).trim()
            if (s.isNotEmpty()) return s
        }
        Files.createDirectories(deviceIdFile.parent)
        val id = UUID.randomUUID().toString()
        Files.writeString(deviceIdFile, id, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return id
    }

    @JvmStatic
    fun defaultDeviceIdFile(globalYml: Path): Path {
        val parent = globalYml.parent
        return parent?.resolve("device.id") ?: Path.of("conf.d").resolve("device.id")
    }
}
