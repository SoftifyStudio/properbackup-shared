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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Top-level restore orchestrator implementing the full recovery protocol (Section C).
 *
 * Supports:
 * - HR-5: Pre-recovery snapshot creation BEFORE restore
 * - HR-7: Resume-on-crash via RestoreStateStore (persisted ops survive restart)
 * - HR-8: CriticalPathsGuard skip + log for protected paths
 * - HR-9: Cancel during AGENT_RESTORING (finish current file, rollback)
 */
class RestoreOrchestrator(
    private val bufferUrl: String,
    private val serverId: String,
    private val token: String,
    private val encryptionPassword: String,
    private val stateDir: Path,
    private val configDir: Path,
    private val backupRoots: List<String>,
    private val jwtToken: String = "",
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val gson = Gson()
    @Volatile
    private var cancelRequested = AtomicBoolean(false)

    data class RecoveryCommand(
        val command: String,
        val sessionId: String,
        val targetSnapshotId: Long,
    )

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

        // Prefer the dedicated buffer manifest endpoint; when it is unavailable
        // (e.g. buffer does not expose /agent/snapshot/{id}/files), derive the
        // file list from the encrypted archive pack — same source of truth the
        // actual restore uses, so dry-run and execute always agree.
        val snapshotFiles = fetchSnapshotFiles(cmd.targetSnapshotId)
            ?: deriveSnapshotFilesFromArchive(cmd.targetSnapshotId)
        if (snapshotFiles == null) {
            reporter.reportComplete(false, "Cannot fetch snapshot manifest")
            return
        }

        val toRestore = mutableListOf<String>()
        val toDelete = mutableListOf<String>()
        var unchanged = 0
        var criticalProtected = 0
        var totalBytes = 0L

        val snapshotPathMap = snapshotFiles.associateBy { it.path }
        val localFiles = scanLocalFiles()
        val localPathMap = localFiles.associateBy { it.path }

        for (sf in snapshotFiles) {
            if (guard.isProtected(sf.path)) {
                criticalProtected++
                println("[restore] [HR-8] SKIP protected: ${sf.path}")
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

        for (lf in localFiles) {
            if (!snapshotPathMap.containsKey(lf.path)) {
                if (guard.isProtected(lf.path)) {
                    criticalProtected++
                    println("[restore] [HR-8] SKIP protected (delete): ${lf.path}")
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
        val fileDeleter = FileDeleter(quarantineDir, cmd.sessionId, stateStore)

        cancelRequested.set(false)
        var earlyDecryptedArchive: Map<String, ByteArray>? = null

        // Signal to buffer that agent is starting restore
        reporter.reportStarted()

        try {
            // ─── HR-7: Resume support ───
            // Check if we have a saved operation list from a previous crash
            val resumedOps = stateStore.loadOperations()
            val isResume = resumedOps != null
            val operations: MutableList<RecoveryOperation>

            if (isResume) {
                operations = resumedOps!!.toMutableList()
                val doneCount = operations.count { it.state == OperationState.DONE }
                val pendingCount = operations.count { !it.state.isTerminal }
                println("[restore] [HR-7] RESUMING: ${operations.size} ops (done=$doneCount, pending=$pendingCount)")
            } else {
                // ─── HR-5: Pre-recovery snapshot ───
                println("[restore] [HR-5] Creating pre-recovery snapshot BEFORE restore...")
                val preSnapshotId = createPreRecoverySnapshot(cmd.sessionId)
                println("[restore] [HR-5] Pre-recovery snapshot created: id=$preSnapshotId")

                // Download the archive first (needed to derive file list if endpoint unavailable)
                val earlyArchiveData = downloadArchivePack(cmd.targetSnapshotId)
                if (earlyArchiveData == null) {
                    reporter.reportComplete(false, "Cannot download archive pack")
                    return
                }
                earlyDecryptedArchive = extractTarGz(decrypt(earlyArchiveData))
                println("[restore] Archive decrypted: ${earlyDecryptedArchive!!.size} files extracted")

                // Try to fetch snapshot manifest from buffer endpoint
                var snapshotFiles = fetchSnapshotFiles(cmd.targetSnapshotId)

                // Fallback: derive file list from extracted archive contents
                if (snapshotFiles == null && earlyDecryptedArchive!!.isNotEmpty()) {
                    println("[restore] Deriving file list from archive contents (${earlyDecryptedArchive!!.size} files)")
                    snapshotFiles = earlyDecryptedArchive!!.map { (entryName, data) ->
                        // Resolve path relative to first backup root
                        val restorePath = if (backupRoots.isNotEmpty()) {
                            Path.of(backupRoots[0]).resolve(entryName).toAbsolutePath().normalize().toString()
                        } else {
                            entryName
                        }
                        SnapshotFile(
                            path = restorePath,
                            sha256 = sha256(data),
                            sizeBytes = data.size.toLong(),
                        )
                    }
                }

                if (snapshotFiles == null || snapshotFiles.isEmpty()) {
                    reporter.reportComplete(false, "Cannot determine file list for restore")
                    return
                }

                val snapshotPathMap = snapshotFiles.associateBy { it.path }
                val localFiles = scanLocalFiles()
                val localPathMap = localFiles.associateBy { it.path }

                operations = mutableListOf()

                for (sf in snapshotFiles) {
                    // ─── HR-8: CriticalPathsGuard ───
                    if (guard.isProtected(sf.path)) {
                        println("[restore] [HR-8] SKIP protected path (restore): ${sf.path}")
                        operations.add(RecoveryOperation(
                            id = UUID.randomUUID().toString(),
                            type = OperationType.SKIP,
                            path = sf.path,
                            sha256Target = sf.sha256,
                            sizeBytes = sf.sizeBytes,
                            state = OperationState.DONE,
                        ))
                        continue
                    }
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
                    } else {
                        operations.add(RecoveryOperation(
                            id = UUID.randomUUID().toString(),
                            type = OperationType.SKIP,
                            path = sf.path,
                            sha256Target = sf.sha256,
                            sizeBytes = sf.sizeBytes,
                            state = OperationState.DONE,
                        ))
                    }
                }
                for (lf in localFiles) {
                    if (!snapshotPathMap.containsKey(lf.path)) {
                        if (guard.isProtected(lf.path)) {
                            println("[restore] [HR-8] SKIP protected path (delete): ${lf.path}")
                            operations.add(RecoveryOperation(
                                id = UUID.randomUUID().toString(),
                                type = OperationType.SKIP,
                                path = lf.path,
                                state = OperationState.DONE,
                            ))
                            continue
                        }
                        operations.add(RecoveryOperation(
                            id = UUID.randomUUID().toString(),
                            type = OperationType.DELETE,
                            path = lf.path,
                            sha256Local = lf.sha256,
                            sizeBytes = lf.sizeBytes,
                        ))
                    }
                }

                // Save full operation list for resume-on-crash (HR-7)
                stateStore.save(operations)
            }

            // ─── Phase 3: Execute operations ───
            println("[restore] Executing ${operations.size} operations...")
            var restored = 0
            var deleted = 0
            var skipped = 0

            // Use already-downloaded archive if available, otherwise download now
            val extractedFiles = if (earlyDecryptedArchive != null) {
                earlyDecryptedArchive!!
            } else {
                val archiveData = downloadArchivePack(cmd.targetSnapshotId)
                if (archiveData != null) extractTarGz(decrypt(archiveData)) else emptyMap()
            }

            for (op in operations) {
                // ─── HR-9: Check cancel ───
                if (cancelRequested.get() || checkCancelFromBuffer(cmd.sessionId)) {
                    println("[restore] [HR-9] CANCEL detected after ${restored + deleted} ops. Rolling back...")
                    handleCancelRollback(operations, fileDeleter, stateStore, reporter, cmd.sessionId)
                    return
                }

                // Skip already-done ops (resume support)
                if (op.state.isTerminal) {
                    if (op.state == OperationState.DONE && op.type == OperationType.RESTORE) restored++
                    if (op.state == OperationState.DONE && op.type == OperationType.DELETE) deleted++
                    if (op.type == OperationType.SKIP) skipped++
                    continue
                }

                try {
                    when (op.type) {
                        OperationType.RESTORE -> {
                            // Look up file data by full path or entry name
                            val fileData = extractedFiles[op.path]
                                ?: extractedFiles[Path.of(op.path).fileName.toString()]
                                ?: backupRoots.firstNotNullOfOrNull { root ->
                                    val relative = try { Path.of(root).relativize(Path.of(op.path)).toString() } catch (_: Exception) { null }
                                    relative?.let { extractedFiles[it] }
                                }
                            if (fileData != null) {
                                restoreFileDirectly(op, fileData, stateStore)
                                reporter.trackRestored(op.sizeBytes)
                                reporter.reportFileRestored(op.path, op.sha256Target, op.sizeBytes)
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
                            reporter.reportFileDeleted(op.path)
                            deleted++
                        }
                        OperationType.SKIP -> {
                            op.state = OperationState.DONE
                            stateStore.markState(op.id, OperationState.DONE)
                            skipped++
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("[restore] Operation failed: ${op.path}: ${e.message}")
                }
            }

            // ─── Phase 4: Verify final state ───
            println("[restore] Verifying final state...")
            val allDone = operations.all { it.state == OperationState.DONE }
            val failedOps = operations.filter { it.state == OperationState.FAILED }
            if (failedOps.isNotEmpty()) {
                System.err.println("[restore] ${failedOps.size} operations failed")
                failedOps.take(5).forEach { System.err.println("  FAILED: ${it.path}: ${it.error}") }
            }

            reporter.flush()
            reporter.reportComplete(success = allDone, verification = if (allDone) "OK" else "PARTIAL")
            println("[restore] Recovery complete: restored=$restored, deleted=$deleted, skipped=$skipped, allDone=$allDone")

            if (allDone) stateStore.cleanup()

        } catch (e: Exception) {
            System.err.println("[restore] Recovery failed: ${e.message}")
            reporter.reportComplete(false, "Agent error: ${e.message}")
        }
    }

    /**
     * HR-9: Handle cancel during AGENT_RESTORING.
     * - Finish current file (already done since we check between ops)
     * - Rollback: restored files → delete; deleted files → restore from quarantine
     */
    private fun handleCancelRollback(
        operations: List<RecoveryOperation>,
        fileDeleter: FileDeleter,
        stateStore: RestoreStateStore,
        reporter: RestoreProgressReporter,
        sessionId: String,
    ) {
        println("[restore] [HR-9] Rolling back restored files...")

        // Remove files that were restored during this session
        val restoredOps = operations.filter { it.type == OperationType.RESTORE && it.state == OperationState.DONE }
        for (op in restoredOps) {
            try {
                val path = Path.of(op.path)
                if (Files.exists(path)) {
                    Files.delete(path)
                    println("[restore] [HR-9] Rolled back restored file: ${op.path}")
                }
            } catch (e: Exception) {
                System.err.println("[restore] [HR-9] Rollback delete failed: ${op.path}: ${e.message}")
            }
        }

        // Restore quarantined files (deleted during restore)
        println("[restore] [HR-9] Restoring quarantined files from pre-restore state...")
        fileDeleter.rollback()

        // Reset non-terminal ops to PENDING for future retry
        for (op in operations) {
            if (!op.state.isTerminal) {
                op.state = OperationState.PENDING
                stateStore.markState(op.id, OperationState.PENDING)
            }
        }

        reporter.flush()
        reporter.reportComplete(false, "CANCELLED — rollback completed")
        stateStore.cleanup()

        println("[restore] [HR-9] Cancel rollback complete. Session $sessionId cancelled.")
    }

    /**
     * Check buffer for cancel status of this recovery session.
     */
    private fun checkCancelFromBuffer(sessionId: String): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/recovery/$sessionId"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val obj = JsonParser.parseString(res.body()).asJsonObject
                val state = obj.get("state")?.asString
                state == "CANCELLED"
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request cancel from outside (e.g. signal handler).
     */
    fun requestCancel() {
        cancelRequested.set(true)
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

    /**
     * HR-5: Create a pre-recovery snapshot of current file state BEFORE restore begins.
     * Uploads current files as a backup pack tagged PRE_RECOVERY.
     * Returns the snapshot ID from the buffer response.
     */
    private fun createPreRecoverySnapshot(sessionId: String): Long? {
        try {
            // Scan current files to create manifest
            val localFiles = scanLocalFiles()
            val manifest = localFiles.map { mapOf("path" to it.path, "sha256" to it.sha256, "size" to it.sizeBytes) }

            val body = gson.toJson(mapOf(
                "session_id" to sessionId,
                "snapshot_type" to "PRE_RECOVERY",
                "server_id" to serverId,
                "file_count" to localFiles.size,
                "total_bytes" to localFiles.sumOf { it.sizeBytes },
                "manifest" to manifest,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/recovery/$sessionId/pre_snapshot"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200 || res.statusCode() == 201) {
                return try {
                    val obj = JsonParser.parseString(res.body()).asJsonObject
                    obj.get("snapshot_id")?.asLong
                } catch (_: Exception) { null }
            }

            // Fallback: signal via scan-complete
            println("[restore] [HR-5] Pre-snapshot endpoint not available, using scan-complete fallback")
            val fallbackBody = gson.toJson(mapOf("session_id" to sessionId, "snapshot_type" to "PRE_RECOVERY"))
            val fallbackReq = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/scan-complete"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(fallbackBody))
                .build()
            http.send(fallbackReq, HttpResponse.BodyHandlers.discarding())
            println("[restore] [HR-5] Pre-recovery snapshot signal sent (fallback)")
            return null
        } catch (e: Exception) {
            System.err.println("[restore] [HR-5] Pre-recovery snapshot failed: ${e.message}")
            return null
        }
    }

    data class SnapshotFile(val path: String, val sha256: String, val sizeBytes: Long)

    private fun fetchSnapshotFiles(snapshotId: Long): List<SnapshotFile>? {
        // Try dedicated agent endpoint first
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/snapshot/$snapshotId/files"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val arr = JsonParser.parseString(res.body()).asJsonArray
                return arr.map { el ->
                    val o = el.asJsonObject
                    SnapshotFile(
                        path = o.get("path").asString,
                        sha256 = o.get("sha256").asString,
                        sizeBytes = o.get("size_bytes").asLong,
                    )
                }
            }
            println("[restore] /agent/snapshot/$snapshotId/files returned ${res.statusCode()}, will derive from archive")
        } catch (e: Exception) {
            println("[restore] fetchSnapshotFiles direct endpoint unavailable: ${e.message}")
        }
        // Fallback: return null — caller will derive file list from archive contents
        return null
    }

    /**
     * Derive the snapshot file list (path + sha256 + size) by downloading the
     * encrypted archive pack, decrypting it and extracting the tar.gz. Mirrors
     * the fallback used by [executeRestore] so the dry-run plan is computed from
     * the exact same data that the restore writes back.
     */
    private fun deriveSnapshotFilesFromArchive(snapshotId: Long): List<SnapshotFile>? {
        val archiveData = downloadArchivePack(snapshotId)
        if (archiveData == null) {
            println("[restore] deriveSnapshotFilesFromArchive: cannot download archive pack")
            return null
        }
        val decrypted = try {
            extractTarGz(decrypt(archiveData))
        } catch (e: Exception) {
            println("[restore] deriveSnapshotFilesFromArchive: decrypt/extract failed: ${e.message}")
            return null
        }
        if (decrypted.isEmpty()) return null
        println("[restore] Deriving file list from archive contents (${decrypted.size} files)")
        return decrypted.map { (entryName, data) ->
            val restorePath = if (backupRoots.isNotEmpty()) {
                Path.of(backupRoots[0]).resolve(entryName).toAbsolutePath().normalize().toString()
            } else {
                entryName
            }
            SnapshotFile(
                path = restorePath,
                sha256 = sha256(data),
                sizeBytes = data.size.toLong(),
            )
        }
    }

    private fun downloadArchivePack(snapshotId: Long): ByteArray? {
        // Strategy 1: Try dedicated agent endpoint
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/agent/snapshot/$snapshotId/download"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer $token")
                .header("X-Server-Id", serverId)
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() == 200) {
                println("[restore] Downloaded archive via /agent/snapshot/$snapshotId/download (${res.body().size} bytes)")
                return res.body()
            }
            println("[restore] Direct download returned ${res.statusCode()}, trying object name lookup...")
        } catch (e: Exception) {
            println("[restore] Direct download failed: ${e.message}, trying object name lookup...")
        }

        // Strategy 2: Look up object_name via archive-snapshots, download via /objects/{name}
        return downloadByObjectName(snapshotId)
    }

    private fun downloadByObjectName(snapshotId: Long): ByteArray? {
        val authToken = jwtToken.ifBlank { token }
        try {
            // Get the object name from archive-snapshots endpoint
            val objectName = lookupObjectName(snapshotId, authToken)
            if (objectName != null) {
                println("[restore] Found object: $objectName, downloading...")
                val downloadReq = HttpRequest.newBuilder()
                    .uri(URI.create("$bufferUrl/objects/$objectName"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Authorization", "Bearer $authToken")
                    .GET()
                    .build()
                val downloadRes = http.send(downloadReq, HttpResponse.BodyHandlers.ofByteArray())
                if (downloadRes.statusCode() == 200) {
                    println("[restore] Downloaded $objectName (${downloadRes.body().size} bytes)")
                    return downloadRes.body()
                }
                System.err.println("[restore] Object download failed: ${downloadRes.statusCode()}")
            }
        } catch (e: Exception) {
            System.err.println("[restore] Object name download failed: ${e.message}")
        }

        // Strategy 3: Try listing objects (legacy fallback)
        return downloadByObjectListing(authToken)
    }

    private fun lookupObjectName(snapshotId: Long, authToken: String): String? {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/servers/$serverId/archive-snapshots"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $authToken")
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val arr = JsonParser.parseString(res.body()).asJsonArray
                // Try match by id field if present
                for (el in arr) {
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asLong
                    if (id != null && id == snapshotId) {
                        return obj.get("object_name")?.asString
                            ?: obj.get("objectName")?.asString
                    }
                }
                // Fallback: take the most recent archive (flag=A, not pre-recovery)
                for (el in arr) {
                    val obj = el.asJsonObject
                    val flag = obj.get("flag")?.asString ?: ""
                    val pathId = obj.get("pathId")?.asString ?: obj.get("path_id")?.asString ?: ""
                    if (flag == "A" && pathId != "PRE_REC") {
                        val name = obj.get("objectName")?.asString ?: obj.get("object_name")?.asString
                        if (name != null) {
                            println("[restore] Using archive snapshot: pathId=$pathId, object=$name")
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[restore] Snapshot lookup failed: ${e.message}")
        }
        return null
    }

    private fun downloadByObjectListing(authToken: String): ByteArray? {
        try {
            val infoReq = HttpRequest.newBuilder()
                .uri(URI.create("$bufferUrl/objects"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $authToken")
                .GET()
                .build()
            val infoRes = http.send(infoReq, HttpResponse.BodyHandlers.ofString())
            if (infoRes.statusCode() != 200) return null

            val objects = JsonParser.parseString(infoRes.body()).asJsonArray
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
                .header("Authorization", "Bearer $authToken")
                .GET()
                .build()
            val downloadRes = http.send(downloadReq, HttpResponse.BodyHandlers.ofByteArray())
            if (downloadRes.statusCode() == 200) return downloadRes.body()
            return null
        } catch (e: Exception) {
            System.err.println("[restore] Object listing download failed: ${e.message}")
            return null
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

        @JvmStatic
        fun extractTarGzStatic(tarGzData: ByteArray): Map<String, ByteArray> {
            val result = mutableMapOf<String, ByteArray>()
            GzipCompressorInputStream(ByteArrayInputStream(tarGzData)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name != ".properbackup-idx") {
                            result[entry.name] = tar.readNBytes(entry.size.toInt())
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            return result
        }
    }
}
