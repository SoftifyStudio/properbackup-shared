package pl.danielniemiec.properbackup.agent.scanner

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MetadataCache(stateDir: Path, serviceName: String) {

    private val stateFile: Path
    private val gson = Gson()

    init {
        Files.createDirectories(stateDir)
        stateFile = stateDir.resolve("$serviceName.state.json")
    }

    fun load(): MutableMap<String, FileMetadata> {
        if (!Files.exists(stateFile)) return HashMap()
        return try {
            val json = Files.readString(stateFile)
            val type = object : TypeToken<Map<String, StoredMeta>>() {}.type
            val raw: Map<String, StoredMeta>? = gson.fromJson(json, type)
            val result = HashMap<String, FileMetadata>()
            raw?.forEach { (k, v) ->
                result[k] = FileMetadata(k, v.size, v.lastModified, v.xxHash)
            }
            result
        } catch (e: IOException) {
            System.err.println("WARN: could not read state cache: ${e.message}")
            HashMap()
        }
    }

    @Throws(IOException::class)
    fun save(current: Map<String, FileMetadata>) {
        val raw = HashMap<String, StoredMeta>()
        current.forEach { (k, v) ->
            raw[k] = StoredMeta(v.size, v.lastModified, v.xxHash)
        }
        val tmp = stateFile.resolveSibling(stateFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, gson.toJson(raw))
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private class StoredMeta(val size: Long, val lastModified: Long, val xxHash: Long)
}
