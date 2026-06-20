package pl.danielniemiec.properbackup.shared.host.jvm

import pl.danielniemiec.properbackup.shared.host.PlatformNetInfo
import java.net.InetAddress

/**
 * Standard JVM network info — uses InetAddress for hostname and reachability check.
 */
class JvmPlatformNetInfo : PlatformNetInfo {
    override fun localHostname(): String =
        try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }

    override fun isOnline(): Boolean =
        try { InetAddress.getByName("1.1.1.1").isReachable(3000) } catch (_: Exception) { false }
}
