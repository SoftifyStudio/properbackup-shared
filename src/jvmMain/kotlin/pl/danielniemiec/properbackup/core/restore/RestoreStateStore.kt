package pl.danielniemiec.properbackup.core.restore

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed tracking store for resumable recovery (HR-7).
 * Each operation is tracked individually. On crash, the agent resumes
 * from the last non-DONE operation instead of restarting from scratch.
 */
class RestoreStateStore(private val dbPath: Path) : AutoCloseable {

    private val conn: Connection

    init {
        Files.createDirectories(dbPath.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.createStatement().use { stmt ->
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS restore_operation (
                    id TEXT PRIMARY KEY,
                    recovery_session_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    path TEXT NOT NULL,
                    path_hash TEXT NOT NULL,
                    sha256_target TEXT,
                    sha256_local TEXT,
                    size_bytes INTEGER,
                    state TEXT NOT NULL DEFAULT 'PENDING',
                    error TEXT,
                    updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )""",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_op_session ON restore_operation(recovery_session_id)",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_op_state ON restore_operation(recovery_session_id, state)",
            )
        }
    }

    fun insertOperation(op: RestoreOperation) {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO restore_operation
               (id, recovery_session_id, type, path, path_hash, sha256_target, sha256_local, size_bytes, state, error)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { ps ->
            ps.setString(1, op.id)
            ps.setString(2, op.recoverySessionId)
            ps.setString(3, op.type.name)
            ps.setString(4, op.path)
            ps.setString(5, op.pathHash)
            ps.setString(6, op.sha256Target)
            ps.setString(7, op.sha256Local)
            if (op.sizeBytes != null) ps.setLong(8, op.sizeBytes) else ps.setNull(8, java.sql.Types.INTEGER)
            ps.setString(9, op.state.name)
            ps.setString(10, op.error)
            ps.executeUpdate()
        }
    }

    fun insertOperations(ops: List<RestoreOperation>) {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """INSERT OR REPLACE INTO restore_operation
                   (id, recovery_session_id, type, path, path_hash, sha256_target, sha256_local, size_bytes, state, error)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ).use { ps ->
                for (op in ops) {
                    ps.setString(1, op.id)
                    ps.setString(2, op.recoverySessionId)
                    ps.setString(3, op.type.name)
                    ps.setString(4, op.path)
                    ps.setString(5, op.pathHash)
                    ps.setString(6, op.sha256Target)
                    ps.setString(7, op.sha256Local)
                    if (op.sizeBytes != null) ps.setLong(8, op.sizeBytes) else ps.setNull(8, java.sql.Types.INTEGER)
                    ps.setString(9, op.state.name)
                    ps.setString(10, op.error)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun updateState(opId: String, state: RestoreOperation.OperationState, error: String? = null) {
        conn.prepareStatement(
            "UPDATE restore_operation SET state = ?, error = ?, updated_at = strftime('%s','now') WHERE id = ?",
        ).use { ps ->
            ps.setString(1, state.name)
            ps.setString(2, error)
            ps.setString(3, opId)
            ps.executeUpdate()
        }
    }

    fun pendingOperations(sessionId: String): List<RestoreOperation> {
        val ops = mutableListOf<RestoreOperation>()
        conn.prepareStatement(
            """SELECT * FROM restore_operation
               WHERE recovery_session_id = ? AND state IN ('PENDING', 'IN_PROGRESS')
               ORDER BY rowid""",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    ops.add(mapRow(rs))
                }
            }
        }
        return ops
    }

    fun allOperations(sessionId: String): List<RestoreOperation> {
        val ops = mutableListOf<RestoreOperation>()
        conn.prepareStatement(
            "SELECT * FROM restore_operation WHERE recovery_session_id = ? ORDER BY rowid",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    ops.add(mapRow(rs))
                }
            }
        }
        return ops
    }

    fun summary(sessionId: String): OperationSummary {
        var total = 0; var done = 0; var failed = 0; var pending = 0
        conn.prepareStatement(
            "SELECT state, COUNT(*) as cnt FROM restore_operation WHERE recovery_session_id = ? GROUP BY state",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val count = rs.getInt("cnt")
                    total += count
                    when (rs.getString("state")) {
                        "DONE" -> done += count
                        "FAILED" -> failed += count
                        "PENDING", "IN_PROGRESS" -> pending += count
                    }
                }
            }
        }
        return OperationSummary(total, done, failed, pending)
    }

    fun cleanup(sessionId: String) {
        conn.prepareStatement("DELETE FROM restore_operation WHERE recovery_session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }

    override fun close() {
        conn.close()
    }

    private fun mapRow(rs: java.sql.ResultSet): RestoreOperation = RestoreOperation(
        id = rs.getString("id"),
        recoverySessionId = rs.getString("recovery_session_id"),
        type = RestoreOperation.OperationType.valueOf(rs.getString("type")),
        path = rs.getString("path"),
        pathHash = rs.getString("path_hash"),
        sha256Target = rs.getString("sha256_target"),
        sha256Local = rs.getString("sha256_local"),
        sizeBytes = rs.getLong("size_bytes").takeIf { !rs.wasNull() },
        state = RestoreOperation.OperationState.valueOf(rs.getString("state")),
        error = rs.getString("error"),
    )

    data class OperationSummary(val total: Int, val done: Int, val failed: Int, val pending: Int)
}
