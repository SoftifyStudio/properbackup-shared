package pl.danielniemiec.properbackup.agent.transport

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class ResumableUpload(
    private val bufferUrl: String,
    private val token: String,
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
    private val retryPolicy: RetryPolicy = RetryPolicy.defaultPolicy(),
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    @Throws(Exception::class)
    fun upload(
        data: ByteArray,
        pathId: String,
        originalPath: String,
        flag: String,
        serverId: String?,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) {
        if (data.size <= chunkSizeBytes) {
            uploadRange(data, pathId, originalPath, flag, serverId, idempotencyKey, 0, data.size, data.size)
            return
        }

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSizeBytes, data.size)
            val chunk = data.copyOfRange(offset, end)
            uploadRange(chunk, pathId, originalPath, flag, serverId, idempotencyKey, offset, end, data.size)
            offset = end
        }
    }

    @Throws(Exception::class)
    fun resume(
        data: ByteArray,
        pathId: String,
        originalPath: String,
        flag: String,
        serverId: String?,
        idempotencyKey: String,
        startOffset: Int,
    ) {
        if (startOffset >= data.size) return

        var offset = startOffset
        while (offset < data.size) {
            val end = minOf(offset + chunkSizeBytes, data.size)
            val chunk = data.copyOfRange(offset, end)
            uploadRange(chunk, pathId, originalPath, flag, serverId, idempotencyKey, offset, end, data.size)
            offset = end
        }
    }

    private fun uploadRange(
        chunk: ByteArray,
        pathId: String,
        originalPath: String,
        flag: String,
        serverId: String?,
        idempotencyKey: String,
        rangeStart: Int,
        rangeEnd: Int,
        totalSize: Int,
    ) {
        circuitBreaker.execute {
            retryPolicy.execute {
                doUploadRange(chunk, pathId, originalPath, flag, serverId, idempotencyKey, rangeStart, rangeEnd, totalSize)
            }
        }
    }

    private fun doUploadRange(
        chunk: ByteArray,
        pathId: String,
        originalPath: String,
        flag: String,
        serverId: String?,
        idempotencyKey: String,
        rangeStart: Int,
        rangeEnd: Int,
        totalSize: Int,
    ) {
        val url = bufferUrl.trimEnd('/') + "/upload"
        val b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer $token")
            .header("X-Path-Id", pathId)
            .header("X-Original-Path", originalPath)
            .header("X-Flag", flag)
            .header("Idempotency-Key", idempotencyKey)
            .header("Content-Range", "bytes $rangeStart-${rangeEnd - 1}/$totalSize")

        if (!serverId.isNullOrBlank()) {
            b.header("X-Server-Id", serverId)
        }

        val req = b.PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build()
        val response = http.send(req, HttpResponse.BodyHandlers.discarding())
        val status = response.statusCode()

        if (status == 503) throw IOException("Buffer full (503)")
        if (status == 413) throw IOException("Payload too large (413)")
        if (status == 416) throw IOException("Range not satisfiable (416)")
        if (status in 500..599) throw IOException("Server error ($status)")
        if (status < 200 || status >= 300) throw IOException("Unexpected status: $status")
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024
    }
}
