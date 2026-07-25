package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.zer0.muse.R

/**
 * Phase 9.5 (M3): MCP server 注册表 + 持久化 + ToolRegistry 桥接。
 *
 * 职责:
 *  1. 持久化 MCP server 配置到 DataStore(JSON 整存整取)
 *  2. 管理多个 [McpClient] 实例(每个 [McpServerConfig] 对应一个)
 *  3. 连接成功后,把 server 暴露的 tools 注册到本地 [ToolRegistry],
 *     工具名加前缀 `mcp_{serverId}__{toolName}` 避免命名冲突
 *  4. 暴露 [serversState] StateFlow 供 UI 观察所有 server 的连接状态
 *  5. 断开连接时,从 ToolRegistry 注销对应工具
 *
 * 调用方:
 *  - [io.zer0.muse.ui.ChatViewModel] 启动时调用 [startAll] 连接所有启用的 server
 *  - [io.zer0.muse.ui.SettingsScreen] 通过 [addServer] / [removeServer] / [updateServer] 管理
 *  - [io.zer0.muse.tools.ToolRegistry] 收到 LLM tool call 时,通过 `mcp_` 前缀路由到 [McpClient.callTool]
 *
 * 持久化策略:
 *  - DataStore Preferences key="mcp_servers_json",值是 [McpServerConfig] 列表 JSON
 *  - 内存里 [servers] 是当前配置列表,[clients] 是对应的 client 实例 map
 *  - [serversState] 是 server id → 连接状态的快照,UI 实时刷新
 *
 * 独立编写(参考 MCP 官方规范),Apache 2.0。
 */
class McpRegistry(
    private val toolRegistry: ToolRegistry,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // v1.113: MCP 工具调用专用线程池,避免 runBlocking 耗尽 Dispatchers.IO 的 64 线程上限。
    // MCP 工具是远程调用,可能长时间阻塞,若与 LLM/Web 搜索共享 IO 线程池会导致整个应用网络卡死。
    // 线程数设为 8(足够并发多个 MCP server,又不过多占用系统资源)。
    private val mcpToolExecutor = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "mcp-tool-${System.nanoTime()}").apply { isDaemon = true }
    }
    private val mcpToolDispatcher = mcpToolExecutor.asCoroutineDispatcher()

    /** 当前所有 MCP server 配置(内存 + 持久化)。 */
    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    /** 每个 server 的连接状态快照(server id → state)。 */
    private val _serversState = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val serversState: StateFlow<Map<String, McpConnectionState>> = _serversState.asStateFlow()

    /** 活跃的 MCP client 实例(server id → client)。H-REG1: 用 ConcurrentHashMap 保证线程安全。 */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /** M-REG3: 标记 startAll 是否已调用,避免与 init 块重复连接。 */
    private val startAllCalled = AtomicBoolean(false)

    init {
        // 启动时从 DataStore 加载已保存的 server 配置
        scope.launch {
            settings.mcpServersFlow.collect { saved ->
                _servers.value = saved
                // 自动连接所有启用的 server(尚未连接的)
                saved.filter { it.enabled && it.url.isNotBlank() }.forEach { cfg ->
                    if (!clients.containsKey(cfg.id)) {
                        connectServer(cfg)
                    }
                }
            }
        }
    }

    /**
     * 添加 MCP server 并持久化 + 自动连接。
     * @return 是否成功(id 不重复才添加)
     */
    suspend fun addServer(config: McpServerConfig): Boolean {
        val current = _servers.value
        if (current.any { it.id == config.id }) return false
        val newList = current + config
        persist(newList)
        _servers.value = newList
        if (config.enabled && config.url.isNotBlank()) {
            connectServer(config)
        }
        return true
    }

    /** 更新已有 server 配置(id 匹配)。重连已连接的 server。 */
    suspend fun updateServer(config: McpServerConfig) {
        val newList = _servers.value.map { if (it.id == config.id) config else it }
        persist(newList)
        _servers.value = newList
        // 断开旧连接,用新配置重连
        disconnectServer(config.id)
        if (config.enabled && config.url.isNotBlank()) {
            connectServer(config)
        }
    }

    /** 删除 server 并断开连接。 */
    suspend fun removeServer(id: String) {
        val newList = _servers.value.filterNot { it.id == id }
        persist(newList)
        _servers.value = newList
        disconnectServer(id)
    }

    /** 启动所有启用的 server(应用启动时调用)。 */
    fun startAll() {
        // M-REG3: 用 AtomicBoolean 保证只调用一次,避免与 init 块重复连接
        if (!startAllCalled.compareAndSet(false, true)) return
        scope.launch {
            _servers.value.filter { it.enabled && it.url.isNotBlank() }.forEach { connectServer(it) }
        }
    }

    /** 手动重连指定 server。 */
    fun reconnect(id: String) {
        scope.launch {
            val cfg = _servers.value.firstOrNull { it.id == id } ?: return@launch
            disconnectServer(id)
            connectServer(cfg)
        }
    }

    // ── Phase 10.4: OAuth 2.1 PKCE 代理(供 UI 调用) ──────────────────────────

    /**
     * Phase 10.4: 启动指定 server 的 OAuth 授权流程。
     * @return 授权 URL(UI 用 Intent.ACTION_VIEW 打开);null 表示 server 不存在或 OAuth 未启用
     */
    fun startOAuthFlow(serverId: String): String? {
        val client = clients[serverId] ?: return null
        return runCatching { client.startOAuthFlow() }.getOrNull()
    }

    /**
     * Phase 10.4: 完成 OAuth 授权流程(浏览器回调后调用)。
     * 成功后自动重连 server。
     * @return true 成功;false 失败(state 不匹配 / 交换失败 / server 不存在)
     */
    suspend fun completeOAuthFlow(serverId: String, redirectUri: String): Boolean {
        val client = clients[serverId] ?: return false
        val ok = client.completeOAuthFlow(redirectUri)
        if (ok) {
            // 授权成功,重连(这次能拿到 token,正常握手)
            val cfg = _servers.value.firstOrNull { it.id == serverId } ?: return ok
            disconnectServer(serverId)
            connectServer(cfg)
        }
        return ok
    }

    /** Phase 10.4: 撤销指定 server 的 OAuth 授权。 */
    suspend fun revokeOAuth(serverId: String) {
        val client = clients[serverId] ?: return
        client.revokeOAuth()
    }

    /** Phase 10.4: 指定 server 是否需要 OAuth 授权。 */
    fun needsOAuth(serverId: String): Boolean {
        val client = clients[serverId] ?: return false
        return client.needsOAuth()
    }

    // ── Phase 11.1.2: resources / prompts 聚合 API ──────────────────────────

    /**
     * Phase 11.1.2: 聚合所有已连接 server 的 resources。
     * 每个 resource 标注来源 server,UI 展示给用户选择注入 context。
     *
     * @return 所有已连接 server 的资源列表(serverId → resource 列表)
     */
    suspend fun listAllResources(): Map<String, List<McpResource>> {
        val result = mutableMapOf<String, List<McpResource>>()
        clients.forEach { (serverId, client) ->
            if (client.state.value == McpConnectionState.CONNECTED) {
                resultOf {
                    val resources = client.listResources()
                    if (resources.isNotEmpty()) result[serverId] = resources
                }.onError { msg, _ ->
                    Logger.w(TAG, "[$serverId] listResources 失败: $msg")
                }
            }
        }
        return result
    }

    /**
     * Phase 11.1.2: 读取指定 server 的指定 resource 内容。
     * @return 资源内容列表(可能多个);失败返回空列表
     */
    suspend fun readResource(serverId: String, uri: String): List<McpResourceContent> {
        val client = clients[serverId] ?: return emptyList()
        if (client.state.value != McpConnectionState.CONNECTED) return emptyList()
        return resultOf { client.readResource(uri) }
            .onError { msg, _ -> Logger.w(TAG, "[$serverId] readResource($uri) 失败: $msg") }
            .getOrNull() ?: emptyList()
    }

    /**
     * Phase 11.1.2: 聚合所有已连接 server 的 prompts。
     * 每个 prompt 标注来源 server,UI 展示为快捷指令按钮。
     *
     * @return 所有已连接 server 的 prompt 列表(serverId → prompt 列表)
     */
    suspend fun listAllPrompts(): Map<String, List<McpPrompt>> {
        val result = mutableMapOf<String, List<McpPrompt>>()
        clients.forEach { (serverId, client) ->
            if (client.state.value == McpConnectionState.CONNECTED) {
                resultOf {
                    val prompts = client.listPrompts()
                    if (prompts.isNotEmpty()) result[serverId] = prompts
                }.onError { msg, _ ->
                    Logger.w(TAG, "[$serverId] listPrompts 失败: $msg")
                }
            }
        }
        return result
    }

    /**
     * Phase 11.1.2: 获取指定 server 的指定 prompt 内容。
     * @return prompt 消息列表;失败返回空 messages
     */
    suspend fun getPrompt(
        serverId: String,
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): McpPromptResult {
        val client = clients[serverId] ?: return McpPromptResult()
        if (client.state.value != McpConnectionState.CONNECTED) return McpPromptResult()
        return resultOf { client.getPrompt(name, arguments) }
            .onError { msg, _ -> Logger.w(TAG, "[$serverId] getPrompt($name) 失败: $msg") }
            .getOrNull() ?: McpPromptResult()
    }

    // ── Phase 11.1.3: Dynamic Client Registration 代理 ──────────────────────

    /**
     * Phase 11.1.3: 自动发现 OAuth 元数据 + 动态注册客户端。
     *
     * 成功后把填好的 [McpOAuthConfig] 持久化到 server 配置,然后转 NEEDS_AUTH 等 UI 授权。
     * 失败原因:server URL 不可达 / 不支持动态注册 / 注册端点返回错误。
     *
     * @return 填好的 [McpOAuthConfig];失败返回 null
     */
    suspend fun discoverAndRegisterOAuth(serverId: String): McpOAuthConfig? {
        val cfg = _servers.value.firstOrNull { it.id == serverId } ?: return null
        if (cfg.url.isBlank()) return null
        val oauthConfig = McpDynamicRegistration.discoverAndRegister(
            serverUrl = cfg.url,
            // L-REG1: 从 config 读取 redirectUri,而非硬编码
            redirectUri = cfg.oauthConfig.redirectUri,
        ) ?: return null
        // 持久化到 server 配置
        val updated = cfg.copy(oauthConfig = oauthConfig)
        updateServer(updated)
        Logger.i(TAG, "[$serverId] 动态注册成功,clientId=${oauthConfig.clientId}")
        return oauthConfig
    }

    /**
     * 连接到指定 server,握手成功后注册 tools 到 [ToolRegistry]。
     */
    private suspend fun connectServer(config: McpServerConfig) {
        if (clients.containsKey(config.id)) return  // 已连接
        // Phase 10.4: 传入 settings,使 McpClient 支持 OAuth token 持久化
        val client = McpClient(config, settings = settings)
        clients[config.id] = client
        updateState(config.id, McpConnectionState.CONNECTING)

        client.start()
        // M-REG1: 用 first{} 替代 200ms 轮询,等待终态(CONNECTED/FAILED/NEEDS_AUTH)
        val finalState = withTimeoutOrNull(10_000L) {
            client.state.first {
                it == McpConnectionState.CONNECTED ||
                    it == McpConnectionState.FAILED ||
                    it == McpConnectionState.NEEDS_AUTH
            }
        }
        if (finalState == null) {
            // 超时:底层 client.start() 启动的连接协程仍在后台运行,
            // 直接断开 client(其 close() 会 scope.cancel() 取消所有协程),避免 registry 状态不准
            Logger.w(TAG, "[${config.id}] connectServer 10s 超时,取消底层连接")
            disconnectServer(config.id)
            return
        }
        updateState(config.id, finalState)

        // 连接成功,拉取 tools 并注册到 ToolRegistry
        if (finalState == McpConnectionState.CONNECTED) {
            registerTools(config.id, client)
        }
    }

    /** 断开 server 连接,注销 tools。 */
    private fun disconnectServer(id: String) {
        clients.remove(id)?.let { client ->
            client.close()
            // 注销该 server 的所有工具(前缀匹配)
            unregisterTools(id)
        }
        _serversState.update { it - id }
    }

    /**
     * 从 MCP server 拉取 tools/list,注册到 [ToolRegistry]。
     * 工具名: `mcp_{serverId}__{originalToolName}`
     */
    private suspend fun registerTools(serverId: String, client: McpClient) {
        val tools = resultOf { client.listTools() }.getOrNull() ?: emptyList()
        Logger.i(TAG, "[$serverId] 注册 ${tools.size} 个 MCP 工具")
        tools.forEach { tool ->
            val registeredName = "mcp_${serverId}__${tool.name}"
            val description = tool.description ?: context.getString(R.string.mcp_tool_no_description)
            // 从 inputSchema 解析参数名(JSON Schema properties 的 key)
            val params = parseInputSchema(tool.inputSchema)
            val required = parseRequired(tool.inputSchema)
            toolRegistry.register(
                ToolRegistry.ToolDef(
                    name = registeredName,
                    description = "[MCP] $description",
                    parameters = params,
                    required = required,
                    category = "mcp",
                ),
            ) { args ->
                // 执行:把 args map 转 JsonObject,调 McpClient.callTool
                val arguments = buildJsonObject {
                    args.forEach { (k, v) -> put(k, v) }
                }
                // v1.134 P0-3: ToolFn 已改为 suspend 签名,此处直接用 withTimeoutOrNull
                // 协程化调用 MCP 工具,消除 runBlocking 反模式。
                // 旧方案(v1.113):runBlocking(mcpToolDispatcher) + withTimeoutOrNull,占用专用 8 线程池
                // 新方案:直接在调用方协程上下文执行,超时用 withTimeoutOrNull 保护
                // mcpToolDispatcher 仍保留用于其他同步路径(如 listTools 桥接),此处不再需要
                val result = withTimeoutOrNull(120_000L) {
                    client.callTool(tool.name, arguments)
                }
                if (result == null) {
                    context.getString(R.string.mcp_tool_call_timeout, tool.name)
                } else if (result.isError) {
                    context.getString(R.string.mcp_tool_call_failed, tool.name)
                } else {
                    // 把 content 数组里的 text 部分拼接返回
                    result.content.joinToString("\n") { el ->
                        val obj = el as? JsonObject
                        val type = (obj?.get("type") as? JsonPrimitive)?.content
                        val text = (obj?.get("text") as? JsonPrimitive)?.content
                        if (type == "text" && text != null) text else el.toString()
                    }
                }
            }
        }
    }

    /** 注销指定 server 的所有工具。 */
    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}__"
        toolRegistry.listTools()
            .filter { it.name.startsWith(prefix) }
            .forEach { toolRegistry.unregister(it.name) }
    }

    /** 从 inputSchema(JSON Schema)解析参数名 → 描述。 */
    private fun parseInputSchema(schema: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (schema == null) return emptyMap()
        val obj = schema as? JsonObject ?: return emptyMap()
        val props = obj["properties"] as? JsonObject ?: return emptyMap()
        return props.entries.associate { (name, def) ->
            val defObj = def as? JsonObject
            val desc = (defObj?.get("description") as? JsonPrimitive)?.content
                ?: (defObj?.get("type") as? JsonPrimitive)?.content
                ?: "any"
            name to desc
        }
    }

    /** 从 inputSchema 解析 required 字段。 */
    private fun parseRequired(schema: kotlinx.serialization.json.JsonElement?): Set<String> {
        if (schema == null) return emptySet()
        val obj = schema as? JsonObject ?: return emptySet()
        val required = obj["required"] ?: return emptySet()
        return runCatching {
            // required 是 JSON 数组 of string
            val arr = required as? kotlinx.serialization.json.JsonArray ?: return emptySet()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 更新单个 server 的连接状态。 */
    private fun updateState(id: String, state: McpConnectionState) {
        _serversState.update { it + (id to state) }
    }

    /** 持久化 server 列表到 DataStore。 */
    private suspend fun persist(servers: List<McpServerConfig>) {
        settings.saveMcpServers(servers)
    }

    /**
     * v1.113: 关闭 registry,释放线程池资源。
     *
     * McpRegistry 是 Koin 单例,App 退出时调用。关闭专用线程池和 scope,
     * 避免线程泄漏。
     */
    fun shutdown() {
        scope.cancel()
        mcpToolExecutor.shutdown()
        Logger.i(TAG, "McpRegistry shutdown: scope cancelled, tool executor shut down")
    }

    private companion object {
        const val TAG = "McpRegistry"
    }
}
