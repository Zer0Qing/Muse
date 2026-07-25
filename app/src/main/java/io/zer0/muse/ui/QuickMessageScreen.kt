package io.zer0.muse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosFloatingButton
import io.zer0.muse.ui.common.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow  // v1.48 (h21): 名称/预览省略号
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SwitchRow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import io.zer0.muse.ui.theme.MuseIconSizes
import org.koin.androidx.compose.koinViewModel

/**
 * Phase 8.5: QuickMessage(快捷消息)管理页。
 *
 * 顶层: 全部 QuickMessage 条目列表(可新增/编辑/删除/启用切换)。
 * 编辑态: 单条 QuickMessage 表单(name/content/scope/assistantId/sortIndex/enabled)。
 *
 * 模板变量: {{input}}(当前输入框内容) / {{clipboard}}(剪贴板) / {{date}}(日期)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickMessageScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.97 (P1-1): 进入管理页时懒加载 QuickMessage 列表
    LaunchedEffect(Unit) { viewModel.refreshAllQuickMessages() }
    var editing by remember { mutableStateOf<QuickMessageEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<QuickMessageEntity?>(null) }
    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val newDefaultName = stringResource(R.string.quick_msg_new_default_name)

    editing?.let { entity ->
        QuickMessageEditPage(
            initial = entity,
            isNew = isNew,
            assistants = state.assistants,
            onBack = { editing = null; isNew = false },
            onSave = { saved ->
                viewModel.saveQuickMessage(saved)
                editing = null
                isNew = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.quick_msg_screen_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            IosFloatingButton(
                icon = Icons.Default.Add,
                onClick = {
                    val now = System.currentTimeMillis()
                    editing = QuickMessageEntity(
                        id = "qmsg-$now",
                        name = newDefaultName,
                        createdAt = now,
                        updatedAt = now,
                    )
                    isNew = true
                },
                contentDescription = stringResource(R.string.quick_msg_new_cd),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.quick_msg_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.allQuickMessages, key = { it.id }) { entry ->
                QuickMessageCard(
                    entry = entry,
                    assistantName = state.assistants.firstOrNull { it.id == entry.assistantId }?.name,
                    onEdit = { editing = entry; isNew = false },
                    onDelete = { deleteTarget = entry },
                    onToggleEnabled = {
                        viewModel.saveQuickMessage(entry.copy(enabled = !entry.enabled))
                    },
                )
            }
            if (state.allQuickMessages.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Bolt,
                        title = stringResource(R.string.quick_msg_empty_title),
                        subtitle = stringResource(R.string.quick_msg_empty_subtitle),
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.quick_msg_delete_title),
            itemName = target.name,
            onConfirm = {
                viewModel.deleteQuickMessage(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun QuickMessageCard(
    entry: QuickMessageEntity,
    assistantName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    // i18n: 预提取字符串资源,避免在 ifBlank/semantics 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.quick_msg_unnamed)
    val disabledText = stringResource(R.string.quick_msg_disabled)
    val scopeGlobalText = stringResource(R.string.quick_msg_scope_global)
    val scopeAssistantText = stringResource(R.string.quick_msg_scope_assistant, assistantName ?: entry.assistantId)
    val enabledStateText = stringResource(R.string.quick_msg_enabled_cd)
    val disabledStateText = stringResource(R.string.quick_msg_disabled_cd)
    val editCd = stringResource(R.string.quick_msg_edit_cd)
    val deleteCd = stringResource(R.string.quick_msg_delete_cd)

    val scopeLabel = when (entry.scope) {
        "global" -> scopeGlobalText
        "assistant" -> scopeAssistantText
        else -> entry.scope
    }
    val scopeLabelText = stringResource(R.string.quick_msg_scope_label, scopeLabel)
    val stateDesc = if (entry.enabled) enabledStateText else disabledStateText

    Card(
        shape = MuseShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (entry.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name.ifBlank { unnamedText },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
                        // v1.48 (h21): 名称单行 + 省略号,防止长名撑破布局
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.enabled) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = disabledText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(
                    text = scopeLabelText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (entry.content.isNotEmpty()) {
                    Text(
                        text = entry.content.take(40).replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // v1.48 (h21): 预览补 ellipsis(原仅 maxLines=1,无省略号会硬截断)
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IosSwitch(
                checked = entry.enabled,
                onCheckedChange = { onToggleEnabled() },
                modifier = Modifier.semantics {
                    stateDescription = stateDesc
                },
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                Icon(Icons.Default.Edit, contentDescription = editCd, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                Icon(Icons.Default.Delete, contentDescription = deleteCd, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickMessageEditPage(
    initial: QuickMessageEntity,
    isNew: Boolean,
    assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    onBack: () -> Unit,
    onSave: (QuickMessageEntity) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var scope by rememberSaveable { mutableStateOf(initial.scope) }
    var assistantId by rememberSaveable { mutableStateOf(initial.assistantId) }
    var sortIndex by rememberSaveable { mutableStateOf(initial.sortIndex.toString()) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }

    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.quick_msg_unnamed)
    val backCd = stringResource(R.string.quick_msg_back_cd)
    val saveText = stringResource(R.string.quick_msg_save)
    val newTitleText = stringResource(R.string.quick_msg_new_title)
    val editTitleText = stringResource(R.string.quick_msg_edit_title)
    val unsavedTitle = stringResource(R.string.quick_msg_unsaved_title)
    val unsavedContent = stringResource(R.string.quick_msg_unsaved_content)
    val discardText = stringResource(R.string.quick_msg_discard)
    val scopeGlobalOpt = stringResource(R.string.quick_msg_scope_global_opt)
    val scopeAssistantOpt = stringResource(R.string.quick_msg_scope_assistant_opt)

    // v1.48: 返回键拦截,避免误退丢失编辑
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val hasUnsavedChanges = remember(name, content, scope, assistantId, sortIndex, enabled) {
        name != initial.name || content != initial.content ||
            scope != initial.scope || assistantId != initial.assistantId ||
            sortIndex != initial.sortIndex.toString() ||
            enabled != initial.enabled
    }
    BackHandler {
        if (hasUnsavedChanges) showDiscardConfirm = true else onBack()
    }
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = unsavedTitle,
            content = { Text(unsavedContent) },
            confirmText = discardText,
            onConfirm = {
                showDiscardConfirm = false
                onBack()
            },
            destructive = true,
        )
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = if (isNew) newTitleText else editTitleText,
                onBack = {
                    if (hasUnsavedChanges) showDiscardConfirm = true else onBack()
                },
                actions = {
                    TextButton(onClick = {
                        val saved = initial.copy(
                            name = name.trim().ifBlank { unnamedText },
                            content = content,
                            scope = scope,
                            assistantId = if (scope == "assistant") assistantId else "",
                            sortIndex = sortIndex.trim().toIntOrNull() ?: 0,
                            enabled = enabled,
                            updatedAt = System.currentTimeMillis(),
                        )
                        onSave(saved)
                    }) { Text(saveText) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding() // v1.48: 键盘遮挡修复
                .navigationBarsPadding()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionLabel(stringResource(R.string.quick_msg_section_basic))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.quick_msg_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.quick_msg_section_content))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.quick_msg_field_content)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.quick_msg_section_scope))
            IosDropdown(
                value = scope,
                onValueChange = { scope = it },
                label = stringResource(R.string.quick_msg_field_scope),
                options = listOf(
                    "global" to scopeGlobalOpt,
                    "assistant" to scopeAssistantOpt,
                ),
            )
            if (scope == "assistant") {
                IosDropdown(
                    value = assistantId,
                    onValueChange = { assistantId = it },
                    label = stringResource(R.string.quick_msg_field_bind_assistant),
                    options = assistants.map { it.id to "${it.avatarEmoji} ${it.name}" },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.quick_msg_section_behavior))
            OutlinedTextField(
                value = sortIndex,
                onValueChange = { sortIndex = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.quick_msg_field_sort_index)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )
            SwitchRow(
                label = stringResource(R.string.quick_msg_field_enabled),
                description = stringResource(R.string.quick_msg_field_enabled_desc),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
