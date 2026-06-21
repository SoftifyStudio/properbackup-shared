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

/**
 * Full pipeline E2E test: create tar.gz → encrypt → decrypt → extract → atomic write → verify SHA-256.
 * Proves the complete restore protocol works end-to-end.
 */
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
        // Step 1: Create test files matching E2E dataset
        val testFiles = mapOf(
            "README.txt" to "ProperBackup E2E Test Dataset\nThis is a test file for restore verification.\n".toByteArray(),
            "binary-sample.bin" to ByteArray(512 * 1024) { (it % 256).toByte() },
            "file1.txt" to "File 1 content for testing\n".toByteArray(),
            "file2.txt" to "File 2 content for testing\n".toByteArray(),
        )

        val expectedHashes = testFiles.mapValues { sha256(it.value) }
        println("[E2E] Test files:")
        testFiles.forEach { (name, data) ->
            println("  $name: ${data.size} bytes, SHA-256=${expectedHashes[name]!!.substring(0, 16)}...")
        }

        // Step 2: Create tar.gz
        val tarGz = createTestTarGz(testFiles)
        println("[E2E] tar.gz: ${tarGz.size} bytes")

        // Step 3: Encrypt with ProperCrypto
        val encrypted = encrypt(PASSWORD, tarGz)
        println("[E2E] Encrypted: ${encrypted.size} bytes")

        // Step 4: Decrypt
        val decrypted = decrypt(PASSWORD, encrypted)
        assertArrayEquals(tarGz, decrypted, "Decrypted data should match original tar.gz")
        println("[E2E] Decrypted: ${decrypted.size} bytes (matches original)")

        // Step 5: Extract tar.gz
        val extracted = extractTarGz(decrypted)
        assertEquals(testFiles.size, extracted.size, "Should extract same number of files")
        println("[E2E] Extracted ${extracted.size} files from archive")

        // Step 6: Prepare restore components
        val stateFile = tempDir.resolve("restore_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val restoreDir = tempDir.resolve("restored-files")
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(restoreDir)
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)

        val guard = CriticalPathsGuard(configDir, stateDir)
        val fileRestorer = FileRestorer(stateStore) { it } // identity decrypt (already decrypted)
        val quarantineDir = tempDir.resolve("quarantine")
        val deleter = FileDeleter(quarantineDir, "e2e-session-001", stateStore)

        // Step 7: Create operations
        val operations = extracted.map { (name, data) ->
            val targetPath = restoreDir.resolve(name)
            assertFalse(guard.isProtected(targetPath.toString()), "Test files should not be protected")
            RecoveryOperation(
                id = UUID.randomUUID().toString(),
                type = OperationType.RESTORE,
                path = targetPath.toString(),
                sha256Target = sha256(data),
                sizeBytes = data.size.toLong(),
            )
        }
        stateStore.save(operations)
        println("[E2E] Created ${operations.size} restore operations")

        // Step 8: Execute atomic restore
        var restoredCount = 0
        for (op in operations) {
            val relativePath = restoreDir.relativize(Path.of(op.path)).toString()
            val fileData = extracted[relativePath]!!
            fileRestorer.restore(op, fileData)
            assertEquals(OperationState.DONE, op.state, "Operation should be DONE for $relativePath")
            restoredCount++
        }
        println("[E2E] Restored $restoredCount files atomically")

        // Step 9: Verify SHA-256 of all restored files
        for (op in operations) {
            val targetPath = Path.of(op.path)
            assertTrue(Files.exists(targetPath), "File should exist on disk: ${op.path}")
            val written = Files.readAllBytes(targetPath)
            val writtenHash = sha256(written)
            assertEquals(op.sha256Target, writtenHash, "SHA-256 must match for ${targetPath.fileName}")
            val originalName = restoreDir.relativize(targetPath).toString()
            assertEquals(expectedHashes[originalName], writtenHash, "Hash must match original for $originalName")
            println("[E2E] VERIFIED: $originalName SHA-256=${writtenHash.substring(0, 16)}... (${written.size} bytes)")
        }

        // Step 10: Verify state store recorded completion
        val finalState = stateStore.load()
        assertTrue(finalState.all { it.state == "DONE" }, "All operations should be DONE in state store")
        println("[E2E] State store: all ${finalState.size} operations DONE")

        println("[E2E] FULL PIPELINE VERIFIED: encrypt -> decrypt -> extract -> atomic write -> SHA-256 match")
    }

    @Test
    fun `resume after crash - restore only non-done operations`() {
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

        // Simulate restart: load state, skip DONE operations
        val savedState = stateStore.load()
        val doneIds = savedState.filter { it.state == "DONE" }.map { it.id }.toSet()
        assertEquals(1, doneIds.size, "Should have 1 DONE operation after crash")

        // Resume: restore remaining
        var resumed = 0
        for (op in ops) {
            if (doneIds.contains(op.id)) {
                println("[E2E] Skipping DONE: ${Path.of(op.path).fileName}")
                continue
            }
            val name = restoreDir.relativize(Path.of(op.path)).toString()
            fileRestorer.restore(op, extracted[name]!!)
            stateStore.markState(op.id, OperationState.DONE)
            resumed++
        }

        assertEquals(ops.size - 1, resumed, "Should resume ${ops.size - 1} operations")

        // Verify all files
        for (op in ops) {
            val targetPath = Path.of(op.path)
            assertTrue(Files.exists(targetPath))
            assertEquals(op.sha256Target, sha256(Files.readAllBytes(targetPath)))
        }
        println("[E2E] Resume-on-crash: verified ${ops.size} files after simulated crash")
    }

    @Test
    fun `quarantine delete and rollback`() {
        val stateFile = tempDir.resolve("delete_state.json")
        val stateStore = RestoreStateStore(stateFile)
        val quarantineDir = tempDir.resolve("quarantine")
        val deleter = FileDeleter(quarantineDir, "e2e-delete-session", stateStore)

        // Create files to delete
        val fileToDelete = tempDir.resolve("data/delete-me.txt")
        Files.createDirectories(fileToDelete.parent)
        Files.writeString(fileToDelete, "This file should be quarantined")

        val op = RecoveryOperation(
            id = UUID.randomUUID().toString(),
            type = OperationType.DELETE,
            path = fileToDelete.toString(),
        )
        stateStore.save(listOf(op))

        // Delete (quarantine)
        deleter.delete(op)
        assertFalse(Files.exists(fileToDelete), "Original file should be removed")
        assertEquals(OperationState.DONE, op.state)
        println("[E2E] File quarantined: ${fileToDelete.fileName}")

        // Rollback
        deleter.rollback()
        assertTrue(Files.exists(fileToDelete), "File should be restored from quarantine")
        assertEquals("This file should be quarantined", Files.readString(fileToDelete))
        println("[E2E] Rollback verified: file restored from quarantine")
    }

    @Test
    fun `critical paths guard blocks system paths`() {
        val configDir = tempDir.resolve("config")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(configDir)
        Files.createDirectories(stateDir)
        val guard = CriticalPathsGuard(configDir, stateDir)

        // These should be blocked
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

        // These should be allowed
        val allowedPaths = listOf(
            "/home/user/data/file.txt",
            "/var/www/html/index.html",
            tempDir.resolve("safe/file.txt").toString(),
        )
        for (path in allowedPaths) {
            assertFalse(guard.isProtected(path), "Should allow: $path")
        }
        println("[E2E] CriticalPathsGuard: blocked ${blockedPaths.size} system paths, allowed ${allowedPaths.size} user paths")
    }
}
