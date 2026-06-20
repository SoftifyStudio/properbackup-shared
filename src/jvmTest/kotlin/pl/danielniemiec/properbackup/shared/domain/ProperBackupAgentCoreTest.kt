package pl.danielniemiec.properbackup.shared.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.danielniemiec.properbackup.shared.host.*
import pl.danielniemiec.properbackup.shared.host.jvm.JvmPlatformClock
import pl.danielniemiec.properbackup.shared.host.jvm.JvmPlatformFs
import pl.danielniemiec.properbackup.shared.host.jvm.JvmPlatformNetInfo
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for ProperBackupAgentCore lifecycle (SHC-C group).
 */
class ProperBackupAgentCoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun mockHost(configDir: String, dataDir: String): TestHostAdapter =
        TestHostAdapter(configDir, dataDir)

    private fun defaultConfig() = AgentConfig(
        bufferUrl = "http://localhost:7100",
        uploadToken = "test-token",
        encryptionPassword = "test-pass",
        userId = "user-1",
        serverId = "server-1",
        serverName = "Test Server",
        backupRoots = listOf("/tmp/test-backup"),
        backupIntervalSeconds = 1,
    )

    @Test
    fun `start initializes correctly on any host`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())

        core.start()

        assertEquals(AgentStatus.IDLE, core.status())
        assertTrue(host.scheduledTasks.isNotEmpty(), "Should have scheduled a repeating task")
        assertTrue(host.fs.exists(host.configDir()))
        assertTrue(host.fs.exists(host.dataDir()))

        core.stop()
    }

    @Test
    fun `start is idempotent — second call is no-op`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())

        core.start()
        core.start() // should not throw or duplicate tasks

        assertEquals(1, host.scheduledTasks.size)
        core.stop()
    }

    @Test
    fun `stop is idempotent — double stop does not throw`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())

        core.start()
        core.stop()
        core.stop() // second stop should be no-op

        assertEquals(AgentStatus.STOPPED, core.status())
    }

    @Test
    fun `runBackupNow returns failure when not started`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())

        val result = core.runBackupNow()
        assertFalse(result.success)
    }

    @Test
    fun `runBackupNow returns success when started`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())
        core.start()

        val result = core.runBackupNow()
        assertTrue(result.success)

        core.stop()
    }

    @Test
    fun `host capabilities are logged during start`() {
        val host = mockHost(
            tmpDir.resolve("config").toString(),
            tmpDir.resolve("data").toString(),
        )
        val core = ProperBackupAgentCore(host, defaultConfig())

        core.start()

        val infoLogs = host.logs.filter { it.first == LogLevel.INFO }
        assertTrue(infoLogs.any { it.second.contains("capabilities") })

        core.stop()
    }
}

/**
 * A test HostAdapter that records interactions for assertions.
 * Per HR-10: we only mock the HostAdapter boundary, never internal logic.
 */
class TestHostAdapter(
    private val configDirPath: String,
    private val dataDirPath: String,
) : HostAdapter {
    override val hostType = HostType.VPS_STANDALONE
    override val hostVersion = "test-1.0"
    override val fs: PlatformFs = JvmPlatformFs()
    override val clock: PlatformClock = JvmPlatformClock()
    override val net: PlatformNetInfo = JvmPlatformNetInfo()

    val scheduledTasks = CopyOnWriteArrayList<Cancellable>()
    val logs = CopyOnWriteArrayList<Pair<LogLevel, String>>()

    override fun scheduleRepeating(intervalMillis: Long, task: () -> Unit): Cancellable {
        val cancellable = Cancellable { /* no-op for test */ }
        scheduledTasks.add(cancellable)
        return cancellable
    }

    override fun scheduleOnce(delayMillis: Long, task: () -> Unit): Cancellable {
        val cancellable = Cancellable { /* no-op for test */ }
        return cancellable
    }

    override fun configDir() = configDirPath
    override fun dataDir() = dataDirPath

    override fun capabilities() = HostCapabilities(
        supportsBackgroundUpload = true,
        supportsResumableUpload = true,
        supportsLongRunningThreads = true,
        maxIoBytesPerSec = 50L * 1024 * 1024,
        isMultiTenantHost = false,
        supportsFsLocks = true,
    )

    override fun defaultBackupRoots() = listOf("/tmp/default-backup")

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        logs.add(level to message)
    }
}
