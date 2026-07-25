package io.zer0.muse.data.mcp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val Context.mcpDataStore by preferencesDataStore(name = "muse_mcp_servers")

/**
 * MCP 服务端配置持久化(RikkaHub SettingsStore MCP 移植版)。
 *
 * 以 JSON 形式将 MCP 服务端配置列表存储到 DataStore。
 * 提供 Flow 供响应式 UI 更新使用。
 */
class McpConfigStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val MCP_SERVERS_KEY = stringPreferencesKey("mcp_servers")
    }

    /** 所有 MCP 服务端配置的 Flow。 */
    val serversFlow: Flow<List<McpServerConfig>> =
        context.mcpDataStore.data.map { prefs ->
            val raw = prefs[MCP_SERVERS_KEY] ?: return@map emptyList()
            try {
                json.decodeFromString<List<McpServerConfig>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 新增或更新服务端配置。 */
    suspend fun upsertServer(config: McpServerConfig) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.filter { it.id != config.id } + config
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** 按 ID 移除服务端。 */
    suspend fun removeServer(id: Uuid) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.filter { it.id != id }
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** 更新指定服务端的工具列表(工具发现后调用)。 */
    suspend fun updateServerTools(serverId: Uuid, tools: List<McpTool>) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.map { config ->
                if (config.id != serverId) return@map config
                config.clone(
                    commonOptions = config.commonOptions.copy(tools = tools)
                )
            }
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** Import servers from JSON string. */
    suspend fun importFromJson(jsonString: String): Int {
        return try {
            val imported = json.decodeFromString<List<McpServerConfig>>(jsonString)
            context.mcpDataStore.edit { prefs ->
                val current = parseServers(prefs[MCP_SERVERS_KEY])
                val existingIds = current.map { it.id }.toSet()
                val newServers = imported.filter { it.id !in existingIds }
                val merged = current + newServers
                prefs[MCP_SERVERS_KEY] = json.encodeToString(merged)
            }
            imported.size
        } catch (_: Exception) {
            0
        }
    }

    /** 将所有服务端导出为 JSON 字符串。 */
    suspend fun exportAsJson(): String {
        return context.mcpDataStore.data.map { it[MCP_SERVERS_KEY] }.first() ?: "[]"
    }

    private fun parseServers(raw: String?): List<McpServerConfig> {
        if (raw == null) return emptyList()
        return try {
            json.decodeFromString<List<McpServerConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
