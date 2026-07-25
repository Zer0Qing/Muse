package io.zer0.muse.data.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.zer0.common.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "McpSessionManager"

/**
 * MCP 会话管理器(基于 OkHttp + HTTP 上的 JSON-RPC)。
 *
 * 管理多个服务器连接,支持自动工具发现。
 * MCP JSON-RPC 流程:initialize → tools/list → tools/call。
 */
class McpSessionManager(
    private val onToolsUpdated: (Uuid, List<McpTool>) -> Unit,
    private val onStatusChanged: (Uuid, McpConnectionStatus) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()
    private val sessions = ConcurrentHashMap<Uuid, SessionState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var requestId = 0

    private class SessionState(val config: McpServerConfig) {
        @Volatile
        var connected: Boolean = false
        @Volatile
        var tools: List<McpTool> = emptyList()
    }

    fun isConnected(configId: Uuid): Boolean {
        val s = sessions[configId]
        return s != null && s.connected
    }

    fun reconcile(configs: List<McpServerConfig>) {
        val activeIds = configs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }.associateBy { it.id }
        val toRemove = sessions.keys.filter { it !in activeIds }
        for (id in toRemove) {
            sessions.remove(id)
            onStatusChanged(id, McpConnectionStatus.Idle)
        }
        for (cfg in activeIds.values) {
            if (sessions[cfg.id] == null) {
                val state = SessionState(cfg)
                sessions[cfg.id] = state
                scope.launch { doConnect(state) }
            }
        }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): String {
        val state = sessions[serverId] ?: error("No MCP session for server $serverId")
        if (!state.connected) error("MCP server $serverId not connected")
        val params = buildJsonObject {
            put("name", JsonPrimitive(toolName))
            put("arguments", args)
        }
        val result = postJsonRpc(state.config, "tools/call", params)
        return extractToolResult(result)
    }

    fun getAllAvailableTools(): List<Triple<Uuid, String, McpTool>> {
        val result = mutableListOf<Triple<Uuid, String, McpTool>>()
        for (state in sessions.values) {
            if (state.connected) {
                for (tool in state.tools) {
                    result.add(Triple(state.config.id, state.config.commonOptions.name, tool))
                }
            }
        }
        return result
    }

    suspend fun shutdownAll() {
        for (state in sessions.values) {
            state.connected = false
        }
        sessions.clear()
    }

    private fun doConnect(state: SessionState) {
        val cfg = state.config
        onStatusChanged(cfg.id, McpConnectionStatus.Connecting)
        try {
            val initParams = buildJsonObject {
                put("protocolVersion", JsonPrimitive("2024-11-05"))
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", JsonPrimitive(cfg.commonOptions.name))
                    put("version", JsonPrimitive("1.0"))
                })
            }
            postJsonRpc(cfg, "initialize", initParams)
            val notif = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("notifications/initialized"))
                put("params", buildJsonObject { })
            }
            postRaw(cfg, notif)

            val toolsResult = postJsonRpc(cfg, "tools/list", buildJsonObject { })
            val discovered = parseToolsList(toolsResult)
            state.tools = discovered
            onToolsUpdated(cfg.id, discovered)

            state.connected = true
            onStatusChanged(cfg.id, McpConnectionStatus.Connected)
        } catch (e: Exception) {
            state.connected = false
            onStatusChanged(cfg.id, McpConnectionStatus.Error(e.message ?: "Connection failed"))
        }
    }

    private fun postJsonRpc(config: McpServerConfig, method: String, params: JsonObject): JsonObject {
        requestId++
        val request = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(requestId))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val url = resolveUrl(config)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(request.toString().toRequestBody(JSON_MEDIA_TYPE))
        for ((name, value) in config.commonOptions.headers) {
            reqBuilder.header(name, value)
        }
        val resp = httpClient.newCall(reqBuilder.build()).execute()
        val body = resp.body.string()
        resp.close()
        return try {
            json.parseToJsonElement(body) as? JsonObject ?: buildJsonObject { }
        } catch (_: Exception) {
            buildJsonObject { }
        }
    }

    private fun postRaw(config: McpServerConfig, body: JsonObject) {
        val url = resolveUrl(config)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        for ((name, value) in config.commonOptions.headers) {
            reqBuilder.header(name, value)
        }
        try {
            httpClient.newCall(reqBuilder.build()).execute().close()
        } catch (e: Exception) {
            // v1.131: 不再静默吞异常 — 记录到日志便于排查 SSE 推送通道失败/连接泄漏问题。
            Logger.w(TAG, "postRaw failed for ${resolveUrl(config)}: ${e.message}", e)
        }
    }

    private fun resolveUrl(config: McpServerConfig): String {
        return when (config) {
            is McpServerConfig.SseTransportServer -> config.url.replace("/sse", "/message")
            is McpServerConfig.StreamableHTTPServer -> config.url
        }
    }

    private fun parseToolsList(result: JsonObject): List<McpTool> {
        val resultObj = result["result"] as? JsonObject ?: return emptyList()
        val toolsArr = resultObj["tools"] as? JsonArray ?: return emptyList()
        val tools = mutableListOf<McpTool>()
        for (element in toolsArr) {
            val toolObj = element as? JsonObject ?: continue
            val name = toolObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            tools.add(
                McpTool(
                    name = name,
                    description = toolObj["description"]?.jsonPrimitive?.contentOrNull,
                    enable = true,
                    inputSchema = toolObj["inputSchema"] as? JsonObject,
                )
            )
        }
        return tools
    }

    private fun extractToolResult(result: JsonObject): String {
        val resultObj = result["result"] as? JsonObject ?: return "No result"
        val content = resultObj["content"] as? JsonArray ?: return resultObj.toString()
        val parts = mutableListOf<String>()
        for (element in content) {
            val obj = element as? JsonObject ?: run { parts.add(element.toString()); continue }
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                parts.add(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            } else {
                parts.add(obj.toString())
            }
        }
        return parts.joinToString("\n")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
