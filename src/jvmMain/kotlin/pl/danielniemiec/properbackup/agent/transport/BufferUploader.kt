package pl.danielniemiec.properbackup.agent.transport

import com.google.gson.Gson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BufferUploader(
    baseBufferUrl: String,
    private val token: String,
    private val bandwidthKbps: Int = 0,
) : AutoCloseable {

    private val bufferUrl = baseBufferUrl.replace("/+$".toRegex(), "")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val retry = RetryPolicy.defaultPolicy()
    private val gson = Gson()

    constructor(bufferUrl: String, token: String) : this(bufferUrl, token, 0)

    @Throws(Exception::class)
    fun upload(encryptedBytes: ByteArray, pathId: String, originalPath: String, flag: String) {
        upload(encryptedBytes, pathId, originalPath, flag, null)
    }

    /** Rejestracja mapowania path_id → ścieżka rootu zanim powstanie pierwszy seal. */
    fun registerPaths(serverId: String, paths: List<Pair<String, String>>) {
        if (serverId.isBlank() || paths.isEmpty()) return
        val body = gson.toJson(
            mapOf(
                "serverId" to serverId,
                "paths" to paths.map { mapOf("pathId" to it.first, "originalPath" to it.second) },
            ),
        )
        postAgentJson("$bufferUrl/agent/register-paths", body)
    }

    /** Lista skonfigurowanych rootów — widoczność w panelu od pierwszego cyklu. */
    fun postBackupRoots(machineId: String, roots: List<Pair<String, String>>) {
        if (machineId.isBlank() || roots.isEmpty()) return
        val body = gson.toJson(
            mapOf(
                "machineId" to machineId,
                "roots" to roots.map { mapOf("pathId" to it.first, "rootPath" to it.second) },
            ),
        )
        postAgentJson("$bufferUrl/agent/backup-roots", body)
    }

    private fun postAgentJson(url: String, json: String) {
        runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        }
    }

    fun fetchPathsIdxForEmbed(serverId: String?): ByteArray {
        if (serverId.isNullOrBlank()) return ByteArray(0)
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/paths.idx"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) ByteArray(0) else response.body()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    @Throws(Exception::class)
    fun upload(encryptedBytes: ByteArray, pathId: String, originalPath: String, flag: String, serverId: String?) {
        if (bandwidthKbps > 0) {
            uploadThrottled(encryptedBytes, pathId, originalPath, flag, serverId)
        } else {
            retry.execute { doUpload(encryptedBytes, pathId, originalPath, flag, serverId) }
        }
    }

    private fun uploadThrottled(data: ByteArray, pathId: String, originalPath: String, flag: String, serverId: String?) {
        val bytesPerSec = bandwidthKbps.toLong() * 1024
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            val chunkFlag = if (end == data.size) flag else "A"
            retry.execute { doUpload(chunk, "$pathId-$offset", originalPath, chunkFlag, serverId) }
            offset = end
            if (offset < data.size && bytesPerSec > 0) {
                val sleepMs = (chunk.size * 1000L) / bytesPerSec
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
    }

    private fun doUpload(encryptedBytes: ByteArray, pathId: String, originalPath: String, flag: String, serverId: String?) {
        val b = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl/upload"))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer $token")
            .header("X-Path-Id", pathId)
            .header("X-Original-Path", originalPath)
            .header("X-Flag", flag)
        if (!serverId.isNullOrBlank()) {
            b.header("X-Server-Id", serverId)
        }
        val req = b.POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBytes)).build()
        val response = http.send(req, HttpResponse.BodyHandlers.discarding())
        val status = response.statusCode()
        if (status == 503) throw IOException("Buffer full (503) — will retry later")
        if (status == 413) throw IOException("Payload too large (413)")
        if (status < 200 || status >= 300) throw IOException("Unexpected status: $status")
    }

    override fun close() {
        /* HttpClient is not Closeable in Java 11+ default impl — nothing */
    }

    companion object {
        private const val CHUNK_SIZE = 64 * 1024
    }
}
