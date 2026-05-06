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
import java.util.Optional

class ActivationClient(private val bufferUrl: String) {

    private val http = HttpClient.newHttpClient()
    private val gson = Gson()

    data class SessionPayload(val token: String, val email: String, val id: String, val isSystemAdmin: Boolean)

    data class ActivationResult(val serverId: String, val name: String, val session: Optional<SessionPayload>)

    @Throws(IOException::class, InterruptedException::class)
    fun activate(token: String, includeAuth: Boolean): ActivationResult {
        val hostname = getHostname()
        val body = JsonObject().apply {
            addProperty("token", token)
            addProperty("hostname", hostname)
            addProperty("includeAuth", includeAuth)
        }

        val req = HttpRequest.newBuilder()
            .uri(URI.create("$bufferUrl/servers/activate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())

        if (res.statusCode() != 200) {
            throw IOException("Activation failed (${res.statusCode()}): ${res.body()}")
        }

        val o = JsonParser.parseString(res.body()).asJsonObject
        val serverId = o.get("serverId").asString
        val name = if (o.has("name")) o.get("name").asString else ""
        println("✓ Serwer zarejestrowany: $name ($serverId)")

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
        return ActivationResult(serverId, name, session)
    }

    private fun getHostname(): String = try {
        InetAddress.getLocalHost().hostName
    } catch (e: Exception) {
        "unknown-host"
    }
}
