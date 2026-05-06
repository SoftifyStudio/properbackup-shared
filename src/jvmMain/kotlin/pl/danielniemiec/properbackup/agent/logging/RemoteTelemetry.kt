package pl.danielniemiec.properbackup.agent.logging

import com.google.gson.Gson
import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RemoteTelemetry(private val global: GlobalConfig, private val machineId: String) {
    private val gson = Gson()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val token = global.agent.upload_token
    private val base = global.agent.buffer_url.trim().removeSuffix("/")
    private val enabled = token.isNotBlank() && base.isNotBlank()

    fun log(component: String, eventCode: Int, message: String, level: String = "INFO", meta: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val body = gson.toJson(
            mapOf(
                "machineId" to machineId,
                "component" to component,
                "eventCode" to eventCode,
                "level" to level,
                "message" to message,
                "meta" to meta,
            ),
        )
        post("$base/agent/logs", body)
    }

    fun fileState(fullPath: String, status: String) {
        if (!enabled) return
        val body = gson.toJson(
            mapOf(
                "machineId" to machineId,
                "fullPath" to fullPath,
                "status" to status,
            ),
        )
        post("$base/agent/file-state", body)
    }

    fun fileStateBatch(fullPaths: List<String>, status: String) {
        if (!enabled || fullPaths.isEmpty()) return
        val files = fullPaths.map { mapOf("fullPath" to it, "status" to status) }
        val body = gson.toJson(
            mapOf(
                "machineId" to machineId,
                "files" to files,
            ),
        )
        post("$base/agent/file-states", body)
    }

    /** Stan dla panelu (Spotlight-style): phase ∈ indexing | uploading | idle | waiting | error */
    fun agentStatus(phase: String, detail: String? = null) {
        agentStatus(phase, detail, null, null, null)
    }

    fun agentStatus(phase: String, detail: String?, totalFiles: Int?, scannedFiles: Int?, currentFile: String?) {
        if (!enabled) return
        val map = linkedMapOf<String, Any?>(
            "machineId" to machineId,
            "phase" to phase,
            "detail" to detail,
        )
        if (totalFiles != null) map["totalFiles"] = totalFiles
        if (scannedFiles != null) map["scannedFiles"] = scannedFiles
        if (currentFile != null) map["currentFile"] = currentFile
        post("$base/agent/status", gson.toJson(map))
    }

    private fun post(url: String, body: String) {
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            client.send(req, HttpResponse.BodyHandlers.discarding())
        }
    }
}
