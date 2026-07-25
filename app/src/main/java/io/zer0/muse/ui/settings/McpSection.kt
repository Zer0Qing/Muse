package io.zer0.muse.ui.settings

import android.content.Intent
import android.net.Uri
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.mcp.McpConnectionState
import io.zer0.muse.mcp.McpPrompt
import io.zer0.muse.mcp.McpPromptResult
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.mcp.McpResource
import io.zer0.muse.mcp.McpResourceContent
import io.zer0.muse.mcp.McpServerConfig
import io.zer0.muse.mcp.McpTransportType
import io.zer0.muse.R
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.compose.koinInject

/**
 * 阶段 7: MCP server 管理 section — iOS 风格分组列表。
 *
 * 每个 server 渲染为 [SettingsGroup] 内的一行,显示名称/URL/状态,
 * 右侧 trailing 放重连 + 删除按钮,行间细分割线。
 * 添加按钮放在分组外(对标 iOS 列表底部的"添加"操作)。
 */
@Composable
internal fun McpSection() {
    val mcpRegistry: McpRegistry = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by mcpRegistry.servers.collectAsStateWithLifecycle(initialValue = null)
    val serversState by mcpRegistry.serversState.collectAsStateWithLifecycle(initialValue = null)

    var showAddDialog by remember { mutableStateOf(false) }

    SectionLabel(stringResource(R.string.settings_mcp_title))
    Text(
        text = stringResource(R.string.settings_mcp_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // v1.74: 局部变量捕获避免 !! 非空断言(委托属性无法 smart-cast)
        val serverList = servers
        when {
            serverList == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            serverList.isEmpty() -> {
                // v1.48: h14 MCP server 空态改用 EmptyState 组件
                EmptyState(
                    icon = Icons.Outlined.Extension,
                    title = stringResource(R.string.settings_mcp_empty_title),
                    subtitle = stringResource(R.string.settings_mcp_empty_subtitle),
                )
            }
            else -> {
                serverList.forEachIndexed { index, server ->
                    if (index > 0) SettingsGroupDivider()
                    val state = serversState?.get(server.id) ?: McpConnectionState.DISCONNECTED
                    McpServerRow(
                        server = server,
                        state = state,
                        mcpRegistry = mcpRegistry,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                runCatching { mcpRegistry.updateServer(server.copy(enabled = enabled)) }
                                    .onFailure {
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, it.message))
                                    }
                            }
                        },
                        onReconnect = {
                            runCatching { mcpRegistry.reconnect(server.id) }
                                .onFailure {
                                    MuseToast.show(context.getString(R.string.settings_mcp_failed, it.message))
                                }
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { mcpRegistry.removeServer(server.id) }
                                    .onFailure {
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, it.message))
                                    }
                            }
                        },
                    )
                }
            }
        }
    }

    // 添加按钮(分组外)
    TextButton(
        onClick = { showAddDialog = true },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.settings_mcp_add), style = MaterialTheme.typography.bodyMedium)
    }

    if (showAddDialog) {
        McpServerAddDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config -> mcpRegistry.addServer(config) },
        )
    }
}

/**
 * 单个 MCP server 行(状态点 + 名称/URL/状态 + 启停开关 + 更多菜单 + 重连/删除按钮)。
 *
 * 抽出为独立 Composable 是为了让"更多"下拉菜单与 Resources/Prompts 浏览 dialog 的
 * 状态能够按行独立持有(避免在 forEach 中共享 remember 状态)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerRow(
    server: McpServerConfig,
    state: McpConnectionState,
    mcpRegistry: McpRegistry,
    onToggleEnabled: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showResources by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    // OAuth 支持:启用 OAuth 配置或已注册 clientId
    val oauthSupported = server.oauthConfig.let { it.enabled || it.clientId.isNotBlank() }
    // 状态描述字符串(需在 @Composable 上下文中提取,不能在 semantics{} lambda 内调用 stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 状态指示小圆点(高级感)
        io.zer0.muse.ui.common.StatusDot(
            color = when (state) {
                McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                McpConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                McpConnectionState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
            // L-SC5: StatusDot.size 改 Dp 类型,调用方 8 → 8.dp。
            size = 8.dp,
            pulse = state == McpConnectionState.CONNECTING,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name.ifBlank { server.id },
                style = MaterialTheme.typography.bodyLarge,
                color = if (server.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
            )
            Text(
                text = "${server.transportType.name} · ${server.url.take(50)}${if (server.url.length > 50) "..." else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = state.name,
                style = MaterialTheme.typography.labelSmall,
                color = when (state) {
                    McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                    McpConnectionState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
        // 阶段 D: 启停开关(独立于连接,允许保留配置但不连接)
        IosSwitch(
            checked = server.enabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.semantics {
                stateDescription = if (server.enabled) stateEnabledText else stateDisabledText
            },
        )
        // v1.134 P0-6: 更多操作菜单按钮用 IosTactileButton(48dp 触摸目标 + 无 ripple)
        IosTactileButton(
            icon = Icons.Default.MoreVert,
            onClick = { menuExpanded = true },
            contentDescription = stringResource(R.string.settings_mcp_more),
        )
        // v1.134 P0-7: DropdownMenu → MuseDialog 操作列表(iOS 风格)
        if (menuExpanded) {
            MuseDialog(
                onDismissRequest = { menuExpanded = false },
                title = server.name,
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (oauthSupported) {
                            McpActionRow(stringResource(R.string.settings_mcp_oauth_authorize)) {
                                menuExpanded = false
                                runCatching { mcpRegistry.startOAuthFlow(server.id) }
                                    .onSuccess { url ->
                                        if (url == null) {
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_cannot_start))
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.onFailure {
                                                MuseToast.show(context.getString(R.string.settings_mcp_oauth_open_failed, it.message))
                                            }
                                        }
                                    }
                                    .onFailure {
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, it.message))
                                    }
                            }
                            McpActionRow(stringResource(R.string.settings_mcp_oauth_revoke)) {
                                menuExpanded = false
                                scope.launch {
                                    runCatching { mcpRegistry.revokeOAuth(server.id) }
                                        .onSuccess {
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_revoked))
                                        }
                                        .onFailure {
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_revoke_failed, it.message))
                                        }
                                }
                            }
                        }
                        McpActionRow(stringResource(R.string.settings_mcp_browse_resources)) {
                            menuExpanded = false
                            showResources = true
                        }
                        McpActionRow(stringResource(R.string.settings_mcp_browse_prompts)) {
                            menuExpanded = false
                            showPrompts = true
                        }
                    }
                },
                confirmText = stringResource(R.string.common_close),
                onConfirm = { menuExpanded = false },
                onDismiss = { menuExpanded = false },
            )
        }
        IosTactileButton(
            icon = Icons.Default.Edit,
            onClick = onReconnect,
            contentDescription = stringResource(R.string.settings_mcp_reconnect),
        )
        IosTactileButton(
            icon = Icons.Default.Delete,
            onClick = onDelete,
            contentDescription = stringResource(R.string.settings_common_delete),
            tint = MaterialTheme.colorScheme.error,
        )
    }

    if (showResources) {
        ResourcesBrowserDialog(
            serverId = server.id,
            mcpRegistry = mcpRegistry,
            onDismiss = { showResources = false },
        )
    }
    if (showPrompts) {
        PromptsBrowserDialog(
            serverId = server.id,
            mcpRegistry = mcpRegistry,
            onDismiss = { showPrompts = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerAddDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (McpServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transportType by remember {
        mutableStateOf(McpTransportType.STREAMABLE_HTTP)
    }
    var authToken by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 高级字段(参考 ProviderSection 的 showAdvanced 折叠模式)
    var showAdvanced by remember { mutableStateOf(false) }
    var headersText by remember { mutableStateOf("") }
    var autoReconnect by remember { mutableStateOf(true) }
    var maxReconnectAttempts by remember { mutableStateOf("5") }
    var reconnectBaseMs by remember { mutableStateOf("3000") }
    var requestTimeoutMs by remember { mutableStateOf("30000") }
    // 状态描述字符串(需在 @Composable 上下文中提取,不能在 semantics{} lambda 内调用 stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_add_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: 改 ?.let 避免 !!(委托属性无法 smart-cast)
                addError?.let { err ->
                    InlineError(message = err, onDismiss = { addError = null })
                }
                SettingField(
                    label = stringResource(R.string.settings_mcp_display_name),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.settings_mcp_display_name_placeholder),
                )
                SettingField(
                    label = "URL",
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://server.example.com/mcp",
                )
                Text(stringResource(R.string.settings_mcp_transport_type), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { transportType = McpTransportType.SSE },
                        shape = MuseShapes.small,
                    ) {
                        Text(
                            text = "SSE",
                            color = if (transportType == McpTransportType.SSE)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { transportType = McpTransportType.STREAMABLE_HTTP },
                        shape = MuseShapes.small,
                    ) {
                        Text(
                            text = "StreamableHTTP",
                            color = if (transportType == McpTransportType.STREAMABLE_HTTP)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Phase 11.1.1: stdio 传输在 Android 沙箱不可用(SELinux 限制 exec),
                // 明确提示用户仅支持 SSE / StreamableHTTP 两种网络传输
                Text(
                    text = stringResource(R.string.settings_mcp_stdio_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingField(
                    label = stringResource(R.string.settings_mcp_bearer_token),
                    value = authToken,
                    onValueChange = { authToken = it },
                    visualTransformation = PasswordVisualTransformation(),
                )

                // 高级折叠区(headers / 重连参数 / 超时)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_common_advanced),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAdvanced) stringResource(R.string.settings_common_collapse) else stringResource(R.string.settings_common_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_mcp_custom_headers)) },
                        placeholder = { Text("Authorization: Bearer xxx\nX-API-Key: yyy") },
                        singleLine = false,
                        shape = MuseShapes.medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IosSwitch(
                            checked = autoReconnect,
                            onCheckedChange = { autoReconnect = it },
                            modifier = Modifier.semantics {
                                stateDescription = if (autoReconnect) stateEnabledText else stateDisabledText
                            },
                        )
                        Text(
                            text = stringResource(R.string.settings_mcp_auto_reconnect),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    SettingField(
                        label = stringResource(R.string.settings_mcp_max_reconnect),
                        value = maxReconnectAttempts,
                        onValueChange = { maxReconnectAttempts = it.filter { c -> c.isDigit() } },
                        placeholder = "5",
                    )
                    SettingField(
                        label = stringResource(R.string.settings_mcp_reconnect_base),
                        value = reconnectBaseMs,
                        onValueChange = { reconnectBaseMs = it.filter { c -> c.isDigit() } },
                        placeholder = "3000",
                    )
                    SettingField(
                        label = stringResource(R.string.settings_mcp_request_timeout),
                        value = requestTimeoutMs,
                        onValueChange = { requestTimeoutMs = it.filter { c -> c.isDigit() } },
                        placeholder = "30000",
                    )
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_add),
        onConfirm = {
            if (url.isNotBlank()) {
                val config = McpServerConfig(
                    id = "mcp-" + System.currentTimeMillis(),
                    name = name.ifBlank { "MCP Server" },
                    transportType = transportType,
                    url = url.trim(),
                    authToken = authToken.trim(),
                    headers = parseHeaders(headersText),
                    autoReconnect = autoReconnect,
                    maxReconnectAttempts = maxReconnectAttempts.toIntOrNull()?.takeIf { it > 0 }
                        ?: 5,
                    reconnectBaseMs = reconnectBaseMs.toLongOrNull()?.takeIf { it > 0 }
                        ?: 3000L,
                    requestTimeoutMs = requestTimeoutMs.toLongOrNull()?.takeIf { it > 0 }
                        ?: 30_000L,
                )
                scope.launch {
                    runCatching { onAdd(config) }
                        .onSuccess { onDismiss() }
                        .onFailure { addError = context.getString(R.string.settings_mcp_failed, it.message) }
                }
            }
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 浏览指定 server 的 Resources(Phase 11.1.2 listAllResources / readResource 接入)。
 *
 * - 列表态:Column + forEach 展示 uri / name / description
 * - 详情态:点击某项后读取内容,叠加一个 MuseDialog 展示文本/blob
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourcesBrowserDialog(
    serverId: String,
    mcpRegistry: McpRegistry,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var resources by remember { mutableStateOf<List<McpResource>>(emptyList()) }
    var viewingContent by remember { mutableStateOf<List<McpResourceContent>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        loading = true
        loadError = null
        runCatching {
            val all = mcpRegistry.listAllResources()
            all[serverId] ?: emptyList()
        }.onSuccess { resources = it }
            .onFailure {
                loadError = context.getString(R.string.settings_mcp_load_resources_failed, it.message)
            }
        loading = false
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_browse_resources_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: 改 ?.let 避免 !!(委托属性无法 smart-cast)
                loadError?.let { err ->
                    InlineError(message = err, onDismiss = { loadError = null })
                }
                readError?.let { err ->
                    InlineError(message = err, onDismiss = { readError = null })
                }
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.settings_mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (resources.isEmpty()) {
                    // v1.48: h14 Resources 空态改为带图标的居中 Row(空间小,不用 EmptyState 大组件)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.settings_mcp_no_resources),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // v1.0.7 修复:MuseDialog content 已自带 verticalScroll,不能嵌套 LazyColumn。
                    // 改用 Column + forEach,由 MuseDialog 外层统一滚动(对齐 VoiceCloningPage v1.132)。
                    Column {
                        resources.forEach { res ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            readError = null
                                            val contents = runCatching {
                                                mcpRegistry.readResource(serverId, res.uri)
                                            }.onFailure {
                                                readError = context.getString(R.string.settings_mcp_read_failed, it.message)
                                            }.getOrDefault(emptyList())
                                            viewingContent = contents
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = res.name ?: res.uri,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!res.description.isNullOrBlank()) {
                                    Text(
                                        text = res.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Text(
                                    text = res.uri,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
            }
        }
    },
    confirmText = stringResource(R.string.settings_common_close),
    onConfirm = onDismiss,
)

    // 资源内容详情(叠加在列表 dialog 之上)
    viewingContent?.let { contents ->
        MuseDialog(
            onDismissRequest = { viewingContent = null },
            title = stringResource(R.string.settings_mcp_resource_content),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (contents.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_empty_content), style = MaterialTheme.typography.bodySmall)
                    } else {
                        contents.forEach { c ->
                            Text(
                                text = c.text ?: c.blob ?: stringResource(R.string.settings_mcp_no_text),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_common_close),
            onConfirm = { viewingContent = null },
        )
    }
}

/**
 * 浏览指定 server 的 Prompts(Phase 11.1.2 listAllPrompts / getPrompt 接入)。
 *
 * 三态:
 *  - 列表:展示 name / description / 参数名
 *  - 参数输入:选中某个 prompt 后,动态渲染其 arguments 的输入框
 *  - 结果展示:getPrompt 返回的 messages(role + content)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptsBrowserDialog(
    serverId: String,
    mcpRegistry: McpRegistry,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var prompts by remember { mutableStateOf<List<McpPrompt>>(emptyList()) }
    var selectedPrompt by remember { mutableStateOf<McpPrompt?>(null) }
    var promptResult by remember { mutableStateOf<McpPromptResult?>(null) }
    var loadingResult by remember { mutableStateOf(false) }
    val argValues = remember(selectedPrompt) { mutableStateMapOf<String, String>() }
    var loadError by remember { mutableStateOf<String?>(null) }
    var promptError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        loading = true
        loadError = null
        runCatching {
            val all = mcpRegistry.listAllPrompts()
            all[serverId] ?: emptyList()
        }.onSuccess { prompts = it }
            .onFailure {
                loadError = context.getString(R.string.settings_mcp_load_prompts_failed, it.message)
            }
        loading = false
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_browse_prompts_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: 改 ?.let 避免 !!(委托属性无法 smart-cast)
                loadError?.let { err ->
                    InlineError(message = err, onDismiss = { loadError = null })
                }
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.settings_mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (prompts.isEmpty()) {
                    // v1.48: h14 Prompts 空态改为带图标的居中 Row(空间小,不用 EmptyState 大组件)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.settings_mcp_no_prompts),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // v1.0.7 修复:MuseDialog content 已自带 verticalScroll,不能嵌套 LazyColumn。
                    // 改用 Column + forEach,由 MuseDialog 外层统一滚动。
                    Column {
                        prompts.forEach { p ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        argValues.clear()
                                        selectedPrompt = p
                                        promptResult = null
                                        promptError = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!p.description.isNullOrBlank()) {
                                    Text(
                                        text = p.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                if (p.arguments.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_mcp_prompt_args, p.arguments.joinToString { it.name }),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_close),
        onConfirm = onDismiss,
    )

    // 参数输入 dialog(选中 prompt 后弹出)
    selectedPrompt?.let { prompt ->
        MuseDialog(
            onDismissRequest = { selectedPrompt = null },
            title = stringResource(R.string.settings_mcp_prompt_title, prompt.name),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // v1.74: 改 ?.let 避免 !!(委托属性无法 smart-cast)
                    promptError?.let { err ->
                        InlineError(message = err, onDismiss = { promptError = null })
                    }
                    if (loadingResult) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.settings_mcp_getting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (prompt.arguments.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_prompt_no_args), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            prompt.arguments.forEach { arg ->
                                OutlinedTextField(
                                    value = argValues[arg.name] ?: "",
                                    onValueChange = { argValues[arg.name] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            buildString {
                                                append(arg.name)
                                                if (arg.required) append(" *")
                                            },
                                        )
                                    },
                                    placeholder = arg.description?.let { { Text(it) } },
                                    singleLine = true,
                                    shape = MuseShapes.medium,
                                )
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_mcp_get),
            onConfirm = {
                if (!loadingResult) {
                    loadingResult = true
                    promptError = null
                    scope.launch {
                        val result = runCatching {
                            mcpRegistry.getPrompt(serverId, prompt.name, argValues.toMap())
                        }.onFailure {
                            promptError = context.getString(R.string.settings_mcp_get_failed, it.message)
                        }.getOrNull()
                        loadingResult = false
                        if (result != null) {
                            promptResult = result
                            selectedPrompt = null
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { selectedPrompt = null },
        )
    }

    // 结果展示 dialog
    promptResult?.let { result ->
        MuseDialog(
            onDismissRequest = { promptResult = null },
            title = stringResource(R.string.settings_mcp_prompt_result),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!result.description.isNullOrBlank()) {
                        Text(
                            text = result.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (result.messages.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_no_messages), style = MaterialTheme.typography.bodySmall)
                    } else {
                        result.messages.forEach { msg ->
                            Text(
                                text = "[${msg.role}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = msg.content.toDisplayText(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_common_close),
            onConfirm = { promptResult = null },
        )
    }
}

/**
 * Dialog 内容区错误条 — 替代 MuseToast,避免 Toast 被 Dialog 窗口遮挡。
 */
@Composable
private fun InlineError(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.settings_common_close),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onDismiss() },
            )
        }
    }
}

/**
 * 把"每行 key:value"的文本解析成 headers Map。
 * 空行 / 无冒号的行被忽略,空文本返回空 map。
 */
private fun parseHeaders(text: String): Map<String, String> {
    return text.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val idx = trimmed.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
        }
        .toMap()
}

/**
 * 把 MCP prompt message 的 content(JsonElement)转成可显示文本。
 * 形如 {type:"text", text:"..."} 提取 text;否则 toString()。
 */
private fun JsonElement.toDisplayText(): String {
    val obj = this as? JsonObject ?: return toString()
    val type = (obj["type"] as? JsonPrimitive)?.content
    val text = (obj["text"] as? JsonPrimitive)?.content
    return if (type == "text" && text != null) text else toString()
}

/**
 * v1.134 P0-7: MCP 操作菜单行 — 用于 MuseDialog 内部的可点击行。
 *
 * 替代 Material3 DropdownMenuItem,视觉风格与 IosDropdown 选项行一致:
 * 全宽 + 左对齐文本 + 适度 padding,点击触发回调。
 */
@Composable
private fun McpActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClick() }
            .padding(vertical = MusePaddings.inputPadding, horizontal = MusePaddings.iconPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
