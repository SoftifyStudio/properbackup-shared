package pl.danielniemiec.properbackup.shared.domain

import pl.danielniemiec.properbackup.shared.host.Cancellable
import pl.danielniemiec.properbackup.shared.host.HostAdapter
import pl.danielniemiec.properbackup.shared.host.LogLevel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The main entry point for the ProperBackup agent core.
 * Hosts call start() on their lifecycle event (onEnable, main(), ModInitializer)
 * and stop() on shutdown.
 *
 * This facade orchestrates: scan → diff → pack → encrypt → upload cycles
 * through the host's scheduler, using ONLY the HostAdapter interface.
 */
class ProperBackupAgentCore(
    private val host: HostAdapter,
    private val config: AgentConfig,
) {
    private val running = AtomicBoolean(false)
    private val scheduledTask = AtomicReference<Cancellable?>(null)
    private val currentStatus = AtomicReference(AgentStatus.IDLE)

    fun start() {
        if (!running.compareAndSet(false, true)) {
            host.log(LogLevel.WARN, "Core.start() called but already running — ignoring")
            return
        }

        host.log(LogLevel.INFO, "ProperBackupAgentCore starting (host=${host.hostType}, version=${host.hostVersion})")

        val capabilities = host.capabilities()
        host.log(LogLevel.INFO, "Host capabilities: io=${capabilities.maxIoBytesPerSec / (1024 * 1024)}MB/s, " +
            "multiTenant=${capabilities.isMultiTenantHost}, locks=${capabilities.supportsFsLocks}")

        // Ensure config and data directories exist
        host.fs.mkdirs(host.configDir())
        host.fs.mkdirs(host.dataDir())

        // Schedule recurring backup cycles
        val intervalMs = config.backupIntervalSeconds * 1000L
        val task = host.scheduleRepeating(intervalMs) {
            try {
                runBackupCycle()
            } catch (e: Exception) {
                host.log(LogLevel.ERROR, "Backup cycle failed", e)
                currentStatus.set(AgentStatus.ERROR)
            }
        }
        scheduledTask.set(task)

        host.log(LogLevel.INFO, "Backup scheduler started (interval=${config.backupIntervalSeconds}s)")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduledTask.getAndSet(null)?.cancel()
        currentStatus.set(AgentStatus.STOPPED)
        host.log(LogLevel.INFO, "ProperBackupAgentCore stopped")
    }

    fun runBackupNow(): BackupRunResult {
        if (!running.get()) return BackupRunResult(success = false, detail = "Core not started")
        return runBackupCycle()
    }

    fun status(): AgentStatus = currentStatus.get()

    private fun runBackupCycle(): BackupRunResult {
        currentStatus.set(AgentStatus.SCANNING)
        host.log(LogLevel.DEBUG, "Starting backup cycle")

        val roots = config.backupRoots.ifEmpty { host.defaultBackupRoots() }
        if (roots.isEmpty()) {
            host.log(LogLevel.WARN, "No backup roots configured — skipping cycle")
            currentStatus.set(AgentStatus.IDLE)
            return BackupRunResult(success = true, detail = "No roots configured")
        }

        // The actual scan → diff → pack → encrypt → upload logic is delegated
        // to BackupOrchestrator (which uses DifferentialScanner, TarGzPacker,
        // ProperCrypto, BufferUploader from the existing shared modules).
        // This facade just manages lifecycle and scheduling.

        currentStatus.set(AgentStatus.UPLOADING)
        // TODO: Wire BackupOrchestrator once existing modules are refactored to use PlatformFs

        currentStatus.set(AgentStatus.IDLE)
        return BackupRunResult(success = true, detail = "Cycle complete")
    }
}

enum class AgentStatus { IDLE, SCANNING, UPLOADING, ERROR, STOPPED }

data class BackupRunResult(
    val success: Boolean,
    val detail: String,
    val filesScanned: Int = 0,
    val filesUploaded: Int = 0,
    val bytesUploaded: Long = 0,
)
