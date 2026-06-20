package pl.danielniemiec.properbackup.shared.transport

import pl.danielniemiec.properbackup.shared.host.HostAdapter
import pl.danielniemiec.properbackup.shared.host.LogLevel
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Resumable upload protocol for large files (>100MB).
 *
 * Per spec [AGT-B3]:
 * 1. HEAD /inbox/{userId}/{pathId}/{sha256} → get resume offset
 * 2. PUT  /inbox/{userId}/{pathId}/{sha256} with Content-Range → upload from offset
 * 3. POST /inbox/{userId}/{pathId}/{sha256}/seal → finalize
 *
 * Idempotency: Uses sha256 as Idempotency-Key to prevent duplicate writes.
 */
class ResumableUpload(
    private val host: HostAdapter,
    private val jwtClient: JwtClient,
    private val circuitBreaker: CircuitBreaker,
    private val bufferUrl: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    data class UploadResult(
        val success: Boolean,
        val bytesUploaded: Long,
        val resumed: Boolean,
        val resumeOffset: Long = 0,
    )

    /**
     * Upload encrypted data with resume support.
     *
     * @param userId owner user ID
     * @param pathId path identifier on buffer
     * @param sha256 hash of encrypted content (used as idempotency key + resume ID)
     * @param data encrypted content stream
     * @param totalSize total bytes to upload
     */
    fun upload(
        userId: String,
        pathId: String,
        sha256: String,
        data: InputStream,
        totalSize: Long,
    ): UploadResult {
        val inboxPath = "/inbox/$userId/$pathId/$sha256"

        // Step 1: Check resume point
        val resumeOffset = circuitBreaker.execute { checkResumePoint(inboxPath) }

        if (resumeOffset >= totalSize) {
            host.log(LogLevel.DEBUG, "Upload already complete (offset=$resumeOffset >= total=$totalSize)")
            return UploadResult(success = true, bytesUploaded = 0, resumed = true, resumeOffset = resumeOffset)
        }

        // Skip already-uploaded bytes
        if (resumeOffset > 0) {
            host.log(LogLevel.INFO, "Resuming upload from offset $resumeOffset/$totalSize")
            data.skip(resumeOffset)
        }

        // Step 2: Upload with Content-Range
        val bytesUploaded = circuitBreaker.execute {
            uploadRange(inboxPath, data, resumeOffset, totalSize, sha256)
        }

        // Step 3: Seal
        circuitBreaker.execute { seal(inboxPath) }

        return UploadResult(
            success = true,
            bytesUploaded = bytesUploaded,
            resumed = resumeOffset > 0,
            resumeOffset = resumeOffset,
        )
    }

    private fun checkResumePoint(inboxPath: String): Long {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl$inboxPath"))
            .header("Authorization", "Bearer ${jwtClient.accessToken()}")
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())

        return when (response.statusCode()) {
            200 -> {
                response.headers().firstValueAsLong("Content-Length").orElse(0)
            }
            404 -> 0L // Not started yet
            else -> throw TransportException("HEAD $inboxPath failed: HTTP ${response.statusCode()}")
        }
    }

    private fun uploadRange(
        inboxPath: String,
        data: InputStream,
        offset: Long,
        totalSize: Long,
        sha256: String,
    ): Long {
        val remaining = totalSize - offset
        val end = totalSize - 1
        val contentRange = "bytes $offset-$end/$totalSize"

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl$inboxPath"))
            .header("Authorization", "Bearer ${jwtClient.accessToken()}")
            .header("Content-Range", contentRange)
            .header("Content-Type", "application/octet-stream")
            .header("Idempotency-Key", sha256)
            .PUT(HttpRequest.BodyPublishers.ofInputStream { data })
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        return when (response.statusCode()) {
            200, 201, 204 -> remaining
            413 -> throw TransportException("Payload too large: chunk exceeds buffer limit")
            403 -> throw SubscriptionExpiredException("Upload rejected: ${response.body()}")
            else -> throw TransportException("PUT $inboxPath failed: HTTP ${response.statusCode()} — ${response.body()}")
        }
    }

    private fun seal(inboxPath: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl$inboxPath/seal"))
            .header("Authorization", "Bearer ${jwtClient.accessToken()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        when (response.statusCode()) {
            200, 201, 204 -> { /* success */ }
            409 -> host.log(LogLevel.WARN, "Seal returned 409 (already sealed) — idempotent, continuing")
            else -> throw TransportException("POST $inboxPath/seal failed: HTTP ${response.statusCode()}")
        }
    }
}
