package io.zer0.muse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.mcp.MCP_HEADER_NAME
import io.zer0.muse.mcp.McpConfigImport
import io.zer0.muse.mcp.McpConnectionState
import io.zer0.muse.mcp.McpFeishuAuthConfig
import io.zer0.muse.mcp.McpOAuthStatus
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.mcp.McpServerConfig
import io.zer0.muse.mcp.McpServerTemplate
import io.zer0.muse.mcp.McpServerTemplates
import io.zer0.muse.mcp.McpTransportType
import io.zer0.muse.mcp.isMcpEnvironmentName
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 阶段 7: MCP server 管理 section — 分组列表。
 *
 * 每个 server 渲染为 [SettingsGroup] 内的一行,显示名称/URL/状态,
 * 右侧 trailing 放重连 + 删除按钮,行间细分割线。
 * 添加按钮放在分组外(对齐系统列表底部的"添加"操作)。
 */
@Composable
internal fun McpSection(
    openAddTrigger: Int = 0,
    focusServerId: String? = null,
    onOpenRuntime: () -> Unit,
    onOpenChatDraft: (String, String) -> Unit,
) {
    val mcpRegistry: McpRegistry = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by mcpRegistry.servers.collectAsStateWithLifecycle()
    val statuses by mcpRegistry.serverStatuses.collectAsStateWithLifecycle()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var editServerId by remember { mutableStateOf<String?>(null) }
    var detailsServerId by remember(focusServerId) { mutableStateOf(focusServerId) }

    LaunchedEffect(openAddTrigger) {
        if (openAddTrigger > 0) showAddDialog = true
    }

    var riskNoteExpanded by remember { mutableStateOf(false) }
    CardGroup {
        content(key = "mcp_desc") {
            Text(
                text = stringResource(R.string.mcp_add_lede),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        content(key = "mcp_risk_note") {
            McpRiskNote(expanded = riskNoteExpanded, onToggle = { riskNoteExpanded = !riskNoteExpanded })
        }
    }

    CardGroup(modifier = Modifier.padding(top = MusePaddings.cardGap)) {
        McpServerList(
            state = McpServerListState(
                servers = servers,
                statuses = statuses,
                assistants = assistants,
                assistantRepository = assistantRepository,
                registry = mcpRegistry,
                scope = scope,
                context = context,
            ),
            onOpenDetails = { detailsServerId = it },
            onEdit = { editServerId = it },
            onOpenRuntime = onOpenRuntime,
            onOpenChatDraft = onOpenChatDraft,
            onShowAdd = { showAddDialog = true },
            onShowImport = { showImportDialog = true },
        )
    }
    val editing = servers.firstOrNull { it.id == editServerId }
    val details = servers.firstOrNull { it.id == detailsServerId }
    McpSectionDialogs(
        data = McpSectionDialogData(
            registry = mcpRegistry,
            statuses = statuses,
            assistants = assistants,
            assistantRepository = assistantRepository,
            scope = scope,
            context = context,
        ),
        visibility = McpSectionDialogVisibility(
            showImport = showImportDialog,
            showAdd = showAddDialog,
            editing = editing,
            details = details,
        ),
        actions = McpSectionDialogActions(
            onOpenRuntime = onOpenRuntime,
            onOpenChatDraft = onOpenChatDraft,
            onDismissImport = { showImportDialog = false },
            onImportSaved = {
                showImportDialog = false
                detailsServerId = it
            },
            onDismissAdd = {
                showAddDialog = false
                editServerId = null
            },
            onSave = { config ->
                val saved = if (editing == null) {
                    mcpRegistry.addServer(config)
                } else {
                    mcpRegistry.updateServer(config)
                    true
                }
                if (saved) {
                    showAddDialog = false
                    editServerId = null
                    detailsServerId = config.id
                }
                saved
            },
            onDismissDetails = { detailsServerId = null },
            onOpenRuntimeFromDetails = {
                detailsServerId = null
                onOpenRuntime()
            },
            onEditDetails = { editServerId = details?.id },
        ),
    )
}

private data class McpSectionDialogData(
    val registry: McpRegistry,
    val statuses: Map<String, McpRegistry.ServerStatus>,
    val assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    val assistantRepository: AssistantRepository,
    val scope: kotlinx.coroutines.CoroutineScope,
    val context: android.content.Context,
)

private data class McpSectionDialogVisibility(
    val showImport: Boolean,
    val showAdd: Boolean,
    val editing: McpServerConfig?,
    val details: McpServerConfig?,
)

private data class McpSectionDialogActions(
    val onOpenRuntime: () -> Unit,
    val onOpenChatDraft: (String, String) -> Unit,
    val onDismissImport: () -> Unit,
    val onImportSaved: (String?) -> Unit,
    val onDismissAdd: () -> Unit,
    val onSave: suspend (McpServerConfig) -> Boolean,
    val onDismissDetails: () -> Unit,
    val onOpenRuntimeFromDetails: () -> Unit,
    val onEditDetails: () -> Unit,
)

@Composable
private fun McpSectionDialogs(
    data: McpSectionDialogData,
    visibility: McpSectionDialogVisibility,
    actions: McpSectionDialogActions,
) {
    if (visibility.showImport) {
        McpImportDialog(
            onDismiss = actions.onDismissImport,
            onImport = { configs ->
                val saved = data.registry.addServers(configs)
                if (saved) actions.onImportSaved(configs.firstOrNull()?.id)
                saved
            },
        )
    }
    if (visibility.showAdd || visibility.editing != null) {
        McpServerAddDialog(
            existing = visibility.editing,
            onOpenRuntime = actions.onOpenRuntime,
            onDismiss = actions.onDismissAdd,
            onSave = actions.onSave,
        )
    }
    val details = visibility.details
    if (details != null && visibility.editing == null && !visibility.showAdd) {
        McpServerDetailsDialog(
            state = McpServerDetailsState(
                details = details,
                status = data.statuses[details.id] ?: McpRegistry.ServerStatus(),
                assistants = data.assistants,
                assistantRepository = data.assistantRepository,
                registry = data.registry,
                scope = data.scope,
                context = data.context,
            ),
            actions = McpServerDetailsActions(
                onDismiss = actions.onDismissDetails,
                onOpenRuntime = actions.onOpenRuntimeFromDetails,
                onEdit = actions.onEditDetails,
                onOpenChatDraft = actions.onOpenChatDraft,
            ),
        )
    }
}

/**
 * 单个 MCP server 行(状态点 + 名称/URL/状态 + 启停开关 + 更多菜单 + 重连/删除按钮)。
 *
 * 抽出为独立 Composable 是为了让"更多"下拉菜单与 Resources/Prompts 浏览 dialog 的
 * 状态能够按行独立持有(避免在 forEach 中共享 remember 状态)。
 */
private data class McpServerRowSpec(
    val server: McpServerConfig,
    val status: McpRegistry.ServerStatus,
    val registry: McpRegistry,
    val boundNames: List<String>,
    val actions: McpServerRowActions,
    val onInstallRuntime: () -> Unit,
    val onDraft: ((String) -> Unit)?,
    val onDelete: () -> Unit,
)

private data class McpServerDetailsState(
    val details: McpServerConfig,
    val status: McpRegistry.ServerStatus,
    val assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    val assistantRepository: AssistantRepository,
    val registry: McpRegistry,
    val scope: kotlinx.coroutines.CoroutineScope,
    val context: android.content.Context,
)

private data class McpServerDetailsActions(
    val onDismiss: () -> Unit,
    val onOpenRuntime: () -> Unit,
    val onEdit: () -> Unit,
    val onOpenChatDraft: (String, String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerRow(spec: McpServerRowSpec) {
    val server = spec.server
    val status = spec.status
    val mcpRegistry = spec.registry
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var contentKind by remember { mutableStateOf<McpContentBrowserKind?>(null) }
    // 删除是不可撤销的配置丢失，必须先二次确认再执行。
    var confirmDelete by remember { mutableStateOf(false) }
    val connectionState = status.connection
    val authorize = rememberMcpAuthorize(server.id, mcpRegistry)
    val oauthSupported = server.enabled &&
        (connectionState == McpConnectionState.NEEDS_AUTH || server.oauthConfig.enabled)
    // 状态描述字符串(需在 @Composable 上下文中提取,不能在 semantics{} lambda 内调用 stringResource)
    val stateEnabledText = stringResource(R.string.common_state_enabled)
    val stateDisabledText = stringResource(R.string.common_state_disabled)

    val rowData = McpServerRowData(
        server = server,
        status = status,
        boundNames = spec.boundNames,
        stateEnabledText = stateEnabledText,
        stateDisabledText = stateDisabledText,
        oauthSupported = oauthSupported,
    )
    McpServerRowHeader(rowData, spec.actions, mcpRegistry) { menuExpanded = true }
    McpServerRowMenu(
        state = McpServerRowMenuState(
            data = rowData,
            actions = spec.actions,
            registry = mcpRegistry,
            authorize = authorize,
            scope = scope,
            context = context,
            expanded = menuExpanded,
            callbacks = McpServerRowMenuCallbacks(
                onDismiss = { menuExpanded = false },
                onOpenContent = { contentKind = it },
                onConfirmDelete = { confirmDelete = true },
            ),
        ),
    )

    McpRecoveryActions(
        status,
        server.enabled,
        authorize,
        spec.onInstallRuntime,
        spec.actions.onEdit,
        spec.actions.onReconnect,
        modifier = Modifier.padding(horizontal = MuseSpacing.lg),
    )

    if (confirmDelete) {
        McpDeleteServerDialog(
            serverName = server.name.ifBlank { server.id },
            onDismiss = { confirmDelete = false },
            onDelete = {
                confirmDelete = false
                spec.onDelete()
            },
        )
    }

    contentKind?.let { kind ->
        McpContentBrowser(
            server = server,
            kind = kind,
            registry = mcpRegistry,
            onDismiss = { contentKind = null },
            onDraft = spec.onDraft?.let { use ->
                { draft ->
                    contentKind = null
                    use(draft)
                }
            },
        )
    }
}

private data class McpServerListState(
    val servers: List<McpServerConfig>,
    val statuses: Map<String, McpRegistry.ServerStatus>,
    val assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    val assistantRepository: AssistantRepository,
    val registry: McpRegistry,
    val scope: kotlinx.coroutines.CoroutineScope,
    val context: android.content.Context,
)

@Composable
private fun McpRiskNote(expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(MusePaddings.cardInner),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.settings_mcp_risk_note_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.settings_common_collapse else R.string.common_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)) +
                expandVertically(animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS)),
            exit = fadeOut(animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS)) +
                shrinkVertically(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)),
        ) {
            Text(
                text = stringResource(R.string.mcp_external_tool_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MuseSpacing.sm),
            )
        }
    }
}

@Composable
private fun CardGroupScope.McpServerList(
    state: McpServerListState,
    onOpenDetails: (String) -> Unit,
    onEdit: (String) -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenChatDraft: (String, String) -> Unit,
    onShowAdd: () -> Unit,
    onShowImport: () -> Unit,
) {
    state.servers.forEach { server ->
        val status = state.statuses[server.id] ?: McpRegistry.ServerStatus()
        val bound = state.assistants.filter { server.id in state.assistantRepository.parseMcpServerIds(it) }
        content(key = "mcp_server_${server.id}") {
            McpServerRow(
                spec = McpServerRowSpec(
                    server = server,
                    status = status,
                    registry = state.registry,
                    boundNames = bound.map { it.name },
                    actions = McpServerRowActions(
                        onOpen = { onOpenDetails(server.id) },
                        onEdit = { onEdit(server.id) },
                        onToggleEnabled = { enabled ->
                            state.scope.launch {
                                resultOf { state.registry.setEnabled(server.id, enabled) }
                                    .onError { _, _ -> MuseToast.show(state.context.getString(R.string.mcp_save_failed)) }
                            }
                        },
                        onReconnect = { state.registry.reconnect(server.id) },
                    ),
                    onInstallRuntime = onOpenRuntime,
                    onDraft = bound.firstOrNull { it.enabled }?.let { assistant ->
                        { draft -> onOpenChatDraft(assistant.id, draft) }
                    },
                    onDelete = {
                        state.scope.launch {
                            resultOf { state.registry.removeServer(server.id) }
                                .onError { _, _ -> MuseToast.show(state.context.getString(R.string.mcp_save_failed)) }
                        }
                    },
                ),
            )
        }
    }
    actionItem(key = "mcp_add", title = stringResource(R.string.settings_mcp_add_action), onClick = onShowAdd)
    actionItem(key = "mcp_import", title = stringResource(R.string.mcp_import_action), onClick = onShowImport)
}

@Composable
private fun McpServerDetailsDialog(
    state: McpServerDetailsState,
    actions: McpServerDetailsActions,
) {
    val details = state.details
    val status = state.status
    val assistants = state.assistants
    val assistantRepository = state.assistantRepository
    val registry = state.registry
    val scope = state.scope
    val context = state.context
    val onDismiss = actions.onDismiss
    val onOpenRuntime = actions.onOpenRuntime
    val onEdit = actions.onEdit
    val onOpenChatDraft = actions.onOpenChatDraft
    val current = assistants.filter { details.id in assistantRepository.parseMcpServerIds(it) }.map { it.id }.toSet()
    var selected by remember(details.id, current) { mutableStateOf(current) }
    var saving by remember(details.id) { mutableStateOf(false) }
    val authorize = rememberMcpAuthorize(details.id, registry)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = details.name,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                Text(mcpStatusText(details, status), style = MaterialTheme.typography.bodyMedium)
                McpAuthorizationStatus(details.id, registry)
                if (status.connection == McpConnectionState.FAILED) {
                    status.issue?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.bodySmall) }
                }
                McpRecoveryActions(status, details.enabled, authorize, onOpenRuntime, onEdit) { registry.reconnect(details.id) }
                McpToolsList(status)
                McpAssistantBindingList(assistants, selected) { assistantId ->
                    selected = if (assistantId in selected) selected - assistantId else selected + assistantId
                }
                val trialAssistant = assistants.firstOrNull { it.enabled && it.id in selected }
                if (trialAssistant != null && status.tools?.isNotEmpty() == true) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.mcp_tool_trial, trialAssistant.name),
                        onClick = {
                            saving = true
                            scope.launch {
                                resultOf { registry.setAssistantBindings(details.id, selected) }
                                    .onSuccess {
                                        onDismiss()
                                        onOpenChatDraft(
                                            trialAssistant.id,
                                            context.getString(R.string.mcp_tool_trial_message, details.name),
                                        )
                                    }
                                    .onError { _, _ -> MuseToast.show(context.getString(R.string.mcp_save_failed)) }
                                saving = false
                            }
                        },
                        fillWidth = false,
                        enabled = !saving,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.common_save),
        confirmEnabled = !saving,
        onConfirm = {
            saving = true
            scope.launch {
                resultOf { registry.setAssistantBindings(details.id, selected) }
                    .onSuccess { onDismiss() }
                    .onError { _, _ -> MuseToast.show(context.getString(R.string.mcp_save_failed)) }
                saving = false
            }
        },
        dismissText = stringResource(R.string.common_close),
        onDismiss = onDismiss,
    )
}

@Composable
private fun McpToolsList(status: McpRegistry.ServerStatus) {
    status.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
        Text(stringResource(R.string.mcp_details_tools), style = MaterialTheme.typography.titleSmall)
        tools.forEach { tool ->
            Column {
                Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                tool.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun McpAssistantBindingList(
    assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Text(stringResource(R.string.mcp_choose_assistants), style = MaterialTheme.typography.titleSmall)
    assistants.forEach { assistant ->
        val checked = assistant.id in selected
        MuseListItem(
            onClick = { onToggle(assistant.id) },
            headlineContent = { Text(assistant.name) },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        )
    }
}

private data class McpServerRowData(
    val server: McpServerConfig,
    val status: McpRegistry.ServerStatus,
    val boundNames: List<String>,
    val stateEnabledText: String,
    val stateDisabledText: String,
    val oauthSupported: Boolean,
)

private data class McpServerRowActions(
    val onOpen: () -> Unit,
    val onEdit: () -> Unit,
    val onToggleEnabled: (Boolean) -> Unit,
    val onReconnect: () -> Unit,
)

private data class McpServerRowMenuState(
    val data: McpServerRowData,
    val actions: McpServerRowActions,
    val registry: McpRegistry,
    val authorize: () -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    val context: android.content.Context,
    val expanded: Boolean,
    val callbacks: McpServerRowMenuCallbacks,
)

private data class McpServerRowMenuCallbacks(
    val onDismiss: () -> Unit,
    val onOpenContent: (McpContentBrowserKind) -> Unit,
    val onConfirmDelete: () -> Unit,
)

@Composable
private fun McpServerRowHeader(
    data: McpServerRowData,
    actions: McpServerRowActions,
    registry: McpRegistry,
    onOpenMenu: () -> Unit,
) {
    val server = data.server
    val status = data.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        io.zer0.muse.ui.common.settings.StatusDot(
            color = when (status.connection) {
                McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                McpConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                McpConnectionState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
            size = MuseSpacing.xs,
            pulse = status.connection == McpConnectionState.CONNECTING,
        )
        Column(modifier = Modifier.weight(1f).clickable(onClick = actions.onOpen)) {
            Text(
                text = server.name.ifBlank { server.id },
                style = MaterialTheme.typography.bodyLarge,
                color = if (server.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(server.transportType.label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mcpStatusText(server, status),
                style = MaterialTheme.typography.labelMedium,
                color = if (status.issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            McpAuthorizationStatus(server.id, registry)
            if (status.connection == McpConnectionState.FAILED) {
                status.issue?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                text = if (data.boundNames.isEmpty()) {
                    stringResource(R.string.mcp_none_bound)
                } else {
                    data.boundNames.joinToString(", ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MuseSwitch(
            checked = server.enabled,
            onCheckedChange = actions.onToggleEnabled,
            modifier = Modifier.semantics {
                stateDescription = if (server.enabled) data.stateEnabledText else data.stateDisabledText
            },
        )
        MuseTactileButton(
            icon = MuseIcons.moreVertical,
            onClick = onOpenMenu,
            contentDescription = stringResource(R.string.settings_mcp_more),
        )
    }
}

@Composable
private fun McpServerRowMenu(
    state: McpServerRowMenuState,
) {
    if (!state.expanded) return
    val data = state.data
    val actions = state.actions
    val registry = state.registry
    val authorize = state.authorize
    val scope = state.scope
    val context = state.context
    val onDismiss = state.callbacks.onDismiss
    val onOpenContent = state.callbacks.onOpenContent
    val onConfirmDelete = state.callbacks.onConfirmDelete
    val server = data.server
    MuseDialog(
        onDismissRequest = onDismiss,
        title = server.name,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                McpServerAction(textRes = R.string.mcp_edit_server, onDismiss = onDismiss, onClick = actions.onEdit)
                McpServerAction(textRes = R.string.mcp_use_assistants, onDismiss = onDismiss, onClick = actions.onOpen)
                if (data.oauthSupported) {
                    McpServerAction(textRes = R.string.mcp_authorize_action, onDismiss = onDismiss, onClick = authorize)
                    McpServerAction(
                        textRes = R.string.settings_mcp_oauth_revoke,
                        onDismiss = onDismiss,
                        onClick = {
                            scope.launch {
                                resultOf { registry.revokeOAuth(server.id) }
                                    .onSuccess { MuseToast.show(context.getString(R.string.settings_mcp_oauth_revoked)) }
                                    .onError { _, t ->
                                        MuseToast.show(
                                            context.getString(R.string.settings_mcp_oauth_revoke_failed, t?.message),
                                        )
                                    }
                            }
                        },
                    )
                }
                McpServerAction(
                    textRes = R.string.mcp_resource_browser,
                    onDismiss = onDismiss,
                    onClick = { onOpenContent(McpContentBrowserKind.RESOURCES) },
                )
                McpServerAction(
                    textRes = R.string.mcp_prompt_browser,
                    onDismiss = onDismiss,
                    onClick = { onOpenContent(McpContentBrowserKind.PROMPTS) },
                )
                McpServerAction(textRes = R.string.settings_mcp_reconnect, onDismiss = onDismiss, onClick = actions.onReconnect)
                MuseActionSheetRow(
                    text = stringResource(R.string.common_delete),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        onDismiss()
                        onConfirmDelete()
                    },
                )
            }
        },
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

@Composable
private fun McpServerAction(
    textRes: Int,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    MuseActionSheetRow(text = stringResource(textRes)) {
        onDismiss()
        onClick()
    }
}

@Composable
private fun McpDeleteServerDialog(
    serverName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.common_delete),
        content = {
            Text(
                text = stringResource(R.string.provider_plugins_delete_confirm_named, serverName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmText = stringResource(R.string.common_delete),
        destructive = true,
        onConfirm = onDelete,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun mcpStatusText(server: McpServerConfig, status: McpRegistry.ServerStatus): String {
    if (!server.enabled) return stringResource(R.string.mcp_status_disabled)
    return when (status.connection) {
        McpConnectionState.DISCONNECTED -> stringResource(R.string.mcp_status_disconnected)
        McpConnectionState.CONNECTING -> stringResource(R.string.mcp_status_connecting)
        McpConnectionState.RECONNECTING -> stringResource(R.string.mcp_status_reconnecting)
        McpConnectionState.NEEDS_AUTH -> stringResource(R.string.mcp_status_auth)
        McpConnectionState.FAILED -> stringResource(
            if (status.issue == McpRegistry.ConnectionIssue.TIMED_OUT) {
                R.string.mcp_status_timed_out
            } else {
                R.string.mcp_status_failed
            },
        )
        McpConnectionState.CONNECTED -> when {
            status.issue != null -> stringResource(status.issue.label())
            status.tools == null -> stringResource(R.string.mcp_status_fetching)
            status.tools.isEmpty() -> stringResource(R.string.mcp_status_no_tools)
            else -> stringResource(R.string.mcp_status_tools, status.tools.size)
        }
    }
}

private fun McpRegistry.ConnectionIssue.label(): Int = when (this) {
    McpRegistry.ConnectionIssue.INVALID_ADDRESS -> R.string.mcp_issue_invalid_address
    McpRegistry.ConnectionIssue.DnsFailed -> R.string.mcp_issue_dns
    McpRegistry.ConnectionIssue.ADDRESS_BLOCKED -> R.string.mcp_issue_address_blocked
    McpRegistry.ConnectionIssue.RUNTIME_REQUIRED -> R.string.mcp_issue_runtime
    McpRegistry.ConnectionIssue.START_FAILED -> R.string.mcp_issue_start
    McpRegistry.ConnectionIssue.HANDSHAKE_FAILED -> R.string.mcp_issue_handshake
    McpRegistry.ConnectionIssue.TIMED_OUT -> R.string.mcp_status_timed_out
    McpRegistry.ConnectionIssue.TOOLS_FAILED -> R.string.mcp_issue_tools
}

private fun McpTransportType.label(): Int = when (this) {
    McpTransportType.STREAMABLE_HTTP -> R.string.mcp_transport_http
    McpTransportType.SSE -> R.string.mcp_transport_sse
    McpTransportType.STDIO -> R.string.mcp_transport_stdio
}

@Composable
private fun McpAuthorizationStatus(serverId: String, registry: McpRegistry) {
    val flow = registry.oauthStatus(serverId) ?: return
    val status by flow.collectAsStateWithLifecycle()
    val label = when (status) {
        McpOAuthStatus.LAUNCHING -> R.string.mcp_auth_waiting
        McpOAuthStatus.DENIED, McpOAuthStatus.FAILED -> R.string.mcp_auth_problem
        McpOAuthStatus.IDLE, McpOAuthStatus.REQUIRED, McpOAuthStatus.AUTHORIZED -> return
    }
    Text(text = stringResource(label), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun rememberMcpAuthorize(serverId: String, registry: McpRegistry): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pending by remember(serverId) { mutableStateOf(false) }
    return {
        if (!pending) {
            pending = true
            scope.launch {
                resultOf {
                    val url = registry.authorize(serverId)
                    if (url == null) {
                        MuseToast.show(context.getString(R.string.mcp_authorize_failed))
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        MuseToast.show(context.getString(R.string.mcp_auth_browser_return))
                    }
                }.onError { _, _ ->
                    registry.cancelOAuthFlow(serverId)
                    MuseToast.show(context.getString(R.string.mcp_authorize_failed))
                }
                pending = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpRecoveryActions(
    status: McpRegistry.ServerStatus,
    enabled: Boolean,
    onAuthorize: () -> Unit,
    onInstallRuntime: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled || (status.issue == null && status.connection != McpConnectionState.NEEDS_AUTH)) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
        when {
            status.connection == McpConnectionState.NEEDS_AUTH -> MuseCapsuleButton(
                text = stringResource(R.string.mcp_authorize_action),
                onClick = onAuthorize,
                fillWidth = false,
            )
            status.issue == McpRegistry.ConnectionIssue.RUNTIME_REQUIRED -> {
                MuseCapsuleButton(
                    text = stringResource(R.string.mcp_install_runtime),
                    onClick = onInstallRuntime,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_retry),
                    onClick = onRetry,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
            else -> {
                MuseCapsuleButton(
                    text = stringResource(R.string.mcp_edit_server),
                    onClick = onEdit,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_retry),
                    onClick = onRetry,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpServerAddDialog(
    existing: McpServerConfig?,
    onOpenRuntime: () -> Unit,
    onDismiss: () -> Unit,
    onSave: suspend (McpServerConfig) -> Boolean,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    var transportType by remember { mutableStateOf(existing?.transportType ?: McpTransportType.STREAMABLE_HTTP) }
    var authToken by remember { mutableStateOf(existing?.authToken.orEmpty()) }
    var addError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var runtimeReady by remember { mutableStateOf<Boolean?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var headersText by remember {
        mutableStateOf(existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
    }
    var environmentText by remember {
        mutableStateOf(existing?.environment?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty())
    }
    var autoReconnect by remember { mutableStateOf(existing?.autoReconnect ?: true) }
    var maxReconnectAttempts by remember { mutableStateOf((existing?.maxReconnectAttempts ?: 5).toString()) }
    var reconnectBaseMs by remember { mutableStateOf((existing?.reconnectBaseMs ?: 3000L).toString()) }
    var requestTimeoutMs by remember { mutableStateOf((existing?.requestTimeoutMs ?: 30000L).toString()) }
    var toolsReadyTimeoutMs by remember { mutableStateOf((existing?.toolsReadyTimeoutMs ?: 5000L).toString()) }
    var feishuAppId by remember { mutableStateOf(existing?.feishuAuth?.appId.orEmpty()) }
    var feishuAppSecret by remember { mutableStateOf(existing?.feishuAuth?.appSecret.orEmpty()) }
    var selectedTemplateId by remember {
        mutableStateOf(if (existing?.feishuAuth?.enabled == true) "feishu_remote" else McpServerTemplates.CUSTOM_ID)
    }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedTemplate = remember(selectedTemplateId) { McpServerTemplates.find(selectedTemplateId) }
    LaunchedEffect(transportType) {
        runtimeReady = if (transportType == McpTransportType.STDIO) {
            withContext(Dispatchers.IO) { io.zer0.muse.runtime.MuseRuntime.isReady(context) }
        } else {
            null
        }
    }

    val state = McpServerDraftState(
        connection = McpConnectionDraftState(
            name = name,
            url = url,
            command = command,
            transportType = transportType,
            authToken = authToken,
            environmentText = environmentText,
            runtimeReady = runtimeReady,
        ),
        advanced = McpAdvancedDraftState(
            showAdvanced = showAdvanced,
            headersText = headersText,
            autoReconnect = autoReconnect,
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectBaseMs = reconnectBaseMs,
            requestTimeoutMs = requestTimeoutMs,
            toolsReadyTimeoutMs = toolsReadyTimeoutMs,
        ),
        template = McpTemplateDraftState(
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            selectedTemplate = selectedTemplate,
            templateMenuExpanded = templateMenuExpanded,
        ),
        addError = addError,
        saving = saving,
    )
    val actions = McpServerDraftActions(
        text = McpDraftTextActions(
            onNameChange = { name = it },
            onUrlChange = { url = it },
            onCommandChange = { command = it },
            onAuthTokenChange = { authToken = it },
            onHeadersChange = { headersText = it },
            onEnvironmentChange = { environmentText = it },
        ),
        numeric = McpDraftNumericActions(
            onAutoReconnectChange = { autoReconnect = it },
            onMaxReconnectAttemptsChange = { maxReconnectAttempts = it.filter(Char::isDigit) },
            onReconnectBaseMsChange = { reconnectBaseMs = it.filter(Char::isDigit) },
            onRequestTimeoutChange = { requestTimeoutMs = it.filter(Char::isDigit) },
            onToolsReadyTimeoutChange = { toolsReadyTimeoutMs = it.filter(Char::isDigit) },
        ),
        template = McpDraftTemplateActions(
            onFeishuAppIdChange = { feishuAppId = it },
            onFeishuAppSecretChange = { feishuAppSecret = it },
            onTemplateSelected = { template ->
                selectedTemplateId = template.id
                name = if (template.id == McpServerTemplates.CUSTOM_ID) "" else template.displayNameResId
                    ?.let { context.getString(it) } ?: template.displayName
                url = ""
                command = template.commandPlaceholder.takeIf { template.transportType == McpTransportType.STDIO }.orEmpty()
                authToken = ""
                headersText = template.defaultHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                feishuAppId = ""
                feishuAppSecret = ""
                transportType = template.transportType
                addError = null
                templateMenuExpanded = false
            },
            onTemplateMenuChange = { templateMenuExpanded = it },
        ),
        dialog = McpDraftDialogActions(
            onTransportChange = { transportType = it },
            onAdvancedChange = { showAdvanced = it },
            onErrorChange = { addError = it },
            onSavingChange = { saving = it },
        ),
    )
    McpServerAddDialogContent(
        existing = existing,
        state = state,
        actions = actions,
        host = McpServerDialogHost(context, scope, onOpenRuntime, onSave, onDismiss),
    )
}

private data class McpConnectionDraftState(
    val name: String,
    val url: String,
    val command: String,
    val transportType: McpTransportType,
    val authToken: String,
    val environmentText: String,
    val runtimeReady: Boolean?,
)

private data class McpAdvancedDraftState(
    val showAdvanced: Boolean,
    val headersText: String,
    val autoReconnect: Boolean,
    val maxReconnectAttempts: String,
    val reconnectBaseMs: String,
    val requestTimeoutMs: String,
    val toolsReadyTimeoutMs: String,
)

private data class McpTemplateDraftState(
    val feishuAppId: String,
    val feishuAppSecret: String,
    val selectedTemplate: McpServerTemplate,
    val templateMenuExpanded: Boolean,
)

private data class McpServerDraftState(
    val connection: McpConnectionDraftState,
    val advanced: McpAdvancedDraftState,
    val template: McpTemplateDraftState,
    val addError: String?,
    val saving: Boolean,
) {
    val name get() = connection.name
    val url get() = connection.url
    val command get() = connection.command
    val transportType get() = connection.transportType
    val authToken get() = connection.authToken
    val environmentText get() = connection.environmentText
    val runtimeReady get() = connection.runtimeReady
    val showAdvanced get() = advanced.showAdvanced
    val headersText get() = advanced.headersText
    val autoReconnect get() = advanced.autoReconnect
    val maxReconnectAttempts get() = advanced.maxReconnectAttempts
    val reconnectBaseMs get() = advanced.reconnectBaseMs
    val requestTimeoutMs get() = advanced.requestTimeoutMs
    val toolsReadyTimeoutMs get() = advanced.toolsReadyTimeoutMs
    val feishuAppId get() = template.feishuAppId
    val feishuAppSecret get() = template.feishuAppSecret
    val selectedTemplate get() = template.selectedTemplate
    val templateMenuExpanded get() = template.templateMenuExpanded
}

private data class McpDraftTextActions(
    val onNameChange: (String) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onCommandChange: (String) -> Unit,
    val onAuthTokenChange: (String) -> Unit,
    val onHeadersChange: (String) -> Unit,
    val onEnvironmentChange: (String) -> Unit,
)

private data class McpDraftNumericActions(
    val onAutoReconnectChange: (Boolean) -> Unit,
    val onMaxReconnectAttemptsChange: (String) -> Unit,
    val onReconnectBaseMsChange: (String) -> Unit,
    val onRequestTimeoutChange: (String) -> Unit,
    val onToolsReadyTimeoutChange: (String) -> Unit,
)

private data class McpDraftTemplateActions(
    val onFeishuAppIdChange: (String) -> Unit,
    val onFeishuAppSecretChange: (String) -> Unit,
    val onTemplateSelected: (McpServerTemplate) -> Unit,
    val onTemplateMenuChange: (Boolean) -> Unit,
)

private data class McpDraftDialogActions(
    val onTransportChange: (McpTransportType) -> Unit,
    val onAdvancedChange: (Boolean) -> Unit,
    val onErrorChange: (String?) -> Unit,
    val onSavingChange: (Boolean) -> Unit,
)

private data class McpServerDraftActions(
    val text: McpDraftTextActions,
    val numeric: McpDraftNumericActions,
    val template: McpDraftTemplateActions,
    val dialog: McpDraftDialogActions,
) {
    val onNameChange get() = text.onNameChange
    val onUrlChange get() = text.onUrlChange
    val onCommandChange get() = text.onCommandChange
    val onAuthTokenChange get() = text.onAuthTokenChange
    val onHeadersChange get() = text.onHeadersChange
    val onEnvironmentChange get() = text.onEnvironmentChange
    val onAutoReconnectChange get() = numeric.onAutoReconnectChange
    val onMaxReconnectAttemptsChange get() = numeric.onMaxReconnectAttemptsChange
    val onReconnectBaseMsChange get() = numeric.onReconnectBaseMsChange
    val onRequestTimeoutChange get() = numeric.onRequestTimeoutChange
    val onToolsReadyTimeoutChange get() = numeric.onToolsReadyTimeoutChange
    val onFeishuAppIdChange get() = template.onFeishuAppIdChange
    val onFeishuAppSecretChange get() = template.onFeishuAppSecretChange
    val onTemplateSelected get() = template.onTemplateSelected
    val onTemplateMenuChange get() = template.onTemplateMenuChange
    val onTransportChange get() = dialog.onTransportChange
    val onAdvancedChange get() = dialog.onAdvancedChange
    val onErrorChange get() = dialog.onErrorChange
    val onSavingChange get() = dialog.onSavingChange
}

private data class McpServerDialogHost(
    val context: android.content.Context,
    val scope: kotlinx.coroutines.CoroutineScope,
    val onOpenRuntime: () -> Unit,
    val onSave: suspend (McpServerConfig) -> Boolean,
    val onDismiss: () -> Unit,
)

private sealed interface McpDraftValidation {
    data class Invalid(val messageRes: Int) : McpDraftValidation
    data class Valid(val config: McpServerConfig) : McpDraftValidation
}

private fun validateMcpDraft(existing: McpServerConfig?, state: McpServerDraftState): McpDraftValidation {
    val local = state.transportType == McpTransportType.STDIO
    val address = runCatching { java.net.URI(state.url.trim()) }.getOrNull()
    val validAddress = address?.scheme?.lowercase() in setOf("http", "https") &&
        address.host != null && address.userInfo == null && address.fragment == null
    val environment = if (local) parseConfigPairs(state.environmentText, '=', ::isMcpEnvironmentName) else emptyMap()
    val headers = if (local) emptyMap() else parseConfigPairs(state.headersText, ':', MCP_HEADER_NAME::matches)
    val error = when {
        local && state.command.isBlank() -> R.string.settings_mcp_stdio_command_required
        !local && !validAddress -> R.string.mcp_issue_invalid_address
        environment == null -> R.string.mcp_env_invalid
        headers == null -> R.string.mcp_headers_invalid
        state.selectedTemplate.id == "feishu_remote" && state.transportType != McpTransportType.STDIO &&
            (state.feishuAppId.isBlank() || state.feishuAppSecret.isBlank()) -> R.string.audit_mcp_feishu_credentials
        else -> null
    }
    if (error != null) return McpDraftValidation.Invalid(error)
    val id = existing?.id ?: "mcp-" + java.util.UUID.randomUUID().toString().take(12)
    val base = existing ?: McpServerConfig(id = id, name = "")
    val sameEndpoint = base.url == state.url.trim() && base.transportType == state.transportType
    return McpDraftValidation.Valid(
        base.copy(
            name = state.name.trim().ifBlank {
                if (local) state.command.trim().substringBefore(' ') else address?.host.orEmpty()
            },
            transportType = state.transportType,
            url = if (local) "" else state.url.trim(),
            command = if (local) state.command.trim() else "",
            authToken = if (local) "" else state.authToken.trim(),
            headers = headers.orEmpty(),
            environment = environment.orEmpty(),
            autoReconnect = state.autoReconnect,
            maxReconnectAttempts = state.maxReconnectAttempts.toIntOrNull()?.coerceIn(1, 10) ?: 5,
            reconnectBaseMs = state.reconnectBaseMs.toLongOrNull()?.coerceIn(250L, 60_000L) ?: 3000L,
            requestTimeoutMs = state.requestTimeoutMs.toLongOrNull()?.coerceIn(1000L, 120_000L) ?: 30_000L,
            toolsReadyTimeoutMs = state.toolsReadyTimeoutMs.toLongOrNull()?.coerceIn(1000L, 120_000L) ?: 5_000L,
            oauthConfig = if (sameEndpoint) base.oauthConfig else io.zer0.muse.mcp.McpOAuthConfig(),
            feishuAuth = McpFeishuAuthConfig(
                enabled = state.selectedTemplate.id == "feishu_remote" && !local,
                appId = state.feishuAppId.trim().takeIf { state.selectedTemplate.id == "feishu_remote" && !local }.orEmpty(),
                appSecret = state.feishuAppSecret.trim().takeIf { state.selectedTemplate.id == "feishu_remote" && !local }.orEmpty(),
            ),
            lastConnectedAt = if (sameEndpoint) base.lastConnectedAt else 0L,
        ),
    )
}

@Composable
private fun McpServerAddDialogContent(
    existing: McpServerConfig?,
    state: McpServerDraftState,
    actions: McpServerDraftActions,
    host: McpServerDialogHost,
) {
    MuseDialog(
        onDismissRequest = host.onDismiss,
        title = stringResource(if (existing == null) R.string.settings_mcp_add_action else R.string.mcp_edit_server),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.addError?.let { InlineError(message = it, onDismiss = { actions.onErrorChange(null) }) }
                McpTemplatePicker(state, actions, host.context)
                Text(
                    text = state.selectedTemplate.summaryResId?.let { host.context.getString(it) }
                        ?: state.selectedTemplate.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                McpServerConnectionFields(state, actions, host.context)
                McpServerAdvancedFields(state, actions)
            }
        },
        confirmText = stringResource(
            if (state.transportType == McpTransportType.STDIO && state.runtimeReady == false) {
                R.string.mcp_save_install
            } else {
                R.string.mcp_save_connect
            },
        ),
        confirmEnabled = !state.saving,
        onConfirm = {
            when (val result = validateMcpDraft(existing, state)) {
                is McpDraftValidation.Invalid -> actions.onErrorChange(host.context.getString(result.messageRes))
                is McpDraftValidation.Valid -> {
                    actions.onErrorChange(null)
                    actions.onSavingChange(true)
                    host.scope.launch {
                        resultOf { host.onSave(result.config) }
                            .onSuccess { saved ->
                                if (!saved) {
                                    actions.onErrorChange(host.context.getString(R.string.mcp_save_failed))
                                } else if (state.transportType == McpTransportType.STDIO && state.runtimeReady == false) {
                                    host.onOpenRuntime()
                                }
                            }
                            .onError { _, _ -> actions.onErrorChange(host.context.getString(R.string.mcp_save_failed)) }
                        actions.onSavingChange(false)
                    }
                }
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = host.onDismiss,
    )
}

@Composable
private fun McpTemplatePicker(
    state: McpServerDraftState,
    actions: McpServerDraftActions,
    context: android.content.Context,
) {
    Text(
        text = stringResource(R.string.code_gap_mcp_config_template),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
        MuseCapsuleButton(
            text = state.selectedTemplate.displayNameResId?.let { context.getString(it) }
                ?: state.selectedTemplate.displayName,
            onClick = { actions.onTemplateMenuChange(true) },
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
        )
        MuseAnchoredMenu(
            expanded = state.templateMenuExpanded,
            onDismissRequest = { actions.onTemplateMenuChange(false) },
        ) {
            (listOf(McpServerTemplates.custom) + McpServerTemplates.all).forEach { template ->
                MuseListItem(
                    onClick = { actions.onTemplateSelected(template) },
                    headlineContent = {
                        Column {
                            Text(template.displayNameResId?.let { context.getString(it) } ?: template.displayName)
                            Text(
                                text = template.summaryResId?.let { context.getString(it) } ?: template.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerConnectionFields(
    state: McpServerDraftState,
    actions: McpServerDraftActions,
    context: android.content.Context,
) {
    SettingField(
        label = stringResource(R.string.settings_mcp_display_name),
        value = state.name,
        onValueChange = actions.onNameChange,
        placeholder = stringResource(R.string.settings_mcp_display_name_placeholder),
    )
    if (state.transportType == McpTransportType.STDIO) {
        SettingField(
            label = stringResource(R.string.settings_mcp_stdio_command),
            value = state.command,
            onValueChange = actions.onCommandChange,
            placeholder = stringResource(R.string.mcp_stdio_example),
        )
        Text(
            text = stringResource(R.string.mcp_stdio_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SettingField(
            label = stringResource(R.string.mcp_address),
            value = state.url,
            onValueChange = actions.onUrlChange,
            placeholder = state.selectedTemplate.urlPlaceholderResId?.let { context.getString(it) }
                ?: state.selectedTemplate.urlPlaceholder,
        )
    }
    Text(stringResource(R.string.settings_mcp_transport_type), style = MaterialTheme.typography.labelMedium)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TransportChoice(
            text = stringResource(R.string.mcp_transport_sse),
            selected = state.transportType == McpTransportType.SSE,
            onClick = { actions.onTransportChange(McpTransportType.SSE) },
        )
        TransportChoice(
            text = stringResource(R.string.mcp_transport_http),
            selected = state.transportType == McpTransportType.STREAMABLE_HTTP,
            onClick = { actions.onTransportChange(McpTransportType.STREAMABLE_HTTP) },
        )
        TransportChoice(
            text = stringResource(R.string.mcp_transport_stdio),
            selected = state.transportType == McpTransportType.STDIO,
            onClick = { actions.onTransportChange(McpTransportType.STDIO) },
        )
    }
    if (state.transportType == McpTransportType.STDIO) {
        MuseTextField(
            value = state.environmentText,
            onValueChange = actions.onEnvironmentChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.mcp_env_label)) },
            singleLine = false,
            visualTransformation = McpEnvironmentMask,
        )
        if (state.runtimeReady == false) {
            Text(stringResource(R.string.mcp_issue_runtime), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.selectedTemplate.id == "feishu_remote" && state.transportType != McpTransportType.STDIO) {
        SettingField(
            label = stringResource(R.string.code_gap_feishu_app_id),
            value = state.feishuAppId,
            onValueChange = actions.onFeishuAppIdChange,
            placeholder = "cli_xxxxx",
        )
        SettingField(
            label = stringResource(R.string.code_gap_feishu_app_secret),
            value = state.feishuAppSecret,
            onValueChange = actions.onFeishuAppSecretChange,
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(
            text = stringResource(R.string.code_gap_feishu_token_refresh_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (state.transportType != McpTransportType.STDIO) {
        SettingField(
            label = stringResource(R.string.settings_mcp_bearer_token),
            value = state.authToken,
            onValueChange = actions.onAuthTokenChange,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun TransportChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    MuseCapsuleButton(
        text = text,
        onClick = onClick,
        variant = if (selected) IosCapsuleButtonVariant.Primary else IosCapsuleButtonVariant.Text,
        fillWidth = false,
    )
}

@Composable
private fun McpServerAdvancedFields(
    state: McpServerDraftState,
    actions: McpServerDraftActions,
) {
    val enabledState = stringResource(R.string.common_state_enabled)
    val disabledState = stringResource(R.string.common_state_disabled)
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onAdvancedChange(!state.showAdvanced) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_common_advanced),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = if (state.showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = stringResource(
                if (state.showAdvanced) R.string.settings_common_collapse else R.string.common_expand,
            ),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
    if (state.showAdvanced) {
        if (state.transportType != McpTransportType.STDIO) {
            MuseTextField(
                value = state.headersText,
                onValueChange = actions.onHeadersChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_mcp_custom_headers)) },
                placeholder = { Text("Authorization: Bearer xxx\nX-API-Key: yyy") },
                singleLine = false,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.onAutoReconnectChange(!state.autoReconnect) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.settings_mcp_auto_reconnect),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseSwitch(
                checked = state.autoReconnect,
                onCheckedChange = actions.onAutoReconnectChange,
                modifier = Modifier.semantics {
                    stateDescription = if (state.autoReconnect) enabledState else disabledState
                },
            )
        }
        TimeoutField(R.string.settings_mcp_max_reconnect, state.maxReconnectAttempts, actions.onMaxReconnectAttemptsChange, "5")
        TimeoutField(R.string.settings_mcp_reconnect_base, state.reconnectBaseMs, actions.onReconnectBaseMsChange, "3000")
        TimeoutField(R.string.settings_mcp_request_timeout, state.requestTimeoutMs, actions.onRequestTimeoutChange, "30000")
        TimeoutField(R.string.settings_mcp_tools_ready_timeout, state.toolsReadyTimeoutMs, actions.onToolsReadyTimeoutChange, "5000")
    }
}

@Composable
private fun TimeoutField(labelRes: Int, value: String, onValueChange: (String) -> Unit, placeholder: String) {
    SettingField(
        label = stringResource(labelRes),
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
    )
}
@Composable
private fun McpImportDialog(onDismiss: () -> Unit, onImport: suspend (List<McpServerConfig>) -> Boolean) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<McpServerConfig>?>(null) }
    var invalid by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.mcp_import_action),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                if (invalid) Text(stringResource(R.string.mcp_import_failed), color = MaterialTheme.colorScheme.error)
                val configs = preview
                if (configs == null) {
                    MuseTextField(
                        value = input,
                        onValueChange = {
                            input = it.take(100_001)
                            invalid = false
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.mcp_import_json)) },
                    )
                } else {
                    Text(stringResource(R.string.mcp_import_review))
                    configs.forEach { config ->
                        Text(config.name, style = MaterialTheme.typography.titleSmall)
                        val destination = if (config.transportType == McpTransportType.STDIO) {
                            config.command
                        } else {
                            runCatching { java.net.URI(config.url).host }.getOrNull().orEmpty()
                        }
                        Text(destination, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmText = stringResource(if (preview == null) R.string.mcp_content_preview else R.string.mcp_save_connect),
        confirmEnabled = input.isNotBlank() && !saving,
        onConfirm = {
            val configs = preview
            if (configs == null) {
                preview = McpConfigImport.parse(input)
                invalid = preview == null
            } else {
                saving = true
                scope.launch {
                    resultOf { onImport(configs) }
                        .onSuccess { invalid = !it }
                        .onError { _, _ -> invalid = true }
                    saving = false
                }
            }
        },
        dismissText = stringResource(if (preview != null) R.string.mcp_content_back else R.string.common_cancel),
        onDismiss = { if (preview == null) onDismiss() else preview = null },
    )
}

@Composable
private fun InlineError(message: String, onDismiss: () -> Unit) {
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
                imageVector = MuseIcons.info,
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.x,
                    contentDescription = stringResource(R.string.common_close),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 把"每行 key:value"的文本解析成 headers Map。
 * 空行 / 无冒号的行被忽略,空文本返回空 map。
 */
internal fun parseConfigPairs(raw: String, separator: Char, validName: (String) -> Boolean): Map<String, String>? {
    val parsed = mutableMapOf<String, String>()
    for (line in raw.lineSequence().filter(String::isNotBlank)) {
        val index = line.indexOf(separator)
        if (index < 1) return null
        val name = line.substring(0, index).trim()
        if (!validName(name) || parsed.containsKey(name)) return null
        val value = line.substring(index + 1)
        parsed[name] = if (separator == '=') value else value.trim()
    }
    return parsed
}

private val McpEnvironmentMask = VisualTransformation { source ->
    var inValue = false
    val masked = buildString(source.length) {
        for (char in source.text) {
            when {
                char == '\n' -> {
                    inValue = false
                    append(char)
                }
                inValue -> append('•')
                else -> {
                    append(char)
                    if (char == '=') inValue = true
                }
            }
        }
    }
    TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
}
