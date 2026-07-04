package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persists per-operation state to a JSON file for resume-on-crash (HR-7).
 * On agent restart, reads state and resumes from first non-done operation.
 *
 * Stores full operation details (path, type, sha256) so ops survive restart
 * without regenerating new UUIDs.
 */
class RestoreStateStore(private val stateFile: Path) {

    private val gson = Gson()
    private val lock = Any()

    data class OpState(
        val id: String,
        val state: String,
        val error: String? = null,
        val type: String? = null,
        val path: String? = null,
        val sha256Target: String? = null,
        val sha256Local: String? = null,
        val sizeBytes: Long? = null,
    )

    fun save(operations: List<RecoveryOperation>) {
        synchronized(lock) {
            val states = operations.map {
                OpState(
                    id = it.id,
                    state = it.state.name,
                    error = it.error,
                    type = it.type.name,
                    path = it.path,
                    sha256Target = it.sha256Target,
                    sha256Local = it.sha256Local,
                    sizeBytes = it.sizeBytes,
                )
            }
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

    /**
     * Reconstruct RecoveryOperation objects from saved state (HR-7 resume).
     * Returns null if no saved state exists.
     */
    fun loadOperations(): List<RecoveryOperation>? {
        val saved = load()
        if (saved.isEmpty()) return null
        if (saved.first().type == null) return null // legacy format without full op data
        return saved.map { s ->
            RecoveryOperation(
                id = s.id,
                type = OperationType.valueOf(s.type!!),
                path = s.path ?: "",
                sha256Target = s.sha256Target,
                sha256Local = s.sha256Local,
                sizeBytes = s.sizeBytes ?: 0,
                state = try { OperationState.valueOf(s.state) } catch (_: Exception) { OperationState.PENDING },
                error = s.error,
            )
        }
    }

    fun cleanup() {
        try {
            Files.deleteIfExists(stateFile)
        } catch (_: Exception) { }
    }

    fun exists(): Boolean = Files.exists(stateFile)
}
