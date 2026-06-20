package pl.danielniemiec.properbackup.shared.host

import java.io.InputStream
import java.io.OutputStream

/**
 * The contract every host environment (VPS agent, MC plugin, Fabric mod, etc.) must implement.
 * The shared core calls ONLY through this interface — never through direct JVM APIs like
 * System.getProperty(), File(), or Runtime.getRuntime().
 *
 * This ensures the same shared JAR works identically across all host environments (HR-1, HR-4).
 */
interface HostAdapter {
    val hostType: HostType
    val hostVersion: String

    val fs: PlatformFs
    val clock: PlatformClock
    val net: PlatformNetInfo

    fun scheduleRepeating(intervalMillis: Long, task: () -> Unit): Cancellable
    fun scheduleOnce(delayMillis: Long, task: () -> Unit): Cancellable

    fun configDir(): String
    fun dataDir(): String

    fun capabilities(): HostCapabilities

    fun defaultBackupRoots(): List<String>

    fun log(level: LogLevel, message: String, throwable: Throwable? = null)
}

enum class HostType {
    VPS_STANDALONE,
    MC_PAPER,
    MC_SPIGOT,
    MC_FOLIA,
    FABRIC_MOD,
    FORGE_MOD,
    IOS_APP,
    ANDROID_APP,
    WINDOWS_INSTALLER,
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class HostCapabilities(
    val supportsBackgroundUpload: Boolean,
    val supportsResumableUpload: Boolean,
    val supportsLongRunningThreads: Boolean,
    val maxIoBytesPerSec: Long,
    val isMultiTenantHost: Boolean,
    val supportsFsLocks: Boolean,
)

fun interface Cancellable {
    fun cancel()
}
