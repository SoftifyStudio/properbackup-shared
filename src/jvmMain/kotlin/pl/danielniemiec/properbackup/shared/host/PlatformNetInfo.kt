package pl.danielniemiec.properbackup.shared.host

/**
 * Network info abstraction — hosts can provide local IP, hostname, connectivity status.
 */
interface PlatformNetInfo {
    fun localHostname(): String
    fun isOnline(): Boolean
}
