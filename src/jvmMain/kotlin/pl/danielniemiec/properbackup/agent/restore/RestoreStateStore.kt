package pl.danielniemiec.properbackup.agent.restore

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class RestoreStateStore(dbPath: File) {

    private val conn: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePath}")
        conn.createStatement().use { st ->
            st.execute("""
                CREATE TABLE IF NOT EXISTS recovery_operations (
                    id TEXT PRIMARY KEY,
                    recovery_session_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    path TEXT NOT NULL,
                    path_hash TEXT NOT NULL,
                    sha256_target TEXT,
                    sha256_local TEXT,
                    size_bytes INTEGER,
                    state TEXT NOT NULL DEFAULT 'PENDING',
                    error TEXT,
                    quarantine_path TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """.trimIndent())
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_ops_session_state
                ON recovery_operations(recovery_session_id, state)
            """.trimIndent())
        }
    }

    fun insertOperation(op: RestoreOperation) {
        conn.prepareStatement("""
            INSERT OR IGNORE INTO recovery_operations
            (id, recovery_session_id, operation_type, path, path_hash, sha256_target, sha256_local, size_bytes, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { ps ->
            ps.setString(1, op.id)
            ps.setString(2, op.recoverySessionId)
            ps.setString(3, op.type.name)
            ps.setString(4, op.path)
            ps.setString(5, op.pathHash)
            ps.setString(6, op.sha256Target)
            ps.setString(7, op.sha256Local)
            if (op.sizeBytes != null) ps.setLong(8, op.sizeBytes) else ps.setNull(8, java.sql.Types.BIGINT)
            ps.setString(9, op.state.name)
            ps.executeUpdate()
        }
    }

    fun updateState(opId: String, state: RestoreOperation.OperationState, error: String? = null) {
        conn.prepareStatement("""
            UPDATE recovery_operations SET state = ?, error = ?, updated_at = datetime('now')
            WHERE id = ?
        """.trimIndent()).use { ps ->
            ps.setString(1, state.name)
            if (error != null) ps.setString(2, error) else ps.setNull(2, java.sql.Types.VARCHAR)
            ps.setString(3, opId)
            ps.executeUpdate()
        }
    }

    fun setQuarantinePath(opId: String, quarantinePath: String) {
        conn.prepareStatement("""
            UPDATE recovery_operations SET quarantine_path = ?, updated_at = datetime('now')
            WHERE id = ?
        """.trimIndent()).use { ps ->
            ps.setString(1, quarantinePath)
            ps.setString(2, opId)
            ps.executeUpdate()
        }
    }

    fun pendingOperations(sessionId: String): List<RestoreOperation> {
        return queryByState(sessionId, "PENDING")
    }

    fun inProgressOperations(sessionId: String): List<RestoreOperation> {
        return queryByState(sessionId, "IN_PROGRESS")
    }

    fun countByState(sessionId: String): Map<RestoreOperation.OperationState, Int> {
        val map = mutableMapOf<RestoreOperation.OperationState, Int>()
        conn.prepareStatement("""
            SELECT state, COUNT(*) as cnt FROM recovery_operations
            WHERE recovery_session_id = ? GROUP BY state
        """.trimIndent()).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    try {
                        val state = RestoreOperation.OperationState.valueOf(rs.getString("state"))
                        map[state] = rs.getInt("cnt")
                    } catch (_: Exception) { }
                }
            }
        }
        return map
    }

    fun clearSession(sessionId: String) {
        conn.prepareStatement("DELETE FROM recovery_operations WHERE recovery_session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }

    fun close() {
        try { conn.close() } catch (_: Exception) { }
    }

    private fun queryByState(sessionId: String, state: String): List<RestoreOperation> {
        val ops = mutableListOf<RestoreOperation>()
        conn.prepareStatement("""
            SELECT * FROM recovery_operations
            WHERE recovery_session_id = ? AND state = ?
            ORDER BY rowid
        """.trimIndent()).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, state)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    ops.add(RestoreOperation(
                        id = rs.getString("id"),
                        recoverySessionId = rs.getString("recovery_session_id"),
                        type = RestoreOperation.OperationType.valueOf(rs.getString("operation_type")),
                        path = rs.getString("path"),
                        pathHash = rs.getString("path_hash"),
                        sha256Target = rs.getString("sha256_target"),
                        sha256Local = rs.getString("sha256_local"),
                        sizeBytes = rs.getLong("size_bytes").takeIf { !rs.wasNull() },
                        state = RestoreOperation.OperationState.valueOf(rs.getString("state")),
                        error = rs.getString("error"),
                    ))
                }
            }
        }
        return ops
    }
}
