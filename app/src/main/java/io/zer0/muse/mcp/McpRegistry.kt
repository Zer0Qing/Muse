package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

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
 *  - [ChatViewModel] 启动时调用 [startAll] 连接所有启用的 server
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
/** v1.0.92 (B-6): server id 合法形态(与工具名 "mcp_{id}__{tool}" 前缀约定对齐)。 */
private val SERVER_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

class McpRegistry(
    private val toolRegistry: ToolRegistry,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val context: Context,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)

    /** 当前所有 MCP server 配置(内存 + 持久化)。 */
    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    /** 每个 server 的连接状态快照(server id → state)。 */
    private val _serversState = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val serversState: StateFlow<Map<String, McpConnectionState>> = _serversState.asStateFlow()

    enum class ConnectionIssue {
        INVALID_ADDRESS,
        DnsFailed,
        ADDRESS_BLOCKED,
        RUNTIME_REQUIRED,
        START_FAILED,
        HANDSHAKE_FAILED,
        TIMED_OUT,
        TOOLS_FAILED,
    }

    data class ServerStatus(
        val connection: McpConnectionState = McpConnectionState.DISCONNECTED,
        val tools: List<McpTool>? = null,
        val issue: ConnectionIssue? = null,
        val lastConnectedAt: Long = 0,
    )

    private val _serverStatuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, ServerStatus>> = _serverStatuses.asStateFlow()
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val clientConfigs = mutableMapOf<String, McpServerConfig>()
    private val clientJobs = mutableMapOf<String, Job>()
    private val connectionLock = Any()
    private val configLock = Mutex()
    private val refreshingTools = ConcurrentHashMap<String, McpClient>()

    data class ServerReadiness(
        val connected: Boolean,
        val toolsReady: Boolean,
        val assistantBound: Boolean,
    )

    suspend fun readiness(serverId: String, assistantId: String = "default"): ServerReadiness {
        val status = serverStatuses.value[serverId]
        val repo = assistantRepository
        val assistantBound = repo?.getById(assistantId)?.let { assistant ->
            repo.parseMcpServerIds(assistant).contains(serverId)
        } ?: false
        return ServerReadiness(status?.connection == McpConnectionState.CONNECTED, status?.tools != null, assistantBound)
    }

    init {
        registerManagementTools()
        scope.launch {
            settings.mcpServersFlow.collect {
                configLock.withLock { reconcile(settings.mcpServersFlow.first()) }
            }
        }
    }

    suspend fun addServer(config: McpServerConfig): Boolean = addServers(listOf(config))

    suspend fun addServers(configs: List<McpServerConfig>): Boolean = configLock.withLock {
        val current = settings.mcpServersFlow.first()
        val ids = configs.map { it.id }
        if (ids.isEmpty() || ids.any { !SERVER_ID_REGEX.matches(it) } ||
            ids.distinct().size != ids.size || current.any { it.id in ids }
        ) {
            return@withLock false
        }
        val updated = current + configs
        persist(updated)
        reconcile(updated)
        true
    }

    suspend fun updateServer(config: McpServerConfig) = configLock.withLock {
        val current = settings.mcpServersFlow.first()
        val updated = current.map { if (it.id == config.id) config else it }
        val prior = current.firstOrNull { it.id == config.id }
        if (updated != current) {
            if (prior != null && (prior.url != config.url || prior.transportType != config.transportType)) {
                settings.clearMcpToken(config.id)
            }
            persist(updated)
        }
        reconcile(updated)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = configLock.withLock {
        val current = settings.mcpServersFlow.first()
        val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (updated != current) persist(updated)
        reconcile(updated)
    }

    private fun reconcile(configs: List<McpServerConfig>) = synchronized(connectionLock) {
        _servers.value = configs
        val enabled = configs.filter { it.isConnectable() }.associateBy { it.id }
        val changed = clientConfigs.filter { (id, old) -> enabled[id]?.copy(lastConnectedAt = 0) != old }.keys.toList()
        changed.forEach(::disconnectServer)
        enabled.values.forEach(::connectServer)
    }

    /** 配置工具使用的幂等写入：同 id 时更新，否则新增。 */
    suspend fun upsertServer(config: McpServerConfig): String {
        // G2: 入口 SSRF 校验 — 拒绝把新 MCP 指向内网/RFC1918/169.254/metadata 等地址
        // (loopback 除外,Ollama/本地 MCP host 开发场景)。已存在的 server 允许重连原配置
        // (loopback 放行由 isSsrfBlocked 内置,非 loopback 的存量内网配置保持可用,
        //  仅在重新 upsert 时被拦)。
        if (withContext(Dispatchers.IO) { config.isSsrfBlocked() }) {
            return "MCP 服务器 ${config.name}(${config.id}) URL 指向内网/保留地址,已被 SSRF 防护拒绝(仅允许 http/https 公网地址或 localhost)。"
        }
        return if (_servers.value.any { it.id == config.id }) {
            updateServer(config)
            "已更新 MCP 服务器 ${config.name}(${config.id})，正在重新连接。"
        } else if (addServer(config)) {
            "已添加 MCP 服务器 ${config.name}(${config.id})，正在连接并发现工具。"
        } else {
            "MCP 服务器 ${config.id} 写入失败。"
        }
    }

    /** 让助手绑定一个 MCP server；空 assistantId 默认绑定 default 助手。 */
    suspend fun bindServerToAssistant(serverId: String, assistantId: String = "default"): String {
        if (_servers.value.none { it.id == serverId }) return "找不到 MCP 服务器: $serverId"
        val repo = assistantRepository ?: return "助手仓库未就绪，无法绑定 MCP。"
        val assistant = repo.getById(assistantId) ?: return "找不到助手: $assistantId"
        val ids = repo.parseMcpServerIds(assistant).toMutableSet()
        val added = ids.add(serverId)
        if (added) {
            repo.upsert(
                assistant.copy(mcpServerIdsJson = io.zer0.common.AppJson.encodeToString(ListSerializer(String.serializer()), ids.toList())),
            )
        }
        return if (added) "已将 MCP $serverId 绑定到助手 ${assistant.name}。" else "MCP $serverId 已经绑定到助手 ${assistant.name}。"
    }

    suspend fun setAssistantBindings(serverId: String, assistantIds: Set<String>) = configLock.withLock {
        val repo = assistantRepository ?: return@withLock
        if (_servers.value.none { it.id == serverId }) return@withLock
        repo.getAll().forEach { assistant ->
            val before = repo.parseMcpServerIds(assistant).toSet()
            val after = if (assistant.id in assistantIds) before + serverId else before - serverId
            if (after != before) repo.updateMcpServerIds(assistant.id, repo.serializeStringList(after.toList()))
        }
    }

    suspend fun removeServer(id: String) = configLock.withLock {
        val current = settings.mcpServersFlow.first()
        val updated = current.filterNot { it.id == id }
        if (updated != current) persist(updated)
        reconcile(updated)
        settings.clearMcpToken(id)
        assistantRepository?.removeMcpServerBinding(id)
    }

    /** The settings collector owns start-up. An explicit refresh also serves consumers starting on the first turn. */
    fun startAll() {
        if (_servers.value.isNotEmpty()) return
        scope.launch { configLock.withLock { reconcile(settings.mcpServersFlow.first()) } }
    }

    fun reconnect(id: String) = synchronized(connectionLock) {
        val config = _servers.value.firstOrNull { it.id == id && it.isConnectable() } ?: return@synchronized
        disconnectServer(id)
        connectServer(config)
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

    /** Discover/register on demand and return a browser URL for this server. */
    suspend fun authorize(serverId: String): String? {
        var client = clients[serverId]
        var cfg = _servers.value.firstOrNull { it.id == serverId } ?: return null
        if (!cfg.oauthConfig.enabled) {
            val discovered = McpDynamicRegistration.discoverAndRegister(
                serverUrl = cfg.url,
                redirectUri = cfg.oauthConfig.redirectUri,
                protectedResourceMetadataUrl = client?.protectedResourceMetadataUrl,
                scopes = client?.authorizationScopeHint.orEmpty(),
                resource = cfg.url,
            ) ?: return null
            cfg = cfg.copy(oauthConfig = discovered)
            updateServer(cfg)
            client = clients[serverId]
        }
        return client?.startOAuthFlow()
    }

    /** Route a deep link to the pending MCP client whose PKCE state matches it. */
    suspend fun completeOAuthCallback(redirectUri: String): Boolean {
        val state = McpOAuthFlow.callbackState(redirectUri, "io.zer0.muse://oauth/callback") ?: return false
        val match = clients.entries.firstOrNull { (_, client) -> client.hasPendingOAuthState(state) } ?: return false
        val denied = runCatching {
            android.net.Uri.parse(redirectUri).getQueryParameter("error")
        }.getOrNull()
        if (!denied.isNullOrBlank()) {
            match.value.failOAuthCallback(state, "Authorization was denied or cancelled")
            return true
        }
        completeOAuthFlow(match.key, redirectUri)
        return true
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
            reconnect(serverId)
        }
        return ok
    }

    /** Mark a cancelled browser flow so the server remains available for retry. */
    fun cancelOAuthFlow(serverId: String) {
        val client = clients[serverId] ?: return
        client.cancelOAuthFlow()
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

    /** Authorization lifecycle for settings and recovery prompts. */
    fun oauthStatus(serverId: String): kotlinx.coroutines.flow.StateFlow<McpOAuthStatus>? = clients[serverId]?.oauthStatus

    /** Sanitized explanation for the latest authorization state. */
    fun oauthFailure(serverId: String): String? = clients[serverId]?.authFailure

    sealed interface QueryResult<out T> {
        data class Success<T>(val value: T) : QueryResult<T>
        enum class Failure : QueryResult<Nothing> { DISCONNECTED, UNSUPPORTED, REQUEST_FAILED }
    }

    suspend fun listResources(serverId: String): QueryResult<List<McpResource>> = query(serverId, "resources") { listResourcesOrNull() }

    suspend fun readResource(serverId: String, uri: String): QueryResult<List<McpResourceContent>> =
        query(serverId, "resources") { readResourceOrNull(uri) }

    suspend fun listPrompts(serverId: String): QueryResult<List<McpPrompt>> = query(serverId, "prompts") { listPromptsOrNull() }

    suspend fun getPrompt(serverId: String, name: String, arguments: Map<String, String>): QueryResult<McpPromptResult> =
        query(serverId, "prompts") { getPromptOrNull(name, arguments) }

    private suspend fun <T> query(serverId: String, capability: String, read: suspend McpClient.() -> T?): QueryResult<T> {
        val client = clients[serverId] ?: return QueryResult.Failure.DISCONNECTED
        if (client.state.value != McpConnectionState.CONNECTED) return QueryResult.Failure.DISCONNECTED
        if (client.serverCapabilities.value?.containsKey(capability) == false) return QueryResult.Failure.UNSUPPORTED
        return resultOf { client.read() }
            .onError { _, _ -> Logger.w(TAG, "[$serverId] MCP $capability request failed") }
            .getOrNull()?.let { QueryResult.Success(it) } ?: QueryResult.Failure.REQUEST_FAILED
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
            protectedResourceMetadataUrl = clients[serverId]?.protectedResourceMetadataUrl,
            scopes = clients[serverId]?.authorizationScopeHint.orEmpty(),
            resource = cfg.url,
        ) ?: return null
        // 持久化到 server 配置
        val updated = cfg.copy(oauthConfig = oauthConfig)
        updateServer(updated)
        Logger.i(TAG, "[$serverId] 动态注册成功")
        return oauthConfig
    }

    /**
     * 连接到指定 server,握手成功后注册 tools 到 [ToolRegistry]。
     */
    private fun connectServer(config: McpServerConfig) {
        if (config.id in clientConfigs) return
        val id = config.id
        val client = McpClient(config, settings = settings, appContext = context)
        clients[id] = client
        clientConfigs[id] = config.copy(lastConnectedAt = 0)
        setStatus(id, ServerStatus(connection = McpConnectionState.CONNECTING))
        client.onToolsListChanged = { scope.launch { refreshTools(id, client) } }
        clientJobs[id] = scope.launch {
            val issue = connectionIssue(config)
            if (issue != null) {
                synchronized(connectionLock) {
                    if (clients[id] === client) setStatus(id, ServerStatus(McpConnectionState.FAILED, issue = issue))
                }
                return@launch
            }
            client.start()
            client.state.dropWhile { it == McpConnectionState.DISCONNECTED }.collectLatest { state ->
                val active = synchronized(connectionLock) {
                    if (clients[id] !== client) return@synchronized false
                    if (state != McpConnectionState.CONNECTED) unregisterTools(id)
                    val issueForState = if (state == McpConnectionState.FAILED) {
                        when {
                            client.lastConnectTimedOut -> ConnectionIssue.TIMED_OUT
                            config.transportType == McpTransportType.STDIO -> ConnectionIssue.START_FAILED
                            else -> ConnectionIssue.HANDSHAKE_FAILED
                        }
                    } else {
                        null
                    }
                    setStatus(id, ServerStatus(state, issue = issueForState))
                    true
                }
                if (active && state == McpConnectionState.CONNECTED) refreshTools(id, client)
            }
        }
    }

    private fun connectionIssue(config: McpServerConfig): ConnectionIssue? {
        if (config.transportType == McpTransportType.STDIO) {
            return if (MuseRuntime.isReady(context)) null else ConnectionIssue.RUNTIME_REQUIRED
        }
        val address = parseRemoteAddress(config.url) ?: return ConnectionIssue.INVALID_ADDRESS
        val resolved = resolveRemoteAddress(config.id, address.host) ?: return ConnectionIssue.DnsFailed
        return addressIssue(config, resolved)
    }

    private fun parseRemoteAddress(url: String): java.net.URI? {
        val address = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val hasAllowedScheme = address.scheme?.lowercase() in setOf("http", "https")
        return address.takeIf { hasAllowedScheme && it.host != null && it.userInfo == null }
    }

    private fun resolveRemoteAddress(serverId: String, host: String): Array<java.net.InetAddress>? =
        try {
            java.net.InetAddress.getAllByName(host)
        } catch (error: java.net.UnknownHostException) {
            Logger.w(TAG, "[$serverId] MCP address lookup failed", error)
            null
        }

    private fun addressIssue(config: McpServerConfig, addresses: Array<java.net.InetAddress>): ConnectionIssue? =
        if (config.isSsrfBlocked(addresses)) ConnectionIssue.ADDRESS_BLOCKED else null

    private fun disconnectServer(id: String) {
        clientJobs.remove(id)?.cancel()
        clientConfigs.remove(id)
        clients.remove(id)?.close()
        unregisterTools(id)
        refreshingTools.remove(id)
        _serversState.update { it - id }
        _serverStatuses.update { it - id }
    }

    private fun setStatus(id: String, status: ServerStatus) {
        _serverStatuses.update { it + (id to status) }
        _serversState.update { it + (id to status.connection) }
    }

    private suspend fun registerTools(serverId: String, client: McpClient) {
        var tools: List<McpTool>? = null
        for (attempt in 0 until 3) {
            val toolsResult = resultOf { client.listToolsOrNull() }
            tools = toolsResult
                .onError { msg, _ -> Logger.w(TAG, "[$serverId] tools/list 失败(attempt=${attempt + 1}): $msg") }
                .getOrNull()
            if (tools != null) break
            if (attempt < 2) delay(if (attempt == 0) 250L else 1_000L)
        }
        synchronized(connectionLock) {
            if (clients[serverId] !== client || client.state.value != McpConnectionState.CONNECTED) return
            val previous = serverStatuses.value[serverId] ?: ServerStatus(McpConnectionState.CONNECTED)
            val resolvedTools = tools
            if (resolvedTools == null) {
                setStatus(serverId, previous.copy(issue = ConnectionIssue.TOOLS_FAILED))
                return
            }
            unregisterTools(serverId)
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
                        // 对端工具名是第三方自取的 — `get_diagnostics` 也可以是外发调用。
                        // 不拿名字推风险，统一走动态工具判据（保守 NORMAL，ASK 模式下弹审批）。
                        riskLevel = ToolPermissionResolver.riskLevelFor(registeredName),
                    ),
                ) { args ->
                    val timeoutMs = _servers.value.firstOrNull { it.id == serverId }
                        ?.requestTimeoutMs
                        ?.coerceIn(1_000L, 120_000L)
                        ?: 30_000L
                    try {
                        val result = withTimeoutOrNull(timeoutMs) {
                            client.callTool(tool.name, args)
                        }
                        if (result == null) {
                            // 超时不再静默：使用 server 配置的 requestTimeoutMs 回传失败原因
                            // (v2.2.1: 附带 server 名与超时秒数,模型可直接定位是哪个 server 慢)。
                            Logger.w(TAG, "[$serverId] 工具 ${tool.name} 调用超时(${timeoutMs}ms)")
                            "Error: " + context.getString(
                                R.string.mcp_tool_call_timeout,
                                "$serverId / ${tool.name} (${timeoutMs / 1000}s)",
                            )
                        } else if (result.isError) {
                            // v1.0.79 (A-1): 错误详情完整回传 — server 返回的 content/structuredContent
                            // 都带回,模型拿得到失败原因才能自我纠正。
                            val detail = McpToolResultPresentation.formatToolResult(result)
                            Logger.w(TAG, "[$serverId] MCP tool ${tool.name} returned an error")
                            "Error: " + context.getString(
                                R.string.mcp_tool_call_failed,
                                "$serverId / ${tool.name}",
                            ) + ": " + detail
                        } else {
                            McpToolResultPresentation.formatToolResult(result)
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // v2.2.1: 调用抛异常(连接断开/协议错误等)不再落到通用兜底,带 server/工具名回传。
                        Logger.w(TAG, "[$serverId] MCP tool ${tool.name} failed (${e.javaClass.simpleName})")
                        "Error: [MCP $serverId] 工具 '${tool.name}' 调用异常(${e.javaClass.simpleName})"
                    }
                }
            }
            val connectedAt = System.currentTimeMillis()
            setStatus(serverId, ServerStatus(McpConnectionState.CONNECTED, resolvedTools, lastConnectedAt = connectedAt))
            if (previous.lastConnectedAt == 0L) rememberLastConnection(serverId, client, connectedAt)
        }
    }

    private fun rememberLastConnection(serverId: String, client: McpClient, connectedAt: Long) {
        scope.launch {
            configLock.withLock {
                if (clients[serverId] !== client) return@withLock
                val current = settings.mcpServersFlow.first()
                persist(current.map { if (it.id == serverId) it.copy(lastConnectedAt = connectedAt) else it })
            }
        }
    }

    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}__"
        toolRegistry.listTools()
            .filter { it.name.startsWith(prefix) }
            .forEach { toolRegistry.unregister(it.name) }
    }

    private suspend fun refreshTools(serverId: String, client: McpClient) {
        if (clients[serverId] !== client || refreshingTools.putIfAbsent(serverId, client) != null) return
        try {
            if (client.state.value == McpConnectionState.CONNECTED) registerTools(serverId, client)
        } finally {
            refreshingTools.remove(serverId, client)
        }
    }

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
    suspend fun awaitToolsForServers(serverIds: Set<String>, timeoutMs: Long? = null): Boolean =
        unavailableForTurn(serverIds, timeoutMs).isEmpty()

    suspend fun unavailableForTurn(serverIds: Set<String>, timeoutMs: Long? = null): List<McpServerConfig> {
        if (serverIds.isEmpty()) return emptyList()
        val targets = configLock.withLock {
            val saved = settings.mcpServersFlow.first()
            reconcile(saved)
            saved.filter { it.id in serverIds && it.isConnectable() }
        }
        if (targets.isEmpty()) return emptyList()
        val limit = timeoutMs ?: targets.maxOf { it.toolsReadyTimeoutMs }
        withTimeoutOrNull(limit) {
            serverStatuses.first { states ->
                targets.all {
                    val status = states[it.id]
                    status?.tools != null || status?.issue != null ||
                        status?.connection == McpConnectionState.NEEDS_AUTH
                }
            }
        }
        return targets.filter { serverStatuses.value[it.id]?.tools == null }
    }

    private fun parseInputSchemaTypes(schema: JsonElement?): Map<String, String> {
        val obj = schema as? JsonObject ?: return emptyMap()
        val props = obj["properties"] as? JsonObject ?: return emptyMap()
        return props.mapValues { (_, definition) ->
            ((definition as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: "string"
        }
    }

    /** 把 ToolRegistry 的字符串参数恢复为 MCP inputSchema 声明的 JSON 值。 */
    private fun registerManagementTools() {
        fun def(
            name: String,
            description: String,
            parameters: Map<String, String>,
            required: Set<String> = emptySet(),
            risk: io.zer0.muse.tools.ToolRiskLevel = io.zer0.muse.tools.ToolRiskLevel.NORMAL,
        ) = io.zer0.muse.tools.ToolRegistry.ToolDef(name, description, parameters, required, "mcp", riskLevel = risk)
        toolRegistry.register(
            def("mcp_mgmt_list", "列出当前已配置的 MCP 服务器及连接状态。", emptyMap(), risk = io.zer0.muse.tools.ToolRiskLevel.SAFE),
        ) { _ ->
            val current = _servers.value
            if (current.isEmpty()) {
                "当前没有配置 MCP 服务器。"
            } else {
                current.joinToString("\\n") { cfg ->
                    val state = _serversState.value[cfg.id] ?: McpConnectionState.DISCONNECTED
                    val enabledText = if (cfg.enabled) "enabled" else "disabled"
                    "${cfg.id} | ${cfg.name} | ${cfg.transportType.name} | $enabledText | $state"
                }
            }
        }
        toolRegistry.register(
            def(
                "mcp_mgmt_configure",
                "创建或更新 MCP 服务器配置（远程 SSE / Streamable HTTP，或本地 STDIO），并自动连接、握手、发现工具。" +
                    "STDIO 模式在已安装的 Node 运行时中执行 command（支持 node / npm / npx 命令），例如 " +
                    "npx -y @modelcontextprotocol/server-filesystem .。",
                mapOf(
                    "id" to "稳定唯一 ID，可省略",
                    "name" to "服务器显示名",
                    "transport" to "SSE / STREAMABLE_HTTP / STDIO，默认 STREAMABLE_HTTP",
                    "url" to "网络传输的 endpoint（http/https）；STDIO 模式不需要",
                    "command" to "STDIO 模式的启动命令（node / npm / npx），例如 npx -y @modelcontextprotocol/server-memory",
                    "auth_token" to "可选 Bearer token，会加密保存",
                    "headers_json" to "可选 JSON 对象，例如 {\"X-API-Key\":\"...\"}",
                    "enabled" to "可选 true/false，默认 true",
                ),
                setOf("name"),
                io.zer0.muse.tools.ToolRiskLevel.HIGH,
            ),
        ) { args -> configureFromTool(args) }
        toolRegistry.register(
            def(
                "mcp_mgmt_remove",
                "删除已配置的 MCP 服务器并注销其工具。",
                mapOf("id" to "MCP server id"),
                setOf("id"),
                io.zer0.muse.tools.ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["id"].orEmpty().trim()
            if (id.isBlank()) {
                "缺少 MCP server id。"
            } else if (_servers.value.none { it.id == id }) {
                "找不到 MCP 服务器: $id"
            } else {
                removeServer(id)
                "已删除 MCP 服务器 $id。"
            }
        }
        toolRegistry.register(
            def(
                "mcp_mgmt_bind_assistant",
                "把已配置的 MCP 服务器绑定到指定助手，使其工具进入该助手对话能力。",
                mapOf("server_id" to "MCP server id", "assistant_id" to "助手 id，省略时为 default"),
                setOf("server_id"),
            ),
        ) { args ->
            bindServerToAssistant(args["server_id"].orEmpty().trim(), args["assistant_id"].orEmpty().trim().ifBlank { "default" })
        }
        toolRegistry.register(def("mcp_mgmt_reconnect", "重新连接已配置的 MCP 服务器并刷新工具列表。", mapOf("id" to "MCP server id"), setOf("id"))) { args ->
            val id = args["id"].orEmpty().trim()
            if (_servers.value.none { it.id == id }) {
                "找不到 MCP 服务器: $id"
            } else {
                reconnect(id)
                "已开始重连 MCP 服务器 $id。"
            }
        }
    }

    private suspend fun configureFromTool(args: Map<String, String>): String {
        val name = args["name"].orEmpty().trim()
        val url = args["url"].orEmpty().trim()
        val command = args["command"].orEmpty().trim()
        val transport = when (args["transport"].orEmpty().trim().uppercase()) {
            "SSE" -> McpTransportType.SSE
            "STDIO" -> McpTransportType.STDIO
            "", "STREAMABLE_HTTP", "STREAMABLEHTTP", "HTTP" -> McpTransportType.STREAMABLE_HTTP
            else -> return "transport 只能是 SSE / STREAMABLE_HTTP / STDIO。"
        }
        // P1-A: stdio 用 command 校验;网络传输走 url 校验
        if (transport == McpTransportType.STDIO) {
            if (name.isBlank() || command.isBlank()) return "stdio 模式下 name 和 command 都不能为空。"
        } else {
            if (name.isBlank() || url.isBlank()) return "name 和 url 都不能为空。"
            val parsedUrl = runCatching { java.net.URI(url) }.getOrNull()
            val badUrl = parsedUrl == null ||
                parsedUrl.scheme !in setOf("http", "https") ||
                parsedUrl.host.isNullOrBlank()
            if (badUrl) return "MCP url 必须是合法的 http/https 地址。"
        }
        val id = args["id"].orEmpty().trim().ifBlank {
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                .ifBlank { "mcp_${System.currentTimeMillis()}" }
        }
        // v1.0.92 (B-6): 显式传入的 id 同样受形态约束,防止特殊字符破坏工具名前缀。
        if (!SERVER_ID_REGEX.matches(id)) {
            return "id 只能含字母、数字、下划线和连字符(最长 64 字符)。"
        }
        val headers = args["headers_json"].orEmpty().trim().let { raw ->
            if (raw.isBlank()) {
                emptyMap()
            } else {
                runCatching {
                    val parsedRaw = io.zer0.common.AppJson.parseToJsonElement(raw)
                    val obj = parsedRaw as? kotlinx.serialization.json.JsonObject
                        ?: return "headers_json 必须是 JSON 对象。"
                    obj.mapValues { (_, value) -> value.toString().trim('"') }
                }.getOrElse { return "headers_json 不是合法 JSON：${it.message ?: "格式错误"}" }
            }
        }
        val enabled = args["enabled"].orEmpty().trim().lowercase().let { it != "false" && it != "0" }
        return upsertServer(
            McpServerConfig(
                id = id,
                name = name,
                transportType = transport,
                url = url,
                command = command,
                headers = headers,
                authToken = args["auth_token"].orEmpty().trim(),
                enabled = enabled,
            ),
        )
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
        synchronized(connectionLock) { clients.keys.toList().forEach(::disconnectServer) }
    }

    private companion object {
        const val TAG = "McpRegistry"
    }
}
