package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reports restore progress to buffer in batches (every BATCH_SIZE ops or BATCH_INTERVAL_MS).
 */
class RestoreProgressReporter(
    private val bufferUrl: String,
    private val sessionId: String,
    private val token: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val gson = Gson()
    private var pendingRestored = 0
    private var pendingDeleted = 0
    private var pendingBytes = 0L
    private var lastReportTime = System.currentTimeMillis()

    fun trackRestored(bytes: Long) {
        pendingRestored++
        pendingBytes += bytes
        maybeFlush()
    }

    fun trackDeleted() {
        pendingDeleted++
        maybeFlush()
    }

    private fun maybeFlush() {
        val total = pendingRestored + pendingDeleted
        val elapsed = System.currentTimeMillis() - lastReportTime
        if (total >= BATCH_SIZE || elapsed >= BATCH_INTERVAL_MS) {
            flush()
        }
    }

    fun flush() {
        if (pendingRestored == 0 && pendingDeleted == 0) return
        try {
            val body = gson.toJson(mapOf(
                "files_restored_delta" to pendingRestored,
                "files_deleted_delta" to pendingDeleted,
                "bytes_delta" to pendingBytes,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/$sessionId/progress"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            System.err.println("[restore] progress report failed: ${e.message}")
        }
        pendingRestored = 0
        pendingDeleted = 0
        pendingBytes = 0L
        lastReportTime = System.currentTimeMillis()
    }

    fun reportComplete(success: Boolean, verification: String = "OK") {
        flush()
        try {
            val body = gson.toJson(mapOf(
                "success" to success,
                "verification" to verification,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/$sessionId/complete"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            System.err.println("[restore] complete report failed: ${e.message}")
        }
    }

    fun reportDryRun(
        filesToRestore: Int,
        filesToDelete: Int,
        filesUnchanged: Int,
        totalBytes: Long,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        criticalProtected: Int,
    ) {
        try {
            val body = gson.toJson(mapOf(
                "files_to_restore" to filesToRestore,
                "files_to_delete" to filesToDelete,
                "files_unchanged" to filesUnchanged,
                "total_bytes" to totalBytes,
                "sample_restore" to sampleRestore.take(20),
                "sample_delete" to sampleDelete.take(20),
                "critical_protected" to criticalProtected,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/$sessionId/dry_run_result"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            System.err.println("[restore] dry run report failed: ${e.message}")
        }
    }

    companion object {
        const val BATCH_SIZE = 100
        const val BATCH_INTERVAL_MS = 30_000L
    }
}
