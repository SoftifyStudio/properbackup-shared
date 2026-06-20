package pl.danielniemiec.properbackup.core.restore

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class RestoreOrchestratorTest {

    companion object {
        private val tmpDir = Files.createTempDirectory("restore-orchestrator-test")
        private val quarantineDir = tmpDir.resolve(".quarantine")
        private val stateStore = RestoreStateStore(tmpDir.resolve("test.db"))
        private val fileRestorer = FileRestorer(quarantineDir)
        private val fileDeleter = FileDeleter(fileRestorer)

        @JvmStatic
        @AfterAll
        fun cleanup() {
            stateStore.close()
            tmpDir.toFile().deleteRecursively()
        }
    }

    private val reportedProgress = mutableListOf<Triple<Int, Int, Long>>()
    private var reportedComplete: Pair<Boolean, String?>? = null
    private var reportedDryRun: Map<String, Any?>? = null

    private val reporter = object : RestoreProgressReporter {
        override fun reportProgress(sessionId: String, filesRestoredDelta: Int, filesDeletedDelta: Int, bytesDelta: Long) {
            reportedProgress.add(Triple(filesRestoredDelta, filesDeletedDelta, bytesDelta))
        }
        override fun reportComplete(sessionId: String, success: Boolean, failureReason: String?) {
            reportedComplete = success to failureReason
        }
        override fun reportDryRunResult(
            sessionId: String, filesToRestore: Int, filesToDelete: Int, filesUnchanged: Int,
            totalBytes: Long, sampleRestore: List<String>, sampleDelete: List<String>,
            criticalProtected: Int, estimatedDurationSeconds: Int?,
        ) {
            reportedDryRun = mapOf(
                "filesToRestore" to filesToRestore,
                "filesToDelete" to filesToDelete,
                "filesUnchanged" to filesUnchanged,
                "totalBytes" to totalBytes,
                "criticalProtected" to criticalProtected,
            )
        }
    }

    private val fileContents = mutableMapOf<String, ByteArray>()

    private val fileProvider = object : RestoreFileProvider {
        override fun openFileStream(path: String, sha256: String?): InputStream? {
            val data = fileContents[path] ?: return null
            return ByteArrayInputStream(data)
        }
    }

    @BeforeEach
    fun clean() {
        stateStore.cleanup("test-session")
        reportedProgress.clear()
        reportedComplete = null
        reportedDryRun = null
        fileContents.clear()
        val dataDir = tmpDir.resolve("data")
        if (Files.exists(dataDir)) dataDir.toFile().deleteRecursively()
        Files.createDirectories(dataDir)
        if (Files.exists(quarantineDir)) quarantineDir.toFile().deleteRecursively()
    }

    @Test
    fun `computeDryRun reports diff to buffer`() {
        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, reporter, fileProvider)
        val snapshot = mapOf(
            "/app/a.txt" to SnapshotDiff.FileEntry("/app/a.txt", "h1", 100),
            "/app/b.txt" to SnapshotDiff.FileEntry("/app/b.txt", "h2", 200),
        )
        val local = mapOf(
            "/app/a.txt" to SnapshotDiff.FileEntry("/app/a.txt", "h1", 100),
            "/app/c.txt" to SnapshotDiff.FileEntry("/app/c.txt", "h3", 50),
        )
        val diff = orchestrator.computeDryRun("test-session", snapshot, local)
        assertNotNull(reportedDryRun)
        assertEquals(1, reportedDryRun!!["filesToRestore"])
        assertEquals(1, reportedDryRun!!["filesToDelete"])
        assertEquals(1, reportedDryRun!!["filesUnchanged"])
        assertEquals(200L, reportedDryRun!!["totalBytes"])
    }

    @Test
    fun `planOperations creates operations in state store`() {
        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, reporter, fileProvider)
        val diff = SnapshotDiff.DiffResult(
            filesToRestore = listOf(SnapshotDiff.FileEntry("/app/new.txt", "h1", 100)),
            filesToDelete = listOf(SnapshotDiff.FileEntry("/app/old.txt", null, 50)),
            filesUnchanged = emptyList(),
            criticalProtected = emptyList(),
            totalRestoreBytes = 100,
            totalDeleteBytes = 50,
        )
        val ops = orchestrator.planOperations("test-session", diff)
        assertEquals(2, ops.size)
        assertEquals(RestoreOperation.OperationType.RESTORE, ops[0].type)
        assertEquals(RestoreOperation.OperationType.DELETE, ops[1].type)

        val stored = stateStore.allOperations("test-session")
        assertEquals(2, stored.size)
    }

    @Test
    fun `executeRestore processes all operations`() {
        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, reporter, fileProvider)
        val content = "file content".toByteArray()
        val sha256 = SnapshotDiff.sha256Hex(content)
        val targetPath = tmpDir.resolve("data/restored.txt").toAbsolutePath().toString()
        fileContents[targetPath] = content

        val diff = SnapshotDiff.DiffResult(
            filesToRestore = listOf(SnapshotDiff.FileEntry(targetPath, sha256, content.size.toLong())),
            filesToDelete = emptyList(),
            filesUnchanged = emptyList(),
            criticalProtected = emptyList(),
            totalRestoreBytes = content.size.toLong(),
            totalDeleteBytes = 0,
        )
        orchestrator.planOperations("test-session", diff)
        val result = orchestrator.executeRestore("test-session")
        assertTrue(result.success)
        assertEquals(1, result.filesRestored)
        assertEquals(0, result.filesDeleted)
        assertTrue(Files.exists(tmpDir.resolve("data/restored.txt")))
    }

    @Test
    fun `cancel stops execution midway`() {
        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, reporter, fileProvider)
        orchestrator.cancel()
        stateStore.insertOperation(
            RestoreOperation(
                id = "op-cancel",
                recoverySessionId = "test-session",
                type = RestoreOperation.OperationType.RESTORE,
                path = "/app/file.txt",
                pathHash = SnapshotDiff.pathHash("/app/file.txt"),
                sha256Target = null,
                sha256Local = null,
                sizeBytes = 100,
                state = RestoreOperation.OperationState.PENDING,
            ),
        )
        val result = orchestrator.executeRestore("test-session")
        assertFalse(result.success)
        assertTrue(result.cancelledMidway)
    }

    @Test
    fun `executeRestore with empty pending is success`() {
        val orchestrator = RestoreOrchestrator(stateStore, fileRestorer, fileDeleter, reporter, fileProvider)
        val result = orchestrator.executeRestore("test-session")
        assertTrue(result.success)
        assertEquals(0, result.filesRestored)
    }
}
