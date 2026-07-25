package io.zer0.muse.data.mcp

import android.content.Context
import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * MCP 子系统管理器（移植自 RikkaHub McpManager.kt）。
 *
 * 协调 MCP 配置持久化、会话管理、工具发现，
 * 以及将工具注入到 Muse ToolRegistry。
 *
 * 用法：
 * 1. 使用 context 初始化
 * 2. 调用 [start] 开始监听配置变更
 * 3. MCP 工具会自动注入到 [ToolRegistry]
 */
class McpManager(private val context: Context) {

    private val configStore = McpConfigStore(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionManager = McpSessionManager(
        onToolsUpdated = { serverId, tools ->
            scope.launch { configStore.updateServerTools(serverId, tools) }
        },
        onStatusChanged = { serverId, status ->
            _connectionStatuses.update { current ->
                current.toMutableMap().apply { put(serverId, status) }
            }
        },
    )

    private val _connectionStatuses = MutableStateFlow<Map<Uuid, McpConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<Uuid, McpConnectionStatus>> = _connectionStatuses.asStateFlow()

    /** 开始监听配置变更并管理会话。 */
    fun start() {
        scope.launch {
            configStore.serversFlow
                .distinctUntilChanged()
                .collect { servers ->
                    sessionManager.reconcile(servers)
                }
        }
    }

    /** 获取所有已连接服务器中已启用的 MCP 工具。 */
    fun getAllAvailableTools(): List<McpToolInfo> {
        return sessionManager.getAllAvailableTools().map { (serverId, serverName, tool) ->
            McpToolInfo(
                serverId = serverId,
                serverName = serverName,
                tool = tool,
            )
        }
    }

    /** 调用 MCP 工具。返回工具执行的文本结果。 */
    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): String {
        return sessionManager.callTool(serverId, toolName, args)
    }

    /** 添加或更新 MCP 服务器。 */
    suspend fun upsertServer(config: McpServerConfig) {
        configStore.upsertServer(config)
    }

    /** 移除一个 MCP 服务器。 */
    suspend fun removeServer(id: Uuid) {
        configStore.removeServer(id)
    }

    /** 切换服务器的启用/禁用状态。 */
    suspend fun toggleServer(config: McpServerConfig, enabled: Boolean) {
        configStore.upsertServer(
            config.clone(commonOptions = config.commonOptions.copy(enable = enabled))
        )
    }

    /** 从 JSON 导入服务器。 */
    suspend fun importFromJson(json: String): Int = configStore.importFromJson(json)

    /** 将服务器导出为 JSON。 */
    suspend fun exportAsJson(): String = configStore.exportAsJson()

    /**
     * 将已发现的 MCP 工具注入到 Muse ToolRegistry。
     * 在 MCP 服务器已连接并发现工具后调用此方法。
     */
    fun injectToolsIntoRegistry(toolRegistry: ToolRegistry) {
        val mcpTools = getAllAvailableTools()
        for (info in mcpTools) {
            val tool = info.tool
            if (!tool.enable) continue

            val params = mutableMapOf<String, String>()
            tool.inputSchema?.let { schema ->
                val properties = schema["properties"]
                if (properties is JsonObject) {
                    for ((key, value) in properties) {
                        val desc = if (value is JsonObject) {
                            (value["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: key
                        } else key
                        params[key] = desc
                    }
                }
            }

            val required = mutableSetOf<String>()
            tool.inputSchema?.let { schema ->
                val reqArr = schema["required"]
                if (reqArr is kotlinx.serialization.json.JsonArray) {
                    for (item in reqArr) {
                        if (item is kotlinx.serialization.json.JsonPrimitive) {
                            required.add(item.content)
                        }
                    }
                }
            }

            toolRegistry.register(
                ToolRegistry.ToolDef(
                    name = "mcp_${info.serverName}_${tool.name}",
                    description = tool.description ?: tool.name,
                    parameters = params,
                    required = required,
                    category = "mcp",
                ),
            ) { args: Map<String, String> ->
                val argJson = kotlinx.serialization.json.buildJsonObject {
                    for ((key, value) in args) {
                        put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    }
                }
                kotlinx.coroutines.runBlocking {
                    callTool(info.serverId, tool.name, argJson)
                }
            }
        }
    }

    /** 关闭所有 MCP 会话。 */
    suspend fun shutdown() {
        sessionManager.shutdownAll()
    }
}

/**
 * MCP 工具信息，结合服务器上下文与工具定义。
 */
data class McpToolInfo(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool,
)
