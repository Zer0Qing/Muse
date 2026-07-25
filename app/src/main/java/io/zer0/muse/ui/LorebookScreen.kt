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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
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
 * Phase 8.5: Lorebook(世界书)管理页。
 *
 * 顶层: 全部 Lorebook 条目列表(可新增/编辑/删除/启用切换)。
 * 编辑态: 单条 Lorebook 表单(name/keywords/content/priority/caseSensitive/insertionPosition/enabled)。
 *
 * 独立编写实现,UI 沿用 muse warm-paper 风格。
 */
@Composable
fun LorebookScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.97 (P1-1): 进入管理页时懒加载 Lorebook 列表(替代原 init 常驻 Flow 收集器)
    LaunchedEffect(Unit) { viewModel.refreshLorebooks() }
    var editing by remember { mutableStateOf<LorebookEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LorebookEntity?>(null) }
    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val newDefaultName = stringResource(R.string.lorebook_new_default_name)

    editing?.let { entity ->
        LorebookEditPage(
            initial = entity,
            isNew = isNew,
            onBack = { editing = null; isNew = false },
            onSave = { saved ->
                viewModel.saveLorebook(saved)
                editing = null
                isNew = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.lorebook_screen_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            IosFloatingButton(
                icon = Icons.Default.Add,
                onClick = {
                    val now = System.currentTimeMillis()
                    editing = LorebookEntity(
                        id = "lorebook-$now",
                        name = newDefaultName,
                        createdAt = now,
                        updatedAt = now,
                    )
                    isNew = true
                },
                contentDescription = stringResource(R.string.lorebook_new_cd),
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
                    text = stringResource(R.string.lorebook_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.lorebooks, key = { it.id }) { entry ->
                LorebookCard(
                    entry = entry,
                    onEdit = { editing = entry; isNew = false },
                    onDelete = { deleteTarget = entry },
                    onToggleEnabled = {
                        viewModel.saveLorebook(entry.copy(enabled = !entry.enabled))
                    },
                )
            }
            if (state.lorebooks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = stringResource(R.string.lorebook_empty_title),
                        subtitle = stringResource(R.string.lorebook_empty_subtitle),
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.lorebook_delete_title),
            itemName = target.name,
            onConfirm = {
                viewModel.deleteLorebook(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun LorebookCard(
    entry: LorebookEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    // i18n: 预提取字符串资源,避免在 ifBlank/semantics 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.lorebook_unnamed)
    val disabledText = stringResource(R.string.lorebook_disabled)
    val enabledStateText = stringResource(R.string.lorebook_enabled_cd)
    val disabledStateText = stringResource(R.string.lorebook_disabled_cd)
    val editCd = stringResource(R.string.lorebook_edit_cd)
    val deleteCd = stringResource(R.string.lorebook_delete_cd)
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
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
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
                // M-LORE1: 关键词解析统一走 LorebookRepository.parseKeywords,避免手动 split/removeSurrounding 损坏转义字符
                val kw = LorebookRepository.parseKeywords(entry.keywordsJson).joinToString(", ")
                if (kw.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.lorebook_keywords_label, kw),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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
private fun LorebookEditPage(
    initial: LorebookEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (LorebookEntity) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    // M-LORE1: 编辑初始化统一走 LorebookRepository.parseKeywords,避免手动 split/removeSurrounding 损坏转义字符
    val initialKeywordsText = remember { LorebookRepository.parseKeywords(initial.keywordsJson).joinToString(", ") }
    var keywordsText by rememberSaveable { mutableStateOf(initialKeywordsText) }
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var priority by rememberSaveable { mutableStateOf(initial.priority.toString()) }
    var caseSensitive by rememberSaveable { mutableStateOf(initial.caseSensitive) }
    var insertionPosition by rememberSaveable { mutableStateOf(initial.insertionPosition) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }

    // i18n: 预提取字符串资源,避免在 onClick 等非 Composable lambda 内调用 stringResource
    val unnamedText = stringResource(R.string.lorebook_unnamed)
    val backCd = stringResource(R.string.lorebook_back_cd)
    val saveText = stringResource(R.string.lorebook_save)
    val newTitleText = stringResource(R.string.lorebook_new_title)
    val editTitleText = stringResource(R.string.lorebook_edit_title)
    val unsavedTitle = stringResource(R.string.lorebook_unsaved_title)
    val unsavedContent = stringResource(R.string.lorebook_unsaved_content)
    val discardText = stringResource(R.string.lorebook_discard)

    // v1.48: 返回键拦截,避免误退丢失编辑
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    // M-LORE2: keywordsText 加入 remember keys 并参与比较,否则修改关键词后返回不提示未保存
    val hasUnsavedChanges = remember(name, keywordsText, content, priority, caseSensitive, insertionPosition, enabled) {
        name != initial.name || keywordsText != initialKeywordsText || content != initial.content ||
            priority != initial.priority.toString() ||
            caseSensitive != initial.caseSensitive ||
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
                        // L-LORE4: 复用 LorebookRepository.encodeKeywords 编码关键词,
                        // 与解析端(M-LORE1)使用同一 Json 配置,避免手动 AppJson 编码与 Repository 解析配置不一致
                        val keywordsList = if (keywordsText.isBlank()) emptyList()
                        else keywordsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val keywordsJson = LorebookRepository.encodeKeywords(keywordsList)
                        val saved = initial.copy(
                            name = name.trim().ifBlank { unnamedText },
                            keywordsJson = keywordsJson,
                            content = content,
                            priority = priority.trim().toIntOrNull() ?: 0,
                            caseSensitive = caseSensitive,
                            insertionPosition = insertionPosition,
                            enabled = enabled,
                            // L-PID8: updatedAt 由 ChatViewModel.saveLorebook 统一设置,避免双重设置
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

            SectionLabel(stringResource(R.string.lorebook_section_basic))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.lorebook_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )
            OutlinedTextField(
                value = keywordsText,
                onValueChange = { keywordsText = it },
                label = { Text(stringResource(R.string.lorebook_field_keywords)) },
                placeholder = { Text(stringResource(R.string.lorebook_field_keywords_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.lorebook_section_content))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.lorebook_field_content)) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = MuseShapes.medium,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.lorebook_section_behavior))
            OutlinedTextField(
                value = priority,
                onValueChange = { priority = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.lorebook_field_priority)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
            )
            IosDropdown(
                value = insertionPosition,
                onValueChange = { insertionPosition = it },
                label = stringResource(R.string.lorebook_field_insertion_position),
                options = listOf("before_system", "after_system", "before_last").map { it to it },
            )
            SwitchRow(
                label = stringResource(R.string.lorebook_field_case_sensitive),
                description = stringResource(R.string.lorebook_field_case_sensitive_desc),
                checked = caseSensitive,
                onCheckedChange = { caseSensitive = it },
            )
            SwitchRow(
                label = stringResource(R.string.lorebook_field_enabled),
                description = stringResource(R.string.lorebook_field_enabled_desc),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
