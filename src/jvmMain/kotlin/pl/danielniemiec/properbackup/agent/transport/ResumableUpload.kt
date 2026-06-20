package pl.danielniemiec.properbackup.agent.transport

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Resumable upload with Content-Range support (HR-7 from agent-vps-master-spec).
 *
 * Protocol:
 * 1. HEAD /inbox/{userId}/{pathId}/{sha256} -> get bytes-already-received
 * 2. PUT with Content-Range: bytes start-end/total + Idempotency-Key
 * 3. POST /inbox/{userId}/{pathId}/{sha256}/seal when complete
 *
 * Integrates with [CircuitBreaker] for fault tolerance.
 */
class ResumableUpload(
    private val bufferUrl: String,
    private val token: String,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
    private val maxRetries: Int = 3,
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Upload data with resume capability.
     * If the upload was partially completed, resumes from the last confirmed byte.
     */
    @Throws(IOException::class)
    fun upload(
        encryptedData: ByteArray,
        userId: String,
        pathId: String,
        serverId: String?,
    ) {
        val sha256 = sha256Hex(encryptedData)
        val idempotencyKey = UUID.randomUUID().toString()
        val totalSize = encryptedData.size.toLong()

        // 1. Check resume point
        val resumeFrom = queryResumePoint(userId, pathId, sha256)
        if (resumeFrom >= totalSize) {
            println("[ResumableUpload] Already uploaded $pathId (${totalSize} bytes)")
            return
        }

        // 2. Upload in chunks with Content-Range
        var offset = resumeFrom
        while (offset < totalSize) {
            val end = minOf(offset + chunkSizeBytes, totalSize.toInt().toLong())
            val chunk = encryptedData.copyOfRange(offset.toInt(), end.toInt())

            uploadChunk(userId, pathId, sha256, chunk, offset, end - 1, totalSize, idempotencyKey, serverId)
            offset = end
        }

        println("[ResumableUpload] Upload complete: $pathId ($totalSize bytes, sha256=$sha256)")
    }

    private fun queryResumePoint(userId: String, pathId: String, sha256: String): Long {
        return try {
            circuitBreaker.execute {
                val url = "$bufferUrl/inbox/$userId/$pathId/$sha256"
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $token")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()

                val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                when (resp.statusCode()) {
                    200 -> resp.headers().firstValueAsLong("Content-Length").orElse(0L)
                    404 -> 0L
                    else -> 0L
                }
            }
        } catch (e: CircuitOpenException) {
            throw IOException("Circuit breaker open: ${e.message}", e)
        } catch (e: Exception) {
            0L
        }
    }

    private fun uploadChunk(
        userId: String,
        pathId: String,
        sha256: String,
        chunk: ByteArray,
        rangeStart: Long,
        rangeEnd: Long,
        totalSize: Long,
        idempotencyKey: String,
        serverId: String?,
    ) {
        var lastException: Exception? = null
        val backoffs = longArrayOf(1000, 5000, 30000)

        for (attempt in 0 until maxRetries) {
            try {
                circuitBreaker.execute {
                    val url = "$bufferUrl/inbox/$userId/$pathId/$sha256"
                    val builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Range", "bytes $rangeStart-$rangeEnd/$totalSize")
                        .header("Idempotency-Key", idempotencyKey)
                    if (!serverId.isNullOrBlank()) {
                        builder.header("X-Server-Id", serverId)
                    }
                    val req = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build()

                    val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                    val status = resp.statusCode()
                    if (status == 416) throw IOException("Range not satisfiable (416) — gap in upload")
                    if (status == 503) throw IOException("Buffer full (503)")
                    if (status < 200 || status >= 300) throw IOException("Upload chunk failed: $status")
                }
                return
            } catch (e: CircuitOpenException) {
                throw IOException("Circuit breaker open during upload: ${e.message}", e)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delay = backoffs[minOf(attempt, backoffs.size - 1)]
                    System.err.println("[ResumableUpload] attempt ${attempt + 1}/$maxRetries failed: ${e.message}, retry in ${delay}ms")
                    try {
                        Thread.sleep(delay)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Upload interrupted", ie)
                    }
                }
            }
        }
        throw IOException("Upload failed after $maxRetries attempts", lastException)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024 // 4 MB

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
