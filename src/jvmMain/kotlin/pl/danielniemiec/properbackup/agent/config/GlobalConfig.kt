package pl.danielniemiec.properbackup.agent.config

class GlobalConfig {
    var agent: AgentSection = AgentSection()
    var default_excludes: MutableList<String> = mutableListOf()

    class AgentSection {
        var user_id: String = "U1"
        var buffer_url: String = "http://127.0.0.1:37100"
        var upload_token: String = ""
        var encryption_password: String = ""
        var state_dir: String = System.getProperty("java.io.tmpdir") + "/properbackup"
        var upload_bandwidth_kbps: Int = 0
        var io_read_rate_mbps: Int = 50
        var max_cpu_percent: Int = 25
        var server_id: String = ""
        var poll_interval_seconds: Int = 300
        var panel_url: String = ""
    }
}
