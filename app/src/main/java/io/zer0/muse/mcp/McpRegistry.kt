package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
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
 * 独立编写(按 MCP 官方规范),Apache 2.0。
 */
class McpRegistry(
    private val toolRegistry: ToolRegistry,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val context: Context,
    /**
     * v1.0.79 (C-3): 助手仓库 — 连接成功后自动把 MCP server 绑定到主助手扩展。
     * 此前新加的 MCP 不会自动配置到助手扩展,主聊天 tools schema 按助手 mcpServerIds
     * 过滤后看不到 MCP 工具(只能看到本地工具),必须手动去助手页勾选。
     * 为 null 时跳过自动绑定(测试/兼容)。
     */
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前所有 MCP server 配置(内存 + 持久化)。 */
    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    /** 每个 server 的连接状态快照(server id → state)。 */
    private val _serversState = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val serversState: StateFlow<Map<String, McpConnectionState>> = _serversState.asStateFlow()

    /** 活跃的 MCP client 实例(server id → client)。H-REG1: 用 ConcurrentHashMap 保证线程安全。 */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /**
     * 已完成 tools/list 的 server。
     *
     * 不能用“ToolRegistry 中是否存在某个前缀工具”判断就绪:合法的 MCP server
     * 可以返回空工具列表,否则每条首消息都会白等完整超时。
     */
    private val toolsReadyServers = ConcurrentHashMap.newKeySet<String>()

    /** tools/list 明确失败的 server,用于首条消息快速失败而不是等待完整超时。 */
    private val toolLoadFailedServers = ConcurrentHashMap.newKeySet<String>()

    /** M-REG3: 标记 startAll 是否已调用,避免与 init 块重复连接。 */
    private val startAllCalled = AtomicBoolean(false)

    /**
     * Phase 9.5 (P3-2): per-server 工具刷新重入保护。
     *
     * 当 tools/list_changed 通知高频到达时,避免并发 refreshTools 导致:
     *  - 工具列表短暂为空(unregister 后 register 前的窗口)
     *  - 重复拉取 tools/list 浪费网络
     * compareAndSet 保证同一 server 同时只有一个刷新在跑。
     */
    private val refreshingTools = ConcurrentHashMap<String, Boolean>()

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
            // 不依赖 init collector 的调度顺序,显式读取一次持久化配置后再连接。
            val saved = settings.mcpServersFlow.first()
            _servers.value = saved
            saved.filter { it.enabled && it.url.isNotBlank() }.forEach { connectServer(it) }
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
        // init collector 与 startAll 可能同时发现同一个 server,用 putIfAbsent 防止双 client
        // 并行握手、重复注册同名 MCP 工具和重复重连。
        if (clients.putIfAbsent(config.id, client) != null) {
            client.close()
            return
        }
        updateState(config.id, McpConnectionState.CONNECTING)

        // Phase 9.5 (P3-2): 注入 tools/list_changed 回调,server 工具列表变更时自动重新拉取。
        // 回调内 launch 独立协程,避免阻塞 McpClient 的 notification 处理协程。
        client.onToolsListChanged = {
            scope.launch {
                refreshTools(config.id, client)
            }
        }
        // B3-09: prompts/resources 变更时触发刷新信号(列表为按需查询,下轮打开自动取最新)
        client.onPromptsListChanged = {
            Logger.i(TAG, "[${config.id}] prompts/list_changed 通知,下次查询自动刷新")
        }
        client.onResourcesListChanged = {
            Logger.i(TAG, "[${config.id}] resources/list_changed 通知,下次查询自动刷新")
        }

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
            // Phase 9.5 (P3-2): 清除回调引用,避免断开后通知仍触发 refreshTools
            client.onToolsListChanged = null
            client.onPromptsListChanged = null
            client.onResourcesListChanged = null
            client.close()
            // 注销该 server 的所有工具(前缀匹配)
            unregisterTools(id)
        }
        toolsReadyServers.remove(id)
        toolLoadFailedServers.remove(id)
        // 清理刷新标记,避免下次重连时 compareAndSet 误判
        refreshingTools.remove(id)
        _serversState.update { it - id }
    }

    /**
     * 从 MCP server 拉取 tools/list,注册到 [ToolRegistry]。
     * 工具名: `mcp_{serverId}__{originalToolName}`
     */
    private suspend fun registerTools(serverId: String, client: McpClient) {
        toolsReadyServers.remove(serverId)
        toolLoadFailedServers.remove(serverId)
        var tools: List<McpTool>? = null
        for (attempt in 0 until 3) {
            val toolsResult = resultOf { client.listToolsOrNull() }
            tools = toolsResult
                .onError { msg, _ -> Logger.w(TAG, "[$serverId] tools/list 失败(attempt=${attempt + 1}): $msg") }
                .getOrNull()
            if (tools != null) break
            if (attempt < 2) delay(if (attempt == 0) 250L else 1_000L)
        }
        val resolvedTools = tools ?: run {
            toolLoadFailedServers.add(serverId)
            return
        }
        Logger.i(TAG, "[$serverId] 注册 ${resolvedTools.size} 个 MCP 工具")
        // v1.0.79 (C-3): 连接+注册成功后,自动把 server 绑定到主助手扩展,
        // 否则主聊天 tools schema 按助手 mcpServerIds 过滤,看不到新 MCP 的工具。
        autoBindToDefaultAssistant(serverId)
        resolvedTools.forEach { tool ->
            val registeredName = "mcp_${serverId}__${tool.name}"
            val description = tool.description ?: context.getString(R.string.mcp_tool_no_description)
            // 从 inputSchema 解析参数名(JSON Schema properties 的 key)
            val params = parseInputSchema(tool.inputSchema)
            val required = parseRequired(tool.inputSchema)
            val parameterTypes = parseInputSchemaTypes(tool.inputSchema)
            toolRegistry.registerJson(
                ToolRegistry.ToolDef(
                    name = registeredName,
                    description = "[MCP server=$serverId tool=${tool.name}] $description",
                    parameters = params,
                    required = required,
                    category = "mcp",
                    parameterTypes = parameterTypes,
                    rawParametersJsonSchema = tool.inputSchema?.toString(),
                    riskLevel = inferMcpRisk(tool.name),
                ),
            ) { args ->
                // 保留 inputSchema 声明的 JSON 类型,避免 boolean/number/array/object 被压成字符串。
                val arguments = coerceMcpArguments(args, tool.inputSchema)
                val result = withTimeoutOrNull(120_000L) {
                    client.callTool(tool.name, arguments)
                }
                if (result == null) {
                    // v1.0.79 (D-1): 超时不再静默 — 记录日志便于排查,并把失败原因回传给模型
                    Logger.w(TAG, "[$serverId] 工具 ${tool.name} 调用超时(120s)")
                    "Error: " + context.getString(R.string.mcp_tool_call_timeout, tool.name)
                } else if (result.isError) {
                    // v1.0.79 (A-1): 错误详情完整回传 — server 返回的 content/structuredContent
                    // 都带回,模型拿得到失败原因才能自我纠正。
                    val detail = formatToolResult(result)
                    Logger.w(TAG, "[$serverId] 工具 ${tool.name} 调用失败: $detail")
                    "Error: " + context.getString(
                        R.string.mcp_tool_call_failed,
                        tool.name,
                    ) + ": " + detail
                } else {
                    formatToolResult(result)
                }
            }
        }
        toolsReadyServers.add(serverId)
    }

    /**
     * v1.0.79 (C-3): 把已连接的 MCP server 自动绑定到主助手扩展。
     *
     * 背景: 新加的 MCP server 连接成功后不会自动进入助手配置(mcpServerIdsJson),
     * 而聊天 tools schema 按助手 mcpServerIds 过滤 → 主聊天看不到 MCP 工具。
     * 此方法在 registerTools 成功后调用,把 serverId 写入主助手扩展列表(幂等)。
     *
     * 注意: 只在主助手(默认助手)上自动绑定;子助手/团队助手仍由用户在助手页
     * 显式勾选,避免自动扩散到不相关的助手。
     */
    private suspend fun autoBindToDefaultAssistant(serverId: String) {
        val repo = assistantRepository ?: return
        if (serverId.isBlank()) return
        try {
            // 主助手 id 固定为 "default"(与 MemoryTicker.MAIN_ASSISTANT_ID 一致)
            val defaultAssistant = repo.getById("default") ?: run {
                repo.ensureDefaultExists()
                repo.getById("default")
            } ?: return
            val bound = repo.parseMcpServerIds(defaultAssistant).toMutableSet()
            if (serverId in bound) return // 已绑定,幂等
            bound.add(serverId)
            repo.upsert(
                defaultAssistant.copy(mcpServerIdsJson = repo.serializeStringList(bound.toList()))
            )
            Logger.i(TAG, "[$serverId] 已自动绑定到主助手扩展(mcpServerIds=${bound.size})")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "[$serverId] 自动绑定主助手失败(不影响工具注册): ${e.message}")
        }
    }

    /** 注销指定 server 的所有工具。 */
    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}__"
        toolRegistry.listTools()
            .filter { it.name.startsWith(prefix) }
            .forEach { toolRegistry.unregister(it.name) }
    }

    /**
     * Phase 9.5 (P3-2): 刷新指定 server 的工具列表。
     *
     * 触发场景:server 发出 `notifications/tools/list_changed`(MCP 2025-03-26 §Tools),
     * 表示工具集可能增删改,client 缓存的 tools/list 已过期。
     *
     * 流程:
     *  1. 重入保护:compareAndSet 防并发刷新
     *  2. 校验 client 仍连接(断开期间的通知忽略)
     *  3. unregisterTools(清旧) → registerTools(拉新)
     *
     * 幂等性:registerTools 内 toolRegistry.register 是覆盖式,重复注册同名工具安全;
     * 但先 unregister 再 register 保证删除的工具被正确移除(仅覆盖无法删除)。
     *
     * @param serverId server 标识
     * @param client 对应的 McpClient(从 clients map 取出后可能已断开,需校验 state)
     */
    private suspend fun refreshTools(serverId: String, client: McpClient) {
        // 重入保护:同一 server 同时只允许一次刷新。
        // ConcurrentHashMap 无 compareAndSet,用 putIfAbsent 模拟:返回 null 表示成功获取锁。
        if (refreshingTools.putIfAbsent(serverId, true) != null) {
            Logger.d(TAG, "[$serverId] tools/list_changed 触发刷新,但已有刷新在跑,跳过")
            return
        }
        try {
            // 校验 client 仍连接(断开期间的通知可能还在事件队列里)
            if (client.state.value != McpConnectionState.CONNECTED) {
                Logger.d(TAG, "[$serverId] tools/list_changed 到达但 client 未连接,跳过刷新")
                return
            }
            Logger.i(TAG, "[$serverId] 收到 tools/list_changed,重新拉取工具列表")
            unregisterTools(serverId)
            registerTools(serverId, client)
        } finally {
            refreshingTools.remove(serverId)
        }
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

    /**
     * 等待助手绑定的 MCP server 完成握手并注册工具。
     *
     * 应用刚启动时 MCP 连接和用户发送消息可能并发发生;如果只读取一次 ToolRegistry,
     * 首条消息会在 tools/list 返回前看不到 MCP 工具。超时后仍允许聊天继续,但会记录未就绪状态。
     */
    suspend fun awaitToolsForServers(
        serverIds: Set<String>,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        if (serverIds.isEmpty()) return true
        startAll()
        // 未配置、已禁用或空 URL 的 server 不会被 startAll 连接,直接返回失败状态,
        // 不让每条消息无意义地等待完整 timeout。
        // 这里不能只读 _servers.value:startAll() 是异步启动,首条消息可能恰好抢在
        // settings flow collector 之前。直接读取一次持久化快照,消除这个启动竞态。
        val savedServers = settings.mcpServersFlow.first()
        _servers.value = savedServers
        val configuredServerIds = savedServers
            .asSequence()
            .filter { it.enabled && it.url.isNotBlank() }
            .map { it.id }
            .toSet()
        if (!serverIds.all { it in configuredServerIds }) return false
        return withTimeoutOrNull<Boolean>(timeoutMs) {
            var ready = false
            var failed = false
            while (!ready && !failed) {
                ready = serverIds.all { it in toolsReadyServers }
                failed = !ready && serverIds.any { id ->
                    id in toolLoadFailedServers ||
                        serversState.value[id] in setOf(
                            McpConnectionState.FAILED,
                            McpConnectionState.NEEDS_AUTH,
                        )
                }
                if (!ready && !failed) delay(50L)
            }
            ready && !failed
        } ?: false
    }

    /** 从 inputSchema 解析参数类型,供 ToolRegistry 的 JSON 工具校验和 Provider schema 使用。 */
    private fun parseInputSchemaTypes(schema: JsonElement?): Map<String, String> {
        val obj = schema as? JsonObject ?: return emptyMap()
        val props = obj["properties"] as? JsonObject ?: return emptyMap()
        return props.mapValues { (_, definition) ->
            ((definition as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: "string"
        }
    }

    /** MCP 工具不能默认标成 SAFE,否则 ASK 模式会绕过审批执行写入/删除操作。 */
    private fun inferMcpRisk(toolName: String): ToolRiskLevel {
        val name = toolName.lowercase()
        return when {
            listOf("delete", "destroy", "remove", "send", "publish", "commit", "execute", "run").any { name.contains(it) } ->
                ToolRiskLevel.HIGH
            listOf("create", "new", "update", "edit", "write", "add", "move", "rename", "open", "post", "put").any { name.contains(it) } ->
                ToolRiskLevel.NORMAL
            else -> ToolRiskLevel.SAFE
        }
    }

    /** 把 ToolRegistry 的字符串参数恢复为 MCP inputSchema 声明的 JSON 值。 */
    private fun coerceMcpArguments(args: JsonObject, schema: JsonElement?): JsonObject {
        val types = parseInputSchemaTypes(schema)
        return buildJsonObject {
            args.forEach { (name, value) ->
                val raw = (value as? JsonPrimitive)?.content ?: value.toString()
                val type = types[name]
                put(name, when (type) {
                    "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    "array", "object" -> runCatching { AppJson.parseToJsonElement(raw) }
                        .getOrElse { JsonPrimitive(raw) }
                    else -> JsonPrimitive(raw)
                })
            }
        }
    }

    /** 将 MCP 内容统一转换为可回填给模型的文本,错误结果也保留 server 原因。 */
    private fun formatToolContent(content: List<JsonElement>): String =
        content.joinToString("\n") { element ->
            val obj = element as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.content
            val text = (obj?.get("text") as? JsonPrimitive)?.content
            if (type == "text" && text != null) text else element.toString()
        }

    /** MCP server 可能只返回 structuredContent,不能把这种成功结果误报为空。 */
    private fun formatToolResult(result: McpToolCallResult): String {
        val parts = buildList {
            formatToolContent(result.content).takeIf { it.isNotBlank() }?.let(::add)
            result.structuredContent?.let { add(it.toString()) }
        }
        return parts.joinToString("\n").ifBlank { "(MCP server returned no content)" }
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
        Logger.i(TAG, "McpRegistry shutdown: scope cancelled")
    }

    private companion object {
        const val TAG = "McpRegistry"
    }
}
