package io.zer0.muse.ui.worldbook

import io.zer0.muse.ui.common.surface.museBottomBarInsets

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SwitchRow
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.worldbook.WorldBookEntryEntity
import io.zer0.muse.worldbook.WorldBookInjectPosition
import io.zer0.muse.worldbook.WorldBookInjectTarget
import io.zer0.muse.worldbook.WorldBookRepository
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.UUID

/**
 * P1-2: Worldbook(动态世界书)管理页。
 *
 * 顶层: 全部 Worldbook 条目列表(可新增/编辑/删除/启停)。
 * 编辑态: 单条 Worldbook 表单(基础信息 + 内容 + 高级注入配置)。
 *
 * 与 LorebookScreen 的区别:
 *  - 支持 alwaysActive 常驻激活、scanDepth 多层扫描、isRegex 正则关键词
 *  - 支持 injectTarget(system/user/assistant) + injectPosition(prepend/append/at_depth) + insertionDepth
 *  - 支持 assistantId 绑定特定助手
 *  - 支持 SillyTavern World Info JSON 导入导出
 */
@Composable
fun WorldBookScreen(
    onBack: () -> Unit,
    viewModel: WorldBookViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<WorldBookEntryEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorldBookEntryEntity?>(null) }
    val newDefaultName = stringResource(R.string.worldbook_new_default_name)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    editing?.let { entity ->
        WorldBookEditPage(
            initial = entity,
            isNew = isNew,
            onBack = { editing = null; isNew = false },
            onSave = { saved ->
                viewModel.save(saved)
                editing = null
                isNew = false
            },
        )
        return
    }

    // 导入/导出反馈
    LaunchedEffect(state.importMessage) {
        state.importMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearImportMessage()
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.worldbook_screen_title),
                onBack = onBack,
                actions = {
                    TextButton(onClick = {
                        viewModel.exportSillyTavern { json ->
                            scope.launch {
                                val file = java.io.File(context.cacheDir, "worldbook_export.json")
                                file.writeText(json)
                                Toast.makeText(context, context.getString(R.string.worldbook_exported_to, file.absolutePath), Toast.LENGTH_LONG).show() // 前端修复 (i18n-8)
                            }
                        }
                    }) { Text(stringResource(R.string.worldbook_export)) }
                },
            )
        },
        floatingActionButton = {
            MuseFloatingButton(
                icon = Icons.Default.Add,
                onClick = {
                    val now = System.currentTimeMillis()
                    editing = WorldBookEntryEntity(
                        id = "wb-${UUID.randomUUID()}",
                        name = newDefaultName,
                        createdAt = now,
                        updatedAt = now,
                    )
                    isNew = true
                },
                contentDescription = stringResource(R.string.worldbook_new_cd),
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
                    text = stringResource(R.string.worldbook_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.entries, key = { it.id }) { entry ->
                WorldBookCard(
                    entry = entry,
                    onEdit = { editing = entry; isNew = false },
                    onDelete = { deleteTarget = entry },
                    onToggleEnabled = {
                        viewModel.save(entry.copy(enabled = !entry.enabled))
                    },
                )
            }
            if (state.entries.isEmpty()) {
                item {
                    MuseEmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = stringResource(R.string.worldbook_empty_title),
                        subtitle = stringResource(R.string.worldbook_empty_subtitle),
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.worldbook_delete_title),
            itemName = target.name,
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun WorldBookCard(
    entry: WorldBookEntryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val unnamedText = stringResource(R.string.worldbook_unnamed)
    val disabledText = stringResource(R.string.worldbook_disabled)
    val enabledStateText = stringResource(R.string.worldbook_enabled_cd)
    val disabledStateText = stringResource(R.string.worldbook_disabled_cd)
    val editCd = stringResource(R.string.worldbook_edit_cd)
    val deleteCd = stringResource(R.string.worldbook_delete_cd)
    val alwaysActiveText = stringResource(R.string.worldbook_badge_always_active)
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.alwaysActive) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = alwaysActiveText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!entry.enabled) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = disabledText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                val kw = WorldBookRepository.parseKeywords(entry.keywordsJson).joinToString(", ")
                if (kw.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.worldbook_keywords_label, kw),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MuseSwitch(
                checked = entry.enabled,
                onCheckedChange = { onToggleEnabled() },
                modifier = Modifier.semantics { stateDescription = stateDesc },
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
private fun WorldBookEditPage(
    initial: WorldBookEntryEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (WorldBookEntryEntity) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    val initialKeywordsText = remember { WorldBookRepository.parseKeywords(initial.keywordsJson).joinToString(", ") }
    var keywordsText by rememberSaveable { mutableStateOf(initialKeywordsText) }
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var priority by rememberSaveable { mutableStateOf(initial.priority.toString()) }
    var caseSensitive by rememberSaveable { mutableStateOf(initial.caseSensitive) }
    var isRegex by rememberSaveable { mutableStateOf(initial.isRegex) }
    var alwaysActive by rememberSaveable { mutableStateOf(initial.alwaysActive) }
    var scanDepth by rememberSaveable { mutableStateOf(initial.scanDepth.toString()) }
    var injectTarget by rememberSaveable { mutableStateOf(initial.injectTarget) }
    var injectPosition by rememberSaveable { mutableStateOf(initial.injectPosition) }
    var insertionDepth by rememberSaveable { mutableStateOf(initial.insertionDepth.toString()) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }

    val unnamedText = stringResource(R.string.worldbook_unnamed)
    val saveText = stringResource(R.string.worldbook_save)
    val newTitleText = stringResource(R.string.worldbook_new_title)
    val editTitleText = stringResource(R.string.worldbook_edit_title)
    val unsavedTitle = stringResource(R.string.worldbook_unsaved_title)
    val unsavedContent = stringResource(R.string.worldbook_unsaved_content)
    val discardText = stringResource(R.string.worldbook_discard)

    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val hasUnsavedChanges = remember(
        name, keywordsText, content, priority, caseSensitive, isRegex, alwaysActive,
        scanDepth, injectTarget, injectPosition, insertionDepth, enabled,
    ) {
        name != initial.name || keywordsText != initialKeywordsText || content != initial.content ||
            priority != initial.priority.toString() ||
            caseSensitive != initial.caseSensitive ||
            isRegex != initial.isRegex ||
            alwaysActive != initial.alwaysActive ||
            scanDepth != initial.scanDepth.toString() ||
            injectTarget != initial.injectTarget ||
            injectPosition != initial.injectPosition ||
            insertionDepth != initial.insertionDepth.toString() ||
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

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = if (isNew) newTitleText else editTitleText,
                onBack = {
                    if (hasUnsavedChanges) showDiscardConfirm = true else onBack()
                },
                actions = {
                    TextButton(onClick = {
                        val keywordsList = if (keywordsText.isBlank()) emptyList()
                        else keywordsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val keywordsJson = WorldBookRepository.encodeKeywords(keywordsList)
                        val saved = initial.copy(
                            name = name.trim().ifBlank { unnamedText },
                            keywordsJson = keywordsJson,
                            content = content,
                            priority = priority.trim().toIntOrNull() ?: 50,
                            caseSensitive = caseSensitive,
                            isRegex = isRegex,
                            alwaysActive = alwaysActive,
                            scanDepth = scanDepth.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3,
                            injectTarget = injectTarget,
                            injectPosition = injectPosition,
                            insertionDepth = insertionDepth.trim().toIntOrNull() ?: 0,
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
                .museBottomBarInsets()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionLabel(stringResource(R.string.worldbook_section_basic))
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.worldbook_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MuseTextField(
                value = keywordsText,
                onValueChange = { keywordsText = it },
                label = { Text(stringResource(R.string.worldbook_field_keywords)) },
                placeholder = { Text(stringResource(R.string.worldbook_field_keywords_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.worldbook_section_content))
            MuseTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.worldbook_field_content)) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.worldbook_section_injection))
            MuseTextField(
                value = priority,
                onValueChange = { priority = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.worldbook_field_priority)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MuseDropdown(
                value = injectTarget,
                onValueChange = { injectTarget = it },
                label = stringResource(R.string.worldbook_field_inject_target),
                options = WorldBookInjectTarget.entries.map { it.storage to it.storage },
            )
            MuseDropdown(
                value = injectPosition,
                onValueChange = { injectPosition = it },
                label = stringResource(R.string.worldbook_field_inject_position),
                options = WorldBookInjectPosition.entries.map { it.storage to it.storage },
            )
            if (injectPosition == WorldBookInjectPosition.AT_DEPTH.storage) {
                MuseTextField(
                    value = insertionDepth,
                    onValueChange = { insertionDepth = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.worldbook_field_insertion_depth)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.worldbook_section_behavior))
            MuseTextField(
                value = scanDepth,
                onValueChange = { scanDepth = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.worldbook_field_scan_depth)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                label = stringResource(R.string.worldbook_field_case_sensitive),
                description = stringResource(R.string.worldbook_field_case_sensitive_desc),
                checked = caseSensitive,
                onCheckedChange = { caseSensitive = it },
            )
            SwitchRow(
                label = stringResource(R.string.worldbook_field_is_regex),
                description = stringResource(R.string.worldbook_field_is_regex_desc),
                checked = isRegex,
                onCheckedChange = { isRegex = it },
            )
            SwitchRow(
                label = stringResource(R.string.worldbook_field_always_active),
                description = stringResource(R.string.worldbook_field_always_active_desc),
                checked = alwaysActive,
                onCheckedChange = { alwaysActive = it },
            )
            SwitchRow(
                label = stringResource(R.string.worldbook_field_enabled),
                description = stringResource(R.string.worldbook_field_enabled_desc),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
