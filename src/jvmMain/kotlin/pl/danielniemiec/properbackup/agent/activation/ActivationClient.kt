package pl.danielniemiec.properbackup.agent.activation

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

class ActivationClient(private val bufferUrl: String) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val gson = Gson()

    data class SessionPayload(val token: String, val email: String, val id: String, val isSystemAdmin: Boolean)

    data class ActivationResult(
        val serverId: String,
        val name: String,
        val session: Optional<SessionPayload>,
        val refreshToken: String = "",
    )

    @Throws(IOException::class, InterruptedException::class)
    fun activate(token: String, includeAuth: Boolean): ActivationResult {
        val hostname = getHostname()
        val body = JsonObject().apply {
            addProperty("token", token)
            addProperty("hostname", hostname)
            addProperty("includeAuth", includeAuth)
        }

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${bufferUrl.trimEnd('/')}/servers/activate"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())

        if (res.statusCode() != 200) {
            throw IOException("Activation failed (${res.statusCode()}): ${res.body()}")
        }

        val o = JsonParser.parseString(res.body()).asJsonObject
        val serverId = o.get("serverId").asString
        val name = if (o.has("name")) o.get("name").asString else ""
        val refreshToken = if (o.has("refreshToken")) o.get("refreshToken").asString else ""
        println("[activation] Server registered: $name ($serverId)")

        var session: Optional<SessionPayload> = Optional.empty()
        if (o.has("token") && o.has("email") && o.has("id")) {
            session = Optional.of(
                SessionPayload(
                    o.get("token").asString,
                    o.get("email").asString,
                    o.get("id").asString,
                    o.has("isSystemAdmin") && o.get("isSystemAdmin").asBoolean,
                ),
            )
        }
        return ActivationResult(serverId, name, session, refreshToken)
    }

    /**
     * Register an access code with the buffer so the web UI can look it up.
     * Returns the serverId once the code is confirmed via the web UI (polling).
     */
    @Throws(IOException::class, InterruptedException::class)
    fun registerAccessCode(accessCode: String, machineId: String): String {
        val hostname = getHostname()
        val body = gson.toJson(
            mapOf(
                "accessCode" to accessCode,
                "machineId" to machineId,
                "hostname" to hostname,
            ),
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${bufferUrl.trimEnd('/')}/agents/register-code"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw IOException("Code registration failed (${res.statusCode()}): ${res.body()}")
        }
        return res.body()
    }

    /**
     * Poll the buffer to check if the access code has been activated via web UI.
     * Returns ActivationResult when activated, null if still pending.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun pollAccessCode(accessCode: String, machineId: String): ActivationResult? {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${bufferUrl.trimEnd('/')}/agents/poll-code?code=$accessCode&machineId=$machineId"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 202 || res.statusCode() == 404) {
            return null // still pending
        }
        if (res.statusCode() != 200) {
            throw IOException("Poll failed (${res.statusCode()}): ${res.body()}")
        }
        val o = JsonParser.parseString(res.body()).asJsonObject
        val serverId = o.get("serverId").asString
        val name = if (o.has("name")) o.get("name").asString else ""
        val refreshToken = if (o.has("refreshToken")) o.get("refreshToken").asString else ""
        var session: Optional<SessionPayload> = Optional.empty()
        if (o.has("token") && o.has("email") && o.has("id")) {
            session = Optional.of(
                SessionPayload(
                    o.get("token").asString,
                    o.get("email").asString,
                    o.get("id").asString,
                    o.has("isSystemAdmin") && o.get("isSystemAdmin").asBoolean,
                ),
            )
        }
        return ActivationResult(serverId, name, session, refreshToken)
    }

    private fun getHostname(): String = try {
        InetAddress.getLocalHost().hostName
    } catch (e: Exception) {
        "unknown-host"
    }
}
