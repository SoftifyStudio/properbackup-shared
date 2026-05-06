package pl.danielniemiec.properbackup.agent.ui

import com.google.gson.Gson
import pl.danielniemiec.properbackup.agent.config.ConfigLoader
import pl.danielniemiec.properbackup.agent.config.GlobalConfig
import pl.danielniemiec.properbackup.agent.util.MachineIdentity
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object AgentPanelUrls {

    @JvmStatic
    fun authLanding(panelBase: String, query: Map<String, String>): String {
        val base = panelBase.trim().replace("/+$".toRegex(), "")
        val q = query.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + q
    }

    @JvmStatic
    fun withSessionHash(panelUrl: String, sessionFields: Map<String, Any?>): String {
        return try {
            val gson = Gson()
            val json = gson.toJson(sessionFields)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
            var u = panelUrl.trim()
            val hash = u.indexOf('#')
            if (hash >= 0) {
                u = u.substring(0, hash)
            }
            u + "#pb_session=" + b64
        } catch (e: Exception) {
            panelUrl
        }
    }

    @JvmStatic
    fun guessPanelFromBuffer(bufferUrl: String?): String {
        if (bufferUrl.isNullOrBlank()) {
            return "http://127.0.0.1:30517"
        }
        var u = bufferUrl.trim().replace("/+$".toRegex(), "")
        if (u.endsWith("/api")) {
            return u.substring(0, u.length - 4)
        }
        if (u.matches("https?://[^/]+:\\d+$".toRegex())) {
            when {
                u.endsWith(":37100") -> return u.replaceFirst(":37100$".toRegex(), ":30517")
                u.endsWith(":7100") -> return u.replaceFirst(":7100$".toRegex(), ":5173")
                else -> return u.replaceFirst(":(\\d+)$".toRegex(), ":5173")
            }
        }
        return "http://127.0.0.1:30517"
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildAgentLandingUrl(panelBase: String?, globalYml: Path, activationKeyOptionalForHash: String?): String {
        val panel = if (panelBase.isNullOrBlank()) {
            "http://127.0.0.1:30517"
        } else {
            panelBase.trim().replace("/+$".toRegex(), "")
        }
        val machineId = MachineIdentity.getOrCreate(MachineIdentity.defaultDeviceIdFile(globalYml))
        var sid: String? = null
        if (Files.exists(globalYml)) {
            try {
                val g = ConfigLoader.loadGlobal(globalYml)
                val id = g.agent.server_id.trim()
                if (id.isNotEmpty()) {
                    sid = id
                }
            } catch (ignored: Exception) {
            }
        }
        val unlinked = sid == null
        val q = linkedMapOf(
            "agent" to "1",
            "machineId" to machineId,
            "unlinked" to if (unlinked) "1" else "0",
        )
        if (!unlinked) {
            q["serverId"] = sid!!
        }
        var u = authLanding(panel, q)
        if (!activationKeyOptionalForHash.isNullOrBlank()) {
            val compact = activationKeyOptionalForHash.trim().replace("\\s+".toRegex(), "")
            u += "#a=" + URLEncoder.encode(compact, StandardCharsets.UTF_8)
        }
        return u
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildAgentLandingFromConfig(globalYml: Path, activationKeyOptionalForHash: String?): String {
        val envPanel = System.getenv("PROPERBACKUP_PANEL_URL")?.trim()?.takeIf { it.isNotEmpty() }
        if (envPanel != null) {
            // Dev / Electron: nadpisuje `panel_url` z YAML — np. Vite `http://127.0.0.1:5173` przy edycji properbackup-web.
            return buildAgentLandingUrl(envPanel, globalYml, activationKeyOptionalForHash)
        }
        val g = if (Files.exists(globalYml)) ConfigLoader.loadGlobal(globalYml) else GlobalConfig()
        val a = g.agent
        val bufferUrl = if (!a.buffer_url.isNullOrBlank()) {
            a.buffer_url
        } else {
            "http://127.0.0.1:37100"
        }
        val panel = if (!a.panel_url.isNullOrBlank()) {
            a.panel_url.trim().replace("/+$".toRegex(), "")
        } else {
            guessPanelFromBuffer(bufferUrl)
        }
        return buildAgentLandingUrl(panel, globalYml, activationKeyOptionalForHash)
    }
}
