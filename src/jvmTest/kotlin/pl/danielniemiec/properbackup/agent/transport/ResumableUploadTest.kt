package pl.danielniemiec.properbackup.agent.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ResumableUploadTest {

    @Test
    fun `small data uploads as single range covering full body`() {
        val recorder = RequestRecorder()
        val upload = createUploadWithRecorder(recorder, chunkSize = 1024)
        val data = ByteArray(500) { it.toByte() }
        val key = UUID.randomUUID().toString()

        upload.upload(data, "pathA", "/home/test", "A", "srv1", key)

        assertEquals(1, recorder.requests.size)
        val req = recorder.requests[0]
        assertEquals("bytes 0-499/500", req.contentRange)
        assertEquals(key, req.idempotencyKey)
        assertEquals(500, req.bodySize)
    }

    @Test
    fun `large data splits into multiple ranges`() {
        val recorder = RequestRecorder()
        val chunkSize = 200
        val upload = createUploadWithRecorder(recorder, chunkSize)
        val data = ByteArray(500) { it.toByte() }
        val key = UUID.randomUUID().toString()

        upload.upload(data, "pathB", "/home/test2", "A", "srv1", key)

        assertEquals(3, recorder.requests.size)

        assertEquals("bytes 0-199/500", recorder.requests[0].contentRange)
        assertEquals(200, recorder.requests[0].bodySize)

        assertEquals("bytes 200-399/500", recorder.requests[1].contentRange)
        assertEquals(200, recorder.requests[1].bodySize)

        assertEquals("bytes 400-499/500", recorder.requests[2].contentRange)
        assertEquals(100, recorder.requests[2].bodySize)

        assertTrue(recorder.requests.all { it.idempotencyKey == key })
    }

    @Test
    fun `resume starts from given offset`() {
        val recorder = RequestRecorder()
        val chunkSize = 200
        val upload = createUploadWithRecorder(recorder, chunkSize)
        val data = ByteArray(500)
        val key = UUID.randomUUID().toString()

        upload.resume(data, "pathC", "/path", "A", "srv1", key, startOffset = 200)

        assertEquals(2, recorder.requests.size)
        assertEquals("bytes 200-399/500", recorder.requests[0].contentRange)
        assertEquals("bytes 400-499/500", recorder.requests[1].contentRange)
    }

    @Test
    fun `resume at or past end is no-op`() {
        val recorder = RequestRecorder()
        val upload = createUploadWithRecorder(recorder, chunkSize = 1024)

        upload.resume(ByteArray(100), "pathD", "/path", "A", "srv1", "key", startOffset = 100)
        assertEquals(0, recorder.requests.size)

        upload.resume(ByteArray(100), "pathD", "/path", "A", "srv1", "key", startOffset = 200)
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun `circuit breaker integration - opens after 3 failures`() {
        val breaker = CircuitBreaker(failureThreshold = 3, openDurationMs = 5000)
        var callCount = 0
        val upload = createFailingUpload(breaker) { callCount++ }

        repeat(3) {
            runCatching {
                upload.upload(ByteArray(10), "pathE", "/path", "A", "srv1")
            }
        }

        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            upload.upload(ByteArray(10), "pathE", "/path", "A", "srv1")
        }
        assertTrue(ex.message!!.contains("OPEN"))
    }

    @Test
    fun `idempotency key is auto-generated when not provided`() {
        val recorder = RequestRecorder()
        val upload = createUploadWithRecorder(recorder, chunkSize = 1024)

        upload.upload(ByteArray(10), "pathF", "/path", "A", null)
        upload.upload(ByteArray(10), "pathF", "/path", "A", null)

        assertEquals(2, recorder.requests.size)
        assertNotEquals(recorder.requests[0].idempotencyKey, recorder.requests[1].idempotencyKey)
    }

    data class RecordedRequest(
        val contentRange: String?,
        val idempotencyKey: String?,
        val bodySize: Int,
        val pathId: String,
        val serverId: String?,
    )

    class RequestRecorder {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
    }

    private fun createUploadWithRecorder(recorder: RequestRecorder, chunkSize: Int): TestableResumableUpload {
        return TestableResumableUpload(
            chunkSizeBytes = chunkSize,
            interceptor = { chunk, pathId, origPath, flag, serverId, idempotencyKey, rangeStart, rangeEnd, totalSize ->
                recorder.requests.add(
                    RecordedRequest(
                        contentRange = "bytes $rangeStart-${rangeEnd - 1}/$totalSize",
                        idempotencyKey = idempotencyKey,
                        bodySize = chunk.size,
                        pathId = pathId,
                        serverId = serverId,
                    )
                )
            }
        )
    }

    private fun createFailingUpload(breaker: CircuitBreaker, onCall: () -> Unit): TestableResumableUpload {
        return TestableResumableUpload(
            chunkSizeBytes = 1024,
            circuitBreaker = breaker,
            interceptor = { _, _, _, _, _, _, _, _, _ ->
                onCall()
                throw IOException("simulated 500")
            }
        )
    }

    class TestableResumableUpload(
        private val chunkSizeBytes: Int = 4 * 1024 * 1024,
        private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
        private val retryPolicy: RetryPolicy = RetryPolicy(1, 10),
        private val interceptor: (ByteArray, String, String, String, String?, String, Int, Int, Int) -> Unit,
    ) {
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
            chunk: ByteArray, pathId: String, originalPath: String, flag: String,
            serverId: String?, idempotencyKey: String, rangeStart: Int, rangeEnd: Int, totalSize: Int,
        ) {
            circuitBreaker.execute {
                retryPolicy.execute {
                    interceptor(chunk, pathId, originalPath, flag, serverId, idempotencyKey, rangeStart, rangeEnd, totalSize)
                }
            }
        }
    }
}
