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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
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
 * Phase 8.5: PromptInjection(模式注入)管理页。
 *
 * 顶层: 全部 PromptInjection 条目列表(可新增/编辑/删除/启用切换)。
 * 编辑态: 单条 PromptInjection 表单(name/mode/displayName/content/priority/insertionPosition/enabled)。
 *
 * 与 Lorebook 区别: PromptInjection 由用户在会话中切换"模式"触发,无需关键词匹配。
 */
@Composable
fun PromptInjectionScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.97 (P1-1): 进入管理页时懒加载 PromptInjection 列表
    LaunchedEffect(Unit) { viewModel.refreshPromptInjections() }
    var editing by remember { mutableStateOf<PromptInjectionEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PromptInjectionEntity?>(null) }
    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val newDefaultName = stringResource(R.string.prompt_injection_new_default_name)
    val promptInjectionModeMap = mapOf(
        "default" to stringResource(R.string.prompt_injection_mode_default),
        "creative" to stringResource(R.string.prompt_injection_mode_creative),
        "coding" to stringResource(R.string.prompt_injection_mode_coding),
        "roleplay" to stringResource(R.string.prompt_injection_mode_roleplay),
        "translation" to stringResource(R.string.prompt_injection_mode_translation),
        "analysis" to stringResource(R.string.prompt_injection_mode_analysis),
    )

    editing?.let { entity ->
        PromptInjectionEditPage(
            initial = entity,
            isNew = isNew,
            onBack = { editing = null; isNew = false },
            onSave = { saved ->
                viewModel.savePromptInjection(saved)
                editing = null
                isNew = false
            },
            promptInjectionModeMap = promptInjectionModeMap,
        )
        return
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.prompt_injection_screen_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            IosFloatingButton(
                icon = Icons.Default.Add,
                onClick = {
                    val now = System.currentTimeMillis()
                    editing = PromptInjectionEntity(
                        id = "pinj-$now",
                        name = newDefaultName,
                        mode = "default",
                        createdAt = now,
                        updatedAt = now,
                    )
                    isNew = true
                },
                contentDescription = stringResource(R.string.prompt_injection_new_cd),
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
                    text = stringResource(R.string.prompt_injection_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.promptInjections, key = { it.id }) { entry ->
                PromptInjectionCard(
                    entry = entry,
                    onEdit = { editing = entry; isNew = false },
                    onDelete = { deleteTarget = entry },
                    onToggleEnabled = {
                        viewModel.savePromptInjection(entry.copy(enabled = !entry.enabled))
                    },
                    promptInjectionModeMap = promptInjectionModeMap,
                )
            }
            if (state.promptInjections.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.SmartToy,
                        title = stringResource(R.string.prompt_injection_empty_title),
                        subtitle = stringResource(R.string.prompt_injection_empty_subtitle),
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.prompt_injection_delete_title),
            itemName = target.name,
            onConfirm = {
                viewModel.deletePromptInjection(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun PromptInjectionCard(
    entry: PromptInjectionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
    promptInjectionModeMap: Map<String, String>,
) {
    // i18n: 预提取字符串资源,避免在 ifBlank/semantics 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.prompt_injection_unnamed)
    val disabledText = stringResource(R.string.prompt_injection_disabled)
    val enabledStateText = stringResource(R.string.prompt_injection_enabled_cd)
    val disabledStateText = stringResource(R.string.prompt_injection_disabled_cd)
    val editCd = stringResource(R.string.prompt_injection_edit_cd)
    val deleteCd = stringResource(R.string.prompt_injection_delete_cd)
    val stateDesc = if (entry.enabled) enabledStateText else disabledStateText

    val modeLabel = promptInjectionModeMap[entry.mode] ?: entry.mode
    val modeLabelText = stringResource(R.string.prompt_injection_mode_label, modeLabel)

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
                imageVector = Icons.Default.SmartToy,
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
                    text = modeLabelText,
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

@Composable
private fun PromptInjectionEditPage(
    initial: PromptInjectionEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (PromptInjectionEntity) -> Unit,
    promptInjectionModeMap: Map<String, String>,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var displayName by rememberSaveable { mutableStateOf(initial.displayName) }
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var priority by rememberSaveable { mutableStateOf(initial.priority.toString()) }
    var insertionPosition by rememberSaveable { mutableStateOf(initial.insertionPosition) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }

    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.prompt_injection_unnamed)
    val backCd = stringResource(R.string.prompt_injection_back_cd)
    val saveText = stringResource(R.string.prompt_injection_save)
    val newTitleText = stringResource(R.string.prompt_injection_new_title)
    val editTitleText = stringResource(R.string.prompt_injection_edit_title)
    val unsavedTitle = stringResource(R.string.prompt_injection_unsaved_title)
    val unsavedContent = stringResource(R.string.prompt_injection_unsaved_content)
    val discardText = stringResource(R.string.prompt_injection_discard)

    // v1.48: 返回键拦截,避免误退丢失编辑
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val hasUnsavedChanges = remember(name, mode, displayName, content, priority, insertionPosition, enabled) {
        name != initial.name || mode != initial.mode ||
            displayName != initial.displayName || content != initial.content ||
            priority != initial.priority.toString() ||
            insertionPosition != initial.insertionPosition ||
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
                        // L-PID8: updatedAt 由 ViewModel.savePromptInjection 统一设置,此处不再重复
                        val saved = initial.copy(
                            name = name.trim().ifBlank { unnamedText },
                            mode = mode,
                            displayName = displayName.trim(),
                            content = content,
                            priority = priority.trim().toIntOrNull() ?: 0,
                            insertionPosition = insertionPosition,
                            enabled = enabled,
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

            SectionLabel(stringResource(R.string.prompt_injection_section_basic))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.prompt_injection_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )
            // 模式选择(预置模式 + 自定义)
            IosDropdown(
                value = mode,
                onValueChange = { selected ->
                    mode = selected
                    if (displayName.isBlank()) displayName = promptInjectionModeMap[selected] ?: ""
                },
                label = stringResource(R.string.prompt_injection_field_mode),
                options = listOf(
                    "default" to (stringResource(R.string.prompt_injection_mode_default) + " (default)"),
                    "creative" to (stringResource(R.string.prompt_injection_mode_creative) + " (creative)"),
                    "coding" to (stringResource(R.string.prompt_injection_mode_coding) + " (coding)"),
                    "roleplay" to (stringResource(R.string.prompt_injection_mode_roleplay) + " (roleplay)"),
                    "translation" to (stringResource(R.string.prompt_injection_mode_translation) + " (translation)"),
                    "analysis" to (stringResource(R.string.prompt_injection_mode_analysis) + " (analysis)"),
                ),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.prompt_injection_field_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.prompt_injection_section_content))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.prompt_injection_field_content)) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.prompt_injection_section_behavior))
            OutlinedTextField(
                // L-PID3: 限制最多 6 位数字(最大 999999),避免超长输入导致 toIntOrNull 溢出后静默回落 0
                value = priority,
                onValueChange = { priority = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.prompt_injection_field_priority)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )
            // L-PID2: 仅提供 before_system/after_system。PromptInjectionTransformer 不支持 before_last
            // (其 when 分支仅显式处理 before_system,其余走 else=after_system);before_last 仅 Lorebook 支持。
            IosDropdown(
                value = insertionPosition,
                onValueChange = { insertionPosition = it },
                label = stringResource(R.string.prompt_injection_field_insertion_position),
                options = listOf(
                    PromptInjectionEntity.INSERTION_BEFORE_SYSTEM,
                    PromptInjectionEntity.INSERTION_AFTER_SYSTEM,
                ).map { it to it },
            )
            SwitchRow(
                label = stringResource(R.string.prompt_injection_field_enabled),
                description = stringResource(R.string.prompt_injection_field_enabled_desc),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
