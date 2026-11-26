package io.zer0.muse.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceWithCount
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import org.koin.androidx.compose.koinViewModel

/**
 * 记忆空间切换器（横向滚动 MuseChip 列表）。
 */
@Composable
fun SpaceSwitcherRow(spaces: List<MemorySpaceEntity>, selectedSpaceId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (spaces.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MuseIcons.folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = MusePaddings.screen),
        )
        spaces.forEach { space ->
            MuseChip(
                selected = space.id == selectedSpaceId,
                onClick = { onSelect(space.id) },
                label = memorySpaceDisplayName(space.name, stringResource(R.string.memory_space_default_name)),
            )
        }
    }
}

/**
 * 记忆空间管理页面 — Space 的 CRUD 界面（分组卡片 + 锚定菜单 + 删除确认）。
 */
@Composable
fun MemorySpaceManageScreen(onBack: () -> Unit, viewModel: MemorySpaceViewModel = koinViewModel()) {
    val spaces by viewModel.spacesWithCount.collectAsStateWithLifecycle()
    val operationMessage by viewModel.operationMessage.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MemorySpaceWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<MemorySpaceWithCount?>(null) }
    val defaultSpaceLabel = stringResource(R.string.memory_space_default_name)

    SettingsSubPageScaffold(
        title = stringResource(R.string.memory_space_manage_title),
        onBack = onBack,
        headerActions = {
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = { showCreateDialog = true },
                contentDescription = stringResource(R.string.memory_space_create),
                tint = MaterialTheme.colorScheme.primary,
                compact = true,
            )
        },
    ) {
        operationMessage?.let { msg ->
            item(key = "space_operation_message") {
                MuseSurface(
                    shape = MuseShapes.semiLarge,
                    color = MuseActionColors.neutralContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        modifier = Modifier.padding(
                            start = MusePaddings.itemGap,
                            end = MusePaddings.tightGap,
                            top = MusePaddings.tightGap,
                            bottom = MusePaddings.tightGap,
                        ),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MuseActionColors.neutralContent,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.common_confirm),
                            onClick = viewModel::clearOperationMessage,
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }
            }
        }

        if (spaces.isNotEmpty()) {
            item(key = "space_list_card") {
                CardGroup {
                    spaces.forEachIndexed { index, space ->
                        val displayName = memorySpaceDisplayName(space.name, defaultSpaceLabel)
                        val countSub = stringResource(R.string.memory_space_fact_count, space.factCount)
                        item(
                            key = space.id,
                            onClick = { renameTarget = space },
                            headlineContent = { Text(displayName) },
                            supportingContent = { Text(countSub) },
                            trailingContent = {
                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    MuseTactileButton(
                                        icon = MuseIcons.moreHorizontal,
                                        onClick = { menuExpanded = true },
                                        contentDescription = stringResource(R.string.settings_mcp_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        compact = true,
                                    )
                                    MuseAnchoredMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        alignEnd = true,
                                    ) {
                                        MuseListItem(
                                            onClick = {
                                                menuExpanded = false
                                                renameTarget = space
                                            },
                                            leadingContent = {
                                                Icon(
                                                    imageVector = MuseIcons.edit,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                )
                                            },
                                            headlineContent = { Text(stringResource(R.string.memory_space_rename)) },
                                        )
                                        if (index > 0) {
                                            MuseListItem(
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.reorderSpaces(
                                                        spaces.toMutableList().apply {
                                                            add(index - 1, removeAt(index))
                                                        }.map { it.id },
                                                    )
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = MuseIcons.arrowUp,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(stringResource(R.string.prompt_template_manager_move_up))
                                                },
                                            )
                                        }
                                        if (index < spaces.lastIndex) {
                                            MuseListItem(
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.reorderSpaces(
                                                        spaces.toMutableList().apply {
                                                            add(index + 1, removeAt(index))
                                                        }.map { it.id },
                                                    )
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = MuseIcons.arrowDown,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(stringResource(R.string.prompt_template_manager_move_down))
                                                },
                                            )
                                        }
                                        if (space.id != MemorySpaceEntity.DEFAULT_SPACE_ID) {
                                            MuseListItem(
                                                onClick = {
                                                    menuExpanded = false
                                                    deleteTarget = space
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = MuseIcons.trash,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(
                                                        text = stringResource(R.string.common_delete),
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SpaceCreateDialog(
            onConfirm = { name ->
                viewModel.createSpace(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    renameTarget?.let { target ->
        SpaceRenameDialog(
            initialName = target.name,
            onConfirm = { newName ->
                viewModel.renameSpace(target.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
            itemName = memorySpaceDisplayName(target.name, defaultSpaceLabel),
            // 删除 Space 不是「彻底删除」:事实与摘要会合并到默认空间保留,只有整理回滚点失效。
            // 不给 consequence 的话这里会退回通用文案「此操作不可撤销」,把可挽回说成不可挽回。
            consequence = stringResource(R.string.audit_memory_space_delete_consequence),
            onConfirm = {
                viewModel.deleteSpace(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SpaceCreateDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_space_create_title),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.memory_space_name_label)) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun SpaceRenameDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_space_rename_title),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.memory_space_name_label)) },
                singleLine = true,
            )
        },
    )
}
