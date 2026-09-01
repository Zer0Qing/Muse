package io.zer0.muse.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceWithCount
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/**
 * v1.0.52 P2-2: 记忆空间切换器(横向滚动 MuseChip 列表)。
 *
 * 显示所有 Space,用户点击切换当前 Space。
 * 切换后 MemoryViewModel.selectSpace 会持久化到 SettingsRepository,
 * 并触发 loadAll 重新按 spaceId 过滤事实列表。
 *
 * 与 [io.zer0.muse.ui.MemoryScreen.ScopeFilterChipRow] 的区别:
 *  - Space 切换器:按场景隔离(工作/生活/学习),与 Scope 正交
 *  - Scope 筛选器:按 Agent 隔离(主助手/子助手)
 */
@Composable
fun SpaceSwitcherRow(
    spaces: List<MemorySpaceEntity>,
    selectedSpaceId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (spaces.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = MusePaddings.screen),
        )
        spaces.forEach { space ->
            MuseChip(
                selected = space.id == selectedSpaceId,
                onClick = { onSelect(space.id) },
                label = space.name,
            )
        }
    }
}

/**
 * v1.0.52 P2-2: 记忆空间管理页面 — Space 的 CRUD 界面。
 *
 * 功能:
 *  - 展示所有 Space 列表(含事实数量)
 *  - 创建新 Space(顶部 + 按钮)
 *  - 重命名 Space(点击编辑图标)
 *  - 删除 Space(默认 Space 不可删除,删除前事实迁回默认)
 */
@Composable
fun MemorySpaceManageScreen(
    onBack: () -> Unit,
    viewModel: MemorySpaceViewModel = koinViewModel(),
) {
    val spaces by viewModel.spacesWithCount.collectAsStateWithLifecycle()
    val operationMessage by viewModel.operationMessage.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MemorySpaceWithCount?>(null) }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_space_manage_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.memory_space_create),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            itemsIndexed(spaces, key = { _, it -> it.id }) { index, space ->
                SpaceRow(
                    space = space,
                    onRename = { renameTarget = space },
                    onDelete = { viewModel.deleteSpace(space.id) },
                    onMoveUp = {
                        if (index > 0) viewModel.reorderSpaces(
                            spaces.toMutableList().apply {
                                add(index - 1, removeAt(index))
                            }.map { it.id },
                        )
                    },
                    onMoveDown = {
                        if (index < spaces.lastIndex) viewModel.reorderSpaces(
                            spaces.toMutableList().apply {
                                add(index + 1, removeAt(index))
                            }.map { it.id },
                        )
                    },
                )
            }
        }

        // 操作反馈
        operationMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.padding(padding),
                action = {
                    TextButton(onClick = viewModel::clearOperationMessage) {
                        Text(stringResource(R.string.common_confirm))
                    }
                },
            ) {
                Text(msg)
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
    }
}

@Composable
private fun SpaceRow(
    space: MemorySpaceWithCount,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.memory_space_fact_count, space.factCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.memory_space_rename),
                )
            }
            IconButton(onClick = onMoveUp) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "上移",
                )
            }
            IconButton(onClick = onMoveDown) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "下移",
                )
            }
            if (space.id != MemorySpaceEntity.DEFAULT_SPACE_ID) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.memory_space_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceCreateDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
private fun SpaceRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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

