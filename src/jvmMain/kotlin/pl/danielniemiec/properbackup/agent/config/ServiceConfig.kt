package pl.danielniemiec.properbackup.agent.config

class ServiceConfig {
    var service: String? = null
    var enabled: Boolean = true
    var paths: MutableList<String>? = null
    var exclude: MutableList<String> = mutableListOf()
    var schedule: String = "0 */6 * * *"

    override fun toString(): String = "ServiceConfig{service='$service', paths=$paths}"
}
