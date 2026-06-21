package pl.danielniemiec.properbackup.agent.restore

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import pl.danielniemiec.properbackup.core.crypto.ProperCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Top-level restore orchestrator implementing the full recovery protocol (Section C).
 *
 * Flow:
 * 1. Poll buffer for recovery commands (dry_run / execute)
 * 2. On dry_run: compute diff, report to buffer
 * 3. On execute: create pre-recovery snapshot, execute per-file ops, verify, report
 */
class RestoreOrchestrator(
    private val bufferUrl: String,
    private val serverId: String,
    private val token: String,
    private val encryptionPassword: String,
    private val stateDir: Path,
    private val configDir: Path,
    private val backupRoots: List<String>,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val gson = Gson()

    data class RecoveryCommand(
        val command: String,
        val sessionId: String,
        val targetSnapshotId: Long,
    )

    /**
     * Poll buffer for pending recovery commands. Returns null if none.
     */
    fun pollForCommand(): RecoveryCommand? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/poll"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null
            val obj = JsonParser.parseString(res.body()).asJsonObject
            val commands = obj.getAsJsonArray("commands")
            if (commands == null || commands.isEmpty) return null
            val cmd = commands[0].asJsonObject
            RecoveryCommand(
                command = cmd.get("command").asString,
                sessionId = cmd.get("sessionId").asString,
                targetSnapshotId = cmd.get("targetSnapshotId").asLong,
            )
        } catch (e: Exception) {
            System.err.println("[restore] poll failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a recovery command (dry_run or execute).
     */
    fun executeCommand(cmd: RecoveryCommand) {
        println("[restore] Executing recovery command: ${cmd.command} session=${cmd.sessionId}")
        when (cmd.command) {
            "dry_run" -> executeDryRun(cmd)
            "execute" -> executeRestore(cmd)
            else -> System.err.println("[restore] Unknown command: ${cmd.command}")
        }
    }

    private fun executeDryRun(cmd: RecoveryCommand) {
        val reporter = RestoreProgressReporter(bufferUrl, cmd.sessionId, token)
        val guard = CriticalPathsGuard(configDir, stateDir, backupRoots)

        // Fetch snapshot manifest from buffer (archive_snapshot data)
        val snapshotFiles = fetchSnapshotFiles(cmd.targetSnapshotId)
        if (snapshotFiles == null) {
            reporter.reportComplete(false, "Cannot fetch snapshot manifest")
            return
        }

        // Compute diff against local filesystem
        val toRestore = mutableListOf<String>()
        val toDelete = mutableListOf<String>()
        var unchanged = 0
        var criticalProtected = 0
        var totalBytes = 0L

        val snapshotPathMap = snapshotFiles.associateBy { it.path }
        val localFiles = scanLocalFiles()
        val localPathMap = localFiles.associateBy { it.path }

        // Files in snapshot: restore if missing or different
        for (sf in snapshotFiles) {
            if (guard.isProtected(sf.path)) {
                criticalProtected++
                continue
            }
            val local = localPathMap[sf.path]
            if (local == null || local.sha256 != sf.sha256) {
                toRestore.add(sf.path)
                totalBytes += sf.sizeBytes
            } else {
                unchanged++
            }
        }

        // Local files not in snapshot: delete
        for (lf in localFiles) {
            if (!snapshotPathMap.containsKey(lf.path)) {
                if (guard.isProtected(lf.path)) {
                    criticalProtected++
                    continue
                }
                toDelete.add(lf.path)
            }
        }

        println("[restore] Dry run: restore=${toRestore.size}, delete=${toDelete.size}, unchanged=$unchanged, critical=$criticalProtected")

        reporter.reportDryRun(
            filesToRestore = toRestore.size,
            filesToDelete = toDelete.size,
            filesUnchanged = unchanged,
            totalBytes = totalBytes,
            sampleRestore = toRestore.shuffled().take(20),
            sampleDelete = toDelete.shuffled().take(20),
            criticalProtected = criticalProtected,
        )
    }

    private fun executeRestore(cmd: RecoveryCommand) {
        val reporter = RestoreProgressReporter(bufferUrl, cmd.sessionId, token)
        val guard = CriticalPathsGuard(configDir, stateDir, backupRoots)
        val quarantineDir = stateDir.resolve("quarantine")
        val stateFile = stateDir.resolve("recovery_state_${cmd.sessionId}.json")
        val stateStore = RestoreStateStore(stateFile)
        @Suppress("UNUSED_VARIABLE")
        val fileRestorer = FileRestorer(stateStore) { encrypted -> decrypt(encrypted) }
        val fileDeleter = FileDeleter(quarantineDir, cmd.sessionId, stateStore)

        try {
            // Phase 1: Create pre-recovery snapshot (backup current state)
            println("[restore] Creating pre-recovery snapshot...")
            createPreRecoverySnapshot(cmd.sessionId)

            // Phase 2: Fetch snapshot data and compute operations
            val snapshotFiles = fetchSnapshotFiles(cmd.targetSnapshotId)
            if (snapshotFiles == null) {
                reporter.reportComplete(false, "Cannot fetch snapshot manifest")
                return
            }

            val snapshotPathMap = snapshotFiles.associateBy { it.path }
            val localFiles = scanLocalFiles()
            val localPathMap = localFiles.associateBy { it.path }

            val operations = mutableListOf<RecoveryOperation>()

            // Build operation list
            for (sf in snapshotFiles) {
                if (guard.isProtected(sf.path)) continue
                val local = localPathMap[sf.path]
                if (local == null || local.sha256 != sf.sha256) {
                    operations.add(RecoveryOperation(
                        id = UUID.randomUUID().toString(),
                        type = OperationType.RESTORE,
                        path = sf.path,
                        sha256Target = sf.sha256,
                        sha256Local = local?.sha256,
                        sizeBytes = sf.sizeBytes,
                    ))
                }
            }
            for (lf in localFiles) {
                if (!snapshotPathMap.containsKey(lf.path) && !guard.isProtected(lf.path)) {
                    operations.add(RecoveryOperation(
                        id = UUID.randomUUID().toString(),
                        type = OperationType.DELETE,
                        path = lf.path,
                        sha256Local = lf.sha256,
                        sizeBytes = lf.sizeBytes,
                    ))
                }
            }

            // Resume support: check existing state
            val existingState = stateStore.load()
            val doneIds = existingState.filter { it.state == "DONE" }.map { it.id }.toSet()

            // Save initial state
            stateStore.save(operations)

            // Phase 3: Execute operations
            println("[restore] Executing ${operations.size} operations...")
            var restored = 0
            var deleted = 0

            // Download the encrypted archive pack for restores
            val archiveData = downloadArchivePack(cmd.targetSnapshotId)
            val extractedFiles = if (archiveData != null) extractTarGz(decrypt(archiveData)) else emptyMap()

            for (op in operations) {
                if (doneIds.contains(op.id)) {
                    op.state = OperationState.DONE
                    continue
                }

                try {
                    when (op.type) {
                        OperationType.RESTORE -> {
                            val fileData = extractedFiles[op.path]
                            if (fileData != null) {
                                // Write plaintext directly (already decrypted from archive)
                                restoreFileDirectly(op, fileData, stateStore)
                                reporter.trackRestored(op.sizeBytes)
                                restored++
                            } else {
                                op.state = OperationState.FAILED
                                op.error = "File not found in archive: ${op.path}"
                                stateStore.markState(op.id, OperationState.FAILED, op.error)
                                System.err.println("[restore] File not in archive: ${op.path}")
                            }
                        }
                        OperationType.DELETE -> {
                            fileDeleter.delete(op)
                            reporter.trackDeleted()
                            deleted++
                        }
                        OperationType.SKIP -> {
                            op.state = OperationState.DONE
                            stateStore.markState(op.id, OperationState.DONE)
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("[restore] Operation failed: ${op.path}: ${e.message}")
                }
            }

            // Phase 4: Verify final state
            println("[restore] Verifying final state...")
            val allDone = operations.all { it.state == OperationState.DONE }
            val failedOps = operations.filter { it.state == OperationState.FAILED }
            if (failedOps.isNotEmpty()) {
                System.err.println("[restore] ${failedOps.size} operations failed")
                failedOps.take(5).forEach { System.err.println("  FAILED: ${it.path}: ${it.error}") }
            }

            reporter.flush()
            reporter.reportComplete(success = allDone, verification = if (allDone) "OK" else "PARTIAL")
            println("[restore] Recovery complete: restored=$restored, deleted=$deleted, allDone=$allDone")

            // Cleanup state file on success
            if (allDone) stateStore.cleanup()

        } catch (e: Exception) {
            System.err.println("[restore] Recovery failed: ${e.message}")
            reporter.reportComplete(false, "Agent error: ${e.message}")
        }
    }

    private fun restoreFileDirectly(op: RecoveryOperation, plaintext: ByteArray, stateStore: RestoreStateStore) {
        val targetPath = Path.of(op.path)

        // Idempotent check
        if (Files.exists(targetPath) && op.sha256Target != null) {
            val existingHash = sha256(Files.readAllBytes(targetPath))
            if (existingHash == op.sha256Target) {
                op.state = OperationState.DONE
                stateStore.markState(op.id, OperationState.DONE)
                return
            }
        }

        op.state = OperationState.WRITING
        stateStore.markState(op.id, OperationState.WRITING)

        Files.createDirectories(targetPath.parent)
        val tmpFile = targetPath.resolveSibling(".recovery-${UUID.randomUUID()}.tmp")
        Files.write(tmpFile, plaintext)

        // Verify SHA-256
        op.state = OperationState.VERIFYING
        stateStore.markState(op.id, OperationState.VERIFYING)
        if (op.sha256Target != null) {
            val writtenHash = sha256(Files.readAllBytes(tmpFile))
            if (writtenHash != op.sha256Target) {
                Files.deleteIfExists(tmpFile)
                op.state = OperationState.FAILED
                op.error = "SHA-256 mismatch: expected=${op.sha256Target}, got=$writtenHash"
                stateStore.markState(op.id, OperationState.FAILED, op.error)
                throw RestoreException(op.error!!)
            }
        }

        // Atomic rename
        Files.move(tmpFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        op.state = OperationState.DONE
        stateStore.markState(op.id, OperationState.DONE)
    }

    private fun createPreRecoverySnapshot(sessionId: String) {
        // Use existing backup mechanism to create a snapshot of current state
        // For now, report to buffer that pre-recovery snapshot was requested
        try {
            val body = gson.toJson(mapOf("session_id" to sessionId, "snapshot_type" to "PRE_RECOVERY"))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/scan-complete"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
            println("[restore] Pre-recovery snapshot signal sent")
        } catch (e: Exception) {
            System.err.println("[restore] Pre-recovery snapshot signal failed: ${e.message}")
        }
    }

    data class SnapshotFile(val path: String, val sha256: String, val sizeBytes: Long)

    private fun fetchSnapshotFiles(snapshotId: Long): List<SnapshotFile>? {
        // Fetch the archive snapshot info and file list from buffer
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/snapshot/$snapshotId/files"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                System.err.println("[restore] Snapshot files fetch failed: ${res.statusCode()} ${res.body()}")
                null
            } else {
                val arr = JsonParser.parseString(res.body()).asJsonArray
                arr.map { el ->
                    val o = el.asJsonObject
                    SnapshotFile(
                        path = o.get("path").asString,
                        sha256 = o.get("sha256").asString,
                        sizeBytes = o.get("size_bytes").asLong,
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("[restore] Snapshot files fetch failed: ${e.message}")
            null
        }
    }

    private fun downloadArchivePack(snapshotId: Long): ByteArray? {
        return try {
            // Get the object_name from archive_snapshot
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/snapshot/$snapshotId/download"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() != 200) {
                // Fallback: try /objects/ endpoint with object name
                System.err.println("[restore] Direct download failed (${res.statusCode()}), trying object name lookup...")
                downloadByObjectName(snapshotId)
            } else {
                res.body()
            }
        } catch (e: Exception) {
            System.err.println("[restore] Archive download failed: ${e.message}")
            downloadByObjectName(snapshotId)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun downloadByObjectName(snapshotId: Long): ByteArray? {
        return try {
            // First get the object name
            val infoReq = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/objects"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val infoRes = http.send(infoReq, HttpResponse.BodyHandlers.ofString())
            if (infoRes.statusCode() != 200) return null

            val objects = JsonParser.parseString(infoRes.body()).asJsonArray
            // Find object matching our server
            val matching = objects.filter { el ->
                val name = if (el.isJsonPrimitive) el.asString
                else if (el.isJsonObject && el.asJsonObject.has("name")) el.asJsonObject.get("name").asString
                else ""
                name.contains(serverId.substring(0, 8)) || name.contains("e2e-user")
            }

            if (matching.isEmpty()) {
                System.err.println("[restore] No matching objects found for server $serverId")
                return null
            }

            val objectName = if (matching[0].isJsonPrimitive) matching[0].asString
            else matching[0].asJsonObject.get("name").asString

            val downloadReq = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/objects/$objectName"))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build()
            val downloadRes = http.send(downloadReq, HttpResponse.BodyHandlers.ofByteArray())
            if (downloadRes.statusCode() == 200) downloadRes.body() else null
        } catch (e: Exception) {
            System.err.println("[restore] Object name download failed: ${e.message}")
            null
        }
    }

    private fun decrypt(encryptedData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ProperCrypto.decrypt(encryptionPassword.toCharArray(), ByteArrayInputStream(encryptedData), out)
        return out.toByteArray()
    }

    private fun extractTarGz(tarGzData: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        try {
            GzipCompressorInputStream(ByteArrayInputStream(tarGzData)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name != ".properbackup-idx") {
                            val data = tar.readNBytes(entry.size.toInt())
                            result[entry.name] = data
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[restore] TarGz extraction error: ${e.message}")
        }
        return result
    }

    data class LocalFile(val path: String, val sha256: String, val sizeBytes: Long)

    private fun scanLocalFiles(): List<LocalFile> {
        val files = mutableListOf<LocalFile>()
        for (rootStr in backupRoots) {
            val root = Path.of(rootStr)
            if (!Files.exists(root)) continue
            try {
                Files.walk(root)
                    .filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                    .forEach { file ->
                        try {
                            val bytes = Files.readAllBytes(file)
                            files.add(LocalFile(
                                path = file.toAbsolutePath().normalize().toString(),
                                sha256 = sha256(bytes),
                                sizeBytes = bytes.size.toLong(),
                            ))
                        } catch (_: Exception) { }
                    }
            } catch (_: Exception) { }
        }
        return files
    }

    companion object {
        fun sha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
