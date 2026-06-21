package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persists per-operation state to a JSON file for resume-on-crash (HR-7).
 * On agent restart, reads state and resumes from first non-done operation.
 */
class RestoreStateStore(private val stateFile: Path) {

    private val gson = Gson()
    private val lock = Any()

    data class OpState(
        val id: String,
        val state: String,
        val error: String? = null,
    )

    fun save(operations: List<RecoveryOperation>) {
        synchronized(lock) {
            val states = operations.map { OpState(it.id, it.state.name, it.error) }
            val json = gson.toJson(states)
            Files.createDirectories(stateFile.parent)
            Files.writeString(stateFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    fun markState(opId: String, state: OperationState, error: String? = null) {
        synchronized(lock) {
            val existing = load()
            val updated = existing.map {
                if (it.id == opId) it.copy(state = state.name, error = error) else it
            }
            val json = gson.toJson(updated)
            Files.writeString(stateFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    fun load(): List<OpState> {
        if (!Files.exists(stateFile)) return emptyList()
        return try {
            val json = Files.readString(stateFile)
            val type = object : TypeToken<List<OpState>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cleanup() {
        try {
            Files.deleteIfExists(stateFile)
        } catch (_: Exception) { }
    }

    fun exists(): Boolean = Files.exists(stateFile)
}
