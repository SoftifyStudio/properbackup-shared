package pl.danielniemiec.properbackup.shared.domain

/**
 * Configuration for the agent core. Loaded by the host from its platform-specific
 * config storage (e.g. ~/.properbackup/global.yml for VPS, plugins/ProperBackup/config.yml for MC).
 */
data class AgentConfig(
    val bufferUrl: String,
    val uploadToken: String,
    val encryptionPassword: String,
    val userId: String,
    val serverId: String,
    val serverName: String,
    val backupRoots: List<String> = emptyList(),
    val backupIntervalSeconds: Long = 21600, // 6 hours default
    val ioReadRateMbps: Int = 50,
    val maxCpuPercent: Int = 25,
)
