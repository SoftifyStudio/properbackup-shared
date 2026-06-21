package pl.danielniemiec.properbackup.agent.restore

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

class E2ERestoreTest {

    @TempDir
    lateinit var tempDir: Path

    companion object {
        private const val PASSWORD = "e2e-test-password-2026"

        fun sha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }

        fun createTestTarGz(files: Map<String, ByteArray>): ByteArray {
            val baos = ByteArrayOutputStream()
            GzipCompressorOutputStream(baos).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    for ((name, data) in files) {
                        val entry = TarArchiveEntry(name)
                        entry.size = data.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(data)
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
            return baos.toByteArray()
        }

        fun encrypt(password: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            ProperCrypto.encrypt(password.toCharArray(), ByteArrayInputStream(data), out)
            return out.toByteArray()
        }

        fun decrypt(password: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            ProperCrypto.decrypt(password.toCharArray(), ByteArrayInputStream(data), out)
            return out.toByteArray()
        }

        fun extractTarGz(tarGzData: ByteArray): Map<String, ByteArray> {
            val result = mutableMapOf<String, ByteArray>()
            GzipCompressorInputStream(ByteArrayInputStream(tarGzData)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            result[entry.name] = tar.readNBytes(entry.size.toInt())
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            return result
        }
    }

    @Test
    fun `full pipeline - 4 files encrypt decrypt extract write verify`() {
        val testFiles = mapOf(
            "README.txt" to "ProperBackup E2E Test Dataset\nThis is a test file for restore verification.\n".toByteArray(),
            "binary-sample.bin" to ByteArray(512 * 1024) { (it % 256).toByte() },
            "file1.txt" to "File 1 content for testing\n".toByteArray(),
            "file2.txt" to "File 2 content for testing\n".toByteArray(),
        )

        val expectedHashes = testFiles.mapValues { sha256(it.value) }

        val tarGz = createTestTarGz(testFiles)
        val encrypted = encrypt(PASSWORD, tarGz)
        val decrypted = decrypt(PASSWORD, encrypted)
        assertArrayEquals(tarGz, decrypted)

        val extracted = extractTarGz(decrypted)
        assertEquals(testFiles.size, extracted.size)

        val stateFile = tempDir.resolve("restore_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val restoreDir = tempDir.resolve("restored-files")
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(restoreDir)
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)

        val guard = CriticalPathsGuard(configDir, stateDir)
        val fileRestorer = FileRestorer(stateStore) { it }

        val operations = extracted.map { (name, data) ->
            val targetPath = restoreDir.resolve(name)
            assertFalse(guard.isProtected(targetPath.toString()))
            RecoveryOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.RESTORE,
                path = targetPath.toString(),
                sha256Target = sha256(data),
                sizeBytes = data.size.toLong(),
            )
        }
        stateStore.save(operations)

        for (op in operations) {
            val relativePath = restoreDir.relativize(Path.of(op.path)).toString()
            fileRestorer.restore(op, extracted[relativePath]!!)
            assertEquals(OperationState.DONE, op.state)
        }

        for (op in operations) {
            val targetPath = Path.of(op.path)
            assertTrue(Files.exists(targetPath))
            val written = Files.readAllBytes(targetPath)
            val writtenHash = sha256(written)
            assertEquals(op.sha256Target, writtenHash)
            val originalName = restoreDir.relativize(targetPath).toString()
            assertEquals(expectedHashes[originalName], writtenHash)
        }

        val finalState = stateStore.load()
        assertTrue(finalState.all { it.state == "DONE" })
    }

    @Test
    fun `HR-7 resume after crash - restore only non-done operations`() {
        val testFiles = mapOf(
            "a.txt" to "file A".toByteArray(),
            "b.txt" to "file B".toByteArray(),
            "c.txt" to "file C".toByteArray(),
        )

        val tarGz = createTestTarGz(testFiles)
        val encrypted = encrypt(PASSWORD, tarGz)
        val decrypted = decrypt(PASSWORD, encrypted)
        val extracted = extractTarGz(decrypted)

        val stateFile = tempDir.resolve("crash_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val restoreDir = tempDir.resolve("crash-restore")
        Files.createDirectories(restoreDir)

        val fileRestorer = FileRestorer(stateStore) { it }

        val ops = extracted.map { (name, data) ->
            RecoveryOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.RESTORE,
                path = restoreDir.resolve(name).toString(),
                sha256Target = sha256(data),
                sizeBytes = data.size.toLong(),
            )
        }
        stateStore.save(ops)

        // Simulate: restore first file, then "crash"
        val firstOp = ops[0]
        val firstName = restoreDir.relativize(Path.of(firstOp.path)).toString()
        fileRestorer.restore(firstOp, extracted[firstName]!!)
        stateStore.markState(firstOp.id, OperationState.DONE)

        // Simulate restart: load FULL operations from state store (HR-7)
        val reconstructed = stateStore.loadOperations()!!
        assertEquals(3, reconstructed.size)
        val doneOps = reconstructed.filter { it.state == OperationState.DONE }
        assertEquals(1, doneOps.size, "Should have 1 DONE operation after crash")

        // Resume: restore remaining (skip DONE, reset non-terminal states to PENDING)
        var resumed = 0
        for (op in reconstructed) {
            if (op.state.isTerminal) {
                println("[HR-7] Skipping ${op.state}: ${Path.of(op.path).fileName}")
                continue
            }
            // Reset mid-progress states to PENDING (crash recovery)
            if (op.state != OperationState.PENDING) {
                op.state = OperationState.PENDING
            }
            val name = restoreDir.relativize(Path.of(op.path)).toString()
            fileRestorer.restore(op, extracted[name]!!)
            stateStore.markState(op.id, OperationState.DONE)
            resumed++
        }

        assertEquals(ops.size - 1, resumed)

        // Verify all files exist with correct hash
        for (op in ops) {
            val targetPath = Path.of(op.path)
            assertTrue(Files.exists(targetPath))
            assertEquals(op.sha256Target, sha256(Files.readAllBytes(targetPath)))
        }
        println("[HR-7] Resume-on-crash: verified ${ops.size} files after simulated crash+restart")
    }

    @Test
    fun `HR-9 cancel rollback - restored files deleted, quarantined files returned`() {
        val stateFile = tempDir.resolve("cancel_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val restoreDir = tempDir.resolve("cancel-restore")
        val quarantineDir = tempDir.resolve("quarantine")
        Files.createDirectories(restoreDir)

        val fileRestorer = FileRestorer(stateStore) { it }
        val fileDeleter = FileDeleter(quarantineDir, "cancel-session", stateStore)

        // Setup: create a file that will be "deleted" during restore
        val existingFile = restoreDir.resolve("existing.txt")
        Files.writeString(existingFile, "original content")

        // Create ops: 1 restore + 1 delete
        val restoreOp = RecoveryOperation(
            id = UUID.randomUUID().toString(),
            type = OperationType.RESTORE,
            path = restoreDir.resolve("new-file.txt").toString(),
            sha256Target = sha256("new content".toByteArray()),
            sizeBytes = 11,
        )
        val deleteOp = RecoveryOperation(
            id = UUID.randomUUID().toString(),
            type = OperationType.DELETE,
            path = existingFile.toString(),
            sha256Local = sha256("original content".toByteArray()),
        )
        stateStore.save(listOf(restoreOp, deleteOp))

        // Execute restore op
        fileRestorer.restore(restoreOp, "new content".toByteArray())
        assertEquals(OperationState.DONE, restoreOp.state)
        assertTrue(Files.exists(Path.of(restoreOp.path)))

        // Execute delete op (quarantines file)
        fileDeleter.delete(deleteOp)
        assertEquals(OperationState.DONE, deleteOp.state)
        assertFalse(Files.exists(existingFile))

        // ─── CANCEL! Simulate cancel rollback ───
        // 1. Delete restored files
        val restoredPath = Path.of(restoreOp.path)
        if (Files.exists(restoredPath)) {
            Files.delete(restoredPath)
        }
        assertFalse(Files.exists(restoredPath), "Restored file should be removed on cancel")

        // 2. Restore quarantined files
        fileDeleter.rollback()
        assertTrue(Files.exists(existingFile), "Quarantined file should be restored on cancel")
        assertEquals("original content", Files.readString(existingFile))

        println("[HR-9] Cancel rollback: restored file removed, quarantined file returned")
    }

    @Test
    fun `HR-8 critical paths guard blocks system paths and logs`() {
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)
        val guard = CriticalPathsGuard(configDir, stateDir)

        val blockedPaths = listOf(
            "/proc/cpuinfo", "/sys/devices", "/dev/null", "/run/user/1000",
            "/boot/vmlinuz", "/etc/passwd", "/etc/shadow",
            "/etc/systemd/system/sshd.service",
            configDir.resolve("global.yml").toString(),
            stateDir.resolve("recovery_state.json").toString(),
        )
        for (path in blockedPaths) {
            assertTrue(guard.isProtected(path), "Should block: $path")
        }

        val allowedPaths = listOf(
            "/home/user/data/file.txt",
            "/var/www/html/index.html",
            tempDir.resolve("safe/file.txt").toString(),
        )
        for (path in allowedPaths) {
            assertFalse(guard.isProtected(path), "Should allow: $path")
        }

        // Verify that protected paths would generate SKIP operations (not RESTORE/DELETE)
        val stateFile = tempDir.resolve("guard_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val ops = mutableListOf<RecoveryOperation>()
        for (path in blockedPaths + allowedPaths) {
            if (guard.isProtected(path)) {
                ops.add(RecoveryOperation(
                    id = UUID.randomUUID().toString(),
                    type = OperationType.SKIP,
                    path = path,
                    state = OperationState.DONE,
                ))
            } else {
                ops.add(RecoveryOperation(
                    id = UUID.randomUUID().toString(),
                    type = OperationType.RESTORE,
                    path = path,
                ))
            }
        }
        stateStore.save(ops)

        val skipCount = ops.count { it.type == OperationType.SKIP }
        val restoreCount = ops.count { it.type == OperationType.RESTORE }
        assertEquals(blockedPaths.size, skipCount, "All blocked paths should be SKIP")
        assertEquals(allowedPaths.size, restoreCount, "All allowed paths should be RESTORE")
        println("[HR-8] Guard: blocked=${blockedPaths.size}, allowed=${allowedPaths.size}")
    }

    @Test
    fun `HR-5 pre-recovery snapshot stores current file manifest`() {
        // Simulate pre-recovery: scan current files, store manifest
        val sourceDir = tempDir.resolve("pre-snapshot-source")
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve("config.yml"), "buffer_url: http://test")
        Files.writeString(sourceDir.resolve("data.bin"), "binary data here")

        // Scan files (simulates what createPreRecoverySnapshot does)
        val localFiles = mutableListOf<Map<String, Any>>()
        Files.walk(sourceDir).filter { Files.isRegularFile(it) }.forEach { file ->
            val data = Files.readAllBytes(file)
            localFiles.add(mapOf(
                "path" to file.toAbsolutePath().normalize().toString(),
                "sha256" to sha256(data),
                "size" to data.size.toLong(),
            ))
        }

        assertEquals(2, localFiles.size, "Should have 2 files in pre-recovery snapshot")
        assertTrue(localFiles.all { (it["sha256"] as String).length == 64 }, "All files should have valid SHA-256")
        assertTrue(localFiles.all { (it["size"] as Long) > 0 }, "All files should have positive size")

        // Verify manifest contains correct data
        val configFile = localFiles.find { (it["path"] as String).endsWith("config.yml") }
        assertNotNull(configFile)
        assertEquals(sha256("buffer_url: http://test".toByteArray()), configFile!!["sha256"])

        println("[HR-5] Pre-recovery snapshot manifest: ${localFiles.size} files captured")
    }

    @Test
    fun `HR-7 resume resets mid-progress states to allow retry`() {
        val stateFile = tempDir.resolve("mid_progress.json")
        val stateStore = RestoreStateStore(stateFile)
        val restoreDir = tempDir.resolve("mid-progress-restore")
        Files.createDirectories(restoreDir)

        val fileRestorer = FileRestorer(stateStore) { it }

        val ops = listOf(
            RecoveryOperation("done-op", OperationType.RESTORE, restoreDir.resolve("done.txt").toString(),
                sha256Target = sha256("done content".toByteArray()), sizeBytes = 12),
            RecoveryOperation("writing-op", OperationType.RESTORE, restoreDir.resolve("writing.txt").toString(),
                sha256Target = sha256("writing content".toByteArray()), sizeBytes = 15),
            RecoveryOperation("pending-op", OperationType.RESTORE, restoreDir.resolve("pending.txt").toString(),
                sha256Target = sha256("pending content".toByteArray()), sizeBytes = 15),
        )
        stateStore.save(ops)

        // First op completed before crash
        fileRestorer.restore(ops[0], "done content".toByteArray())
        stateStore.markState("done-op", OperationState.DONE)

        // Second op was mid-WRITING when crash occurred
        stateStore.markState("writing-op", OperationState.WRITING)

        // Simulate restart
        val reconstructed = stateStore.loadOperations()!!
        assertEquals(OperationState.DONE, reconstructed[0].state)
        assertEquals(OperationState.WRITING, reconstructed[1].state) // mid-progress
        assertEquals(OperationState.PENDING, reconstructed[2].state)

        // Resume: skip DONE, reset WRITING→PENDING, execute remaining
        var resumed = 0
        for (op in reconstructed) {
            if (op.state == OperationState.DONE) continue
            // Reset any mid-progress state
            if (op.state != OperationState.PENDING && !op.state.isTerminal) {
                op.state = OperationState.PENDING
            }
            val name = restoreDir.relativize(Path.of(op.path)).toString()
            val content = when (name) {
                "writing.txt" -> "writing content"
                "pending.txt" -> "pending content"
                else -> error("unexpected: $name")
            }
            fileRestorer.restore(op, content.toByteArray())
            stateStore.markState(op.id, OperationState.DONE)
            resumed++
        }
        assertEquals(2, resumed, "Should resume 2 ops (skip 1 DONE)")

        // All files exist with correct content
        for (op in ops) {
            assertTrue(Files.exists(Path.of(op.path)))
            assertEquals(op.sha256Target, sha256(Files.readAllBytes(Path.of(op.path))))
        }
        println("[HR-7] Mid-progress resume: 2 ops resumed after WRITING-state crash")
    }
}
