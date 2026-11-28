package io.zer0.muse.mcp

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal object McpConfigImport {
    fun parse(
        json: String,
        newId: () -> String = { "mcp-" + UUID.randomUUID().toString().take(12) },
    ): List<McpServerConfig>? =
        json.takeIf { it.length <= MAX_IMPORT_LENGTH }
            ?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }
            ?.let { root -> readEntries(root)?.let { parseEntries(it, newId) } }

    private fun parseEntries(entries: List<Pair<String, JsonElement>>, newId: () -> String): List<McpServerConfig>? {
        if (entries.isEmpty() || entries.size > MAX_SERVERS) return null
        return entries.map { (label, value) -> parseEntry(label, value, newId) ?: return null }
    }

    private fun readEntries(root: JsonObject): List<Pair<String, JsonElement>>? {
        val servers = root[SERVER_COLLECTION] as? JsonObject
        if (SERVER_COLLECTION in root) return servers?.entries?.map { it.key to it.value }
        return listOf((root["name"].stringValue() ?: "") to root)
    }

    private fun parseEntry(label: String, value: JsonElement, newId: () -> String): McpServerConfig? {
        val config = value as? JsonObject ?: return null
        val command = config["command"].stringValue()?.takeIf(String::isNotBlank)
        val address = config["url"].stringValue()?.takeIf(String::isNotBlank)?.toHttpUrlOrNull()
        val transport = parseTransport(config["type"].stringValue(), command) ?: return null
        val name = label.trim().ifBlank { address?.host ?: command.orEmpty() }.take(MAX_NAME_LENGTH)
        return when (transport) {
            McpTransportType.STDIO -> parseStdio(config, command, address, name, newId)
            McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP ->
                parseHttp(config, command, address, transport, name, newId)
        }
    }

    private fun parseTransport(type: String?, command: String?): McpTransportType? =
        when (type?.lowercase()?.replace('_', '-')) {
            "stdio" -> McpTransportType.STDIO
            "sse" -> McpTransportType.SSE
            "http", "streamable-http", "streamablehttp" -> McpTransportType.STREAMABLE_HTTP
            null -> if (command != null) McpTransportType.STDIO else McpTransportType.STREAMABLE_HTTP
            else -> null
        }

    private fun parseStdio(
        config: JsonObject,
        command: String?,
        address: HttpUrl?,
        name: String,
        newId: () -> String,
    ): McpServerConfig? {
        val args = parseArguments(config)
        val environment = stringMap(config, "env")
        return if (
            command.isNullOrBlank() ||
            address != null ||
            args == null ||
            environment == null ||
            environment.keys.any { !isMcpEnvironmentName(it) }
        ) {
            null
        } else {
            McpServerConfig(
                newId(),
                name,
                McpTransportType.STDIO,
                command = McpStdioCommand.join(command, args),
                environment = environment,
            )
        }
    }

    private fun parseArguments(config: JsonObject): List<String>? {
        val args = config["args"] as? JsonArray ?: return if ("args" in config) null else emptyList()
        return args.map { it.stringValue() ?: return null }
    }

    private fun parseHttp(
        config: JsonObject,
        command: String?,
        address: HttpUrl?,
        transport: McpTransportType,
        name: String,
        newId: () -> String,
    ): McpServerConfig? {
        val safeAddress = address?.takeIf { isSafeHttpAddress(it, command) } ?: return null
        val headers = stringMap(config, "headers") ?: return null
        if (!headers.all(::isSafeHeader)) return null
        return McpServerConfig(
            id = newId(),
            name = name,
            transportType = transport,
            url = safeAddress.toString(),
            headers = headers,
            authToken = config["authToken"].stringValue().orEmpty(),
        )
    }

    private fun isSafeHttpAddress(address: HttpUrl, command: String?): Boolean =
        address.username.isEmpty() &&
            address.password.isEmpty() &&
            address.fragment == null &&
            command == null

    private fun isSafeHeader(entry: Map.Entry<String, String>): Boolean =
        MCP_HEADER_NAME.matches(entry.key) && entry.value.none { it == '\r' || it == '\n' }

    private fun stringMap(config: JsonObject, key: String): Map<String, String>? {
        val element = config[key] ?: return emptyMap()
        val values = element as? JsonObject ?: return null
        return values.mapValues { (_, value) -> value.stringValue() ?: return null }
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MAX_IMPORT_LENGTH = 100_000
    private const val MAX_SERVERS = 30
    private const val MAX_NAME_LENGTH = 100
    private const val SERVER_COLLECTION = "mcpServers"
}

internal val MCP_HEADER_NAME = Regex("[!#$%&'*+.^_a-zA-Z0-9|~-]+")
