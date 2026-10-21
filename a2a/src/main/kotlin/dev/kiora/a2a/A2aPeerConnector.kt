package dev.kiora.a2a

import java.net.URI
import java.util.UUID
import dev.kiora.core.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class A2aSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val inputModes: List<String> = emptyList(),
)

data class A2aAgentConnection(
    val peer: A2aPeer,
    val name: String,
    val description: String,
    val skills: List<A2aSkill>,
    val defaultInputModes: List<String> = emptyList(),
) {
    /** Expose each advertised skill to the model as a separate callable tool. */
    fun skillTools(
        fileLoader: (suspend (List<String>) -> List<A2aInlineFile>)? = null,
        messageSender: (suspend (A2aUserMessage, String?) -> String)? = null,
        resultSender: (suspend (A2aUserMessage, String?) -> ToolOutput)? = null,
        toolNamePrefix: String = "a2a",
        fileIdValidator: (suspend (List<String>) -> String?)? = null,
    ): List<A2aSkillTool> = skills.mapIndexed { index, skill ->
        val safePrefix = toolNamePrefix.replace(Regex("[^A-Za-z0-9_]"), "_")
            .take(24).ifBlank { "a2a" }
        val safeId = skill.id.replace(Regex("[^A-Za-z0-9_]"), "_")
            .take(if (safePrefix == "a2a") 48 else 24).ifBlank { "skill" }
        A2aSkillTool(peer, name, skill, "${safePrefix}_${safeId}_${index + 1}",
            skill.inputModes.ifEmpty { defaultInputModes }, fileLoader, messageSender, resultSender,
            fileIdValidator)
    }
}

/** Discovers a compatible A2A 1.0 interface from the desktop agent's card. */
object A2aPeerConnector {
    suspend fun discover(
        agentCardUrl: String,
        transport: A2aHttpTransport = UrlConnectionTransport(),
        bearerToken: suspend () -> String? = { null },
        rewriteLoopbackHost: Boolean = false,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): A2aPeer = discoverConnection(
        agentCardUrl, transport, bearerToken, rewriteLoopbackHost, newId,
    ).peer

    suspend fun discoverConnection(
        agentCardUrl: String,
        transport: A2aHttpTransport = UrlConnectionTransport(),
        bearerToken: suspend () -> String? = { null },
        rewriteLoopbackHost: Boolean = false,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): A2aAgentConnection {
        val cardUri = URI(agentCardUrl)
        require(cardUri.scheme in listOf("http", "https") && cardUri.host != null) {
            "Agent Card URL must use HTTP(S)"
        }
        val headers = mutableMapOf("Accept" to "application/json")
        bearerToken()?.let { headers["Authorization"] = "Bearer $it" }
        val reply = transport.request("GET", agentCardUrl, headers, null)
        check(reply.status in 200..299) { "Agent Card HTTP ${reply.status}: ${reply.body.take(500)}" }
        val card = Json.parseToJsonElement(reply.body).jsonObject
        val interfaces = card.getValue("supportedInterfaces").jsonArray.map { it.jsonObject }
        val selected = interfaces.firstOrNull { entry ->
            entry["protocolVersion"]?.jsonPrimitive?.content?.startsWith("1.") == true &&
                entry["protocolBinding"]?.jsonPrimitive?.content in setOf("HTTP+JSON", "JSONRPC")
        } ?: error("Agent Card has no supported A2A 1.x HTTP+JSON or JSONRPC interface")

        val binding = selected.getValue("protocolBinding").jsonPrimitive.content
        val version = selected.getValue("protocolVersion").jsonPrimitive.content
        val tenant = selected["tenant"]?.jsonPrimitive?.content
        val advertised = URI(selected.getValue("url").jsonPrimitive.content)
        require(advertised.scheme in listOf("http", "https") && advertised.host != null) {
            "Unsupported A2A interface URL"
        }
        val endpoint = if (rewriteLoopbackHost && advertised.host in setOf("localhost", "127.0.0.1", "::1") &&
            cardUri.host !in setOf("localhost", "127.0.0.1", "::1")) {
            URI(advertised.scheme, advertised.userInfo, cardUri.host, advertised.port,
                advertised.path, advertised.query, advertised.fragment).toString()
        } else advertised.toString()

        val peer = when (binding) {
            "HTTP+JSON" -> HttpJsonA2aPeer.fromInterface(endpoint, version, tenant, transport, bearerToken, newId)
            "JSONRPC" -> JsonRpcA2aPeer(endpoint, version, tenant, transport, bearerToken, newId)
            else -> error("Unsupported A2A binding: $binding")
        }
        val skills = (card["skills"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val name = entry["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
            A2aSkill(
                id = id,
                name = name,
                description = entry["description"]?.jsonPrimitive?.content?.trim().orEmpty(),
                tags = (entry["tags"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                examples = (entry["examples"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                inputModes = (entry["inputModes"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
            )
        }
        return A2aAgentConnection(
            peer = peer,
            name = card["name"]?.jsonPrimitive?.content?.trim().orEmpty(),
            description = card["description"]?.jsonPrimitive?.content?.trim().orEmpty(),
            skills = skills,
            defaultInputModes = (card["defaultInputModes"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
        )
    }
}
