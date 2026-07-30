package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.CharacterCardImporter
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 助手列表页 —— iOS / MANUS 风格重写。
 *
 * 设计要点:
 *  - 大标题 MuseTopBar,右侧导入角色卡入口
 *  - 暖白背景,白色圆角卡片分组
 *  - 长期记忆入口独立 CardGroup
 *  - 助手列表 CardGroup:头像 + 名称 + 当前状态绿点 + 系统提示预览 + 更多菜单
 *  - 操作区 CardGroup:新建助手 / 导入 SillyTavern 角色卡
 *  - 所有按压反馈走 color-fade + 轻触觉,无 Material 涟漪
 */
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repo: AssistantRepository = koinInject()
    val scope = rememberCoroutineScope()
    var actionSheetAssistant by remember { mutableStateOf<AssistantEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<AssistantEntity?>(null) }
    val context = LocalContext.current
    var exportTarget by remember { mutableStateOf<AssistantEntity?>(null) }

    // i18n: 预提取字符串资源
    val screenTitle = stringResource(R.string.assistant_screen_title)
    val importCardCd = stringResource(R.string.assistant_import_card_cd)
    val emptyCreateText = stringResource(R.string.assistant_empty_create)
    val unnamedText = stringResource(R.string.assistant_unnamed)
    val currentText = stringResource(R.string.assistant_current)
    val moreCd = stringResource(R.string.assistant_more_cd)
    val newActionText = stringResource(R.string.assistant_new_action)
    val moveUpCd = stringResource(R.string.assistant_move_up_cd)
    val moveUpText = stringResource(R.string.assistant_move_up)
    val moveDownCd = stringResource(R.string.assistant_move_down_cd)
    val moveDownText = stringResource(R.string.assistant_move_down)
    val cloneCd = stringResource(R.string.assistant_clone_cd)
    val cloneText = stringResource(R.string.assistant_clone)
    val exportCardCd = stringResource(R.string.assistant_export_card_cd)
    val exportCardText = stringResource(R.string.assistant_export_card)
    val deleteCd = stringResource(R.string.assistant_delete_cd)
    val deleteText = stringResource(R.string.assistant_delete)
    val closeText = stringResource(R.string.assistant_close)
    val deleteDialogTitle = stringResource(R.string.assistant_delete_title)
    val importSillyTavernCd = stringResource(R.string.assistant_import_sillytavern_cd)
    val importSillyTavernText = stringResource(R.string.assistant_import_sillytavern)
    val toastSillyTavernImported = stringResource(R.string.assistant_toast_sillytavern_imported)
    val toastSillyTavernImportFailed = stringResource(R.string.assistant_toast_sillytavern_import_failed)
    val sectionAssistants = stringResource(R.string.assistant_section_my_assistants)
    val sectionActions = stringResource(R.string.assistant_section_actions)

    // 导出角色卡 launcher(SAF CreateDocument,.muse-assistant 文件)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                runCatching { AssistantCardExporter.export(context, target, uri) }
                    .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_exported, target.name)) }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_export_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // 导入角色卡 launcher(SAF OpenDocument)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { AssistantCardExporter.import(context, repo, it) }
                    .onSuccess { imported ->
                        if (imported != null) {
                            MuseToast.show(context.getString(R.string.assistant_toast_imported, imported.name))
                        } else {
                            MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                        }
                    }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // 导入 SillyTavern 角色卡 launcher
    val importSillyTavernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                CharacterCardImporter.importAuto(context, it)
                    .onSuccess { entity ->
                        val now = System.currentTimeMillis()
                        val saved = entity.copy(
                            id = Uuid.random().toString(),
                            createdAt = now,
                            updatedAt = now,
                        )
                        repo.upsert(saved)
                        MuseToast.show(toastSillyTavernImported.format(saved.name))
                    }
                    .onFailure { MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    fun createNewAssistant() {
        val now = System.currentTimeMillis()
        val newId = "assistant-$now"
        scope.launch {
            repo.upsert(
                AssistantEntity(
                    id = newId,
                    name = context.getString(R.string.assistant_new_default_name),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            onOpenDetail(newId)
        }
    }

    fun cloneAssistant(assistant: AssistantEntity) {
        val now = System.currentTimeMillis()
        val cloneId = "assistant-$now-clone"
        scope.launch {
            repo.upsert(
                assistant.copy(
                    id = cloneId,
                    name = context.getString(R.string.assistant_clone_name, assistant.name),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = screenTitle,
                onBack = onBack,
                largeTitle = true,
                actions = {
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = importCardCd,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
            contentPadding = PaddingValues(vertical = MusePaddings.sectionGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // 助手列表
            if (state.isAssistantsLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.emptyStateGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    CardGroup(
                        title = {
                            Text(sectionAssistants)
                        },
                    ) {
                        if (state.assistants.isEmpty()) {
                            item(
                                onClick = { createNewAssistant() },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = emptyCreateText,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        } else {
                            state.assistants.forEach { assistant ->
                                item(
                                    key = assistant.id,
                                    onClick = { onOpenDetail(assistant.id) },
                                    leadingContent = {
                                        AssistantAvatar(assistant = assistant, avatarSize = 44.dp)
                                    },
                                    headlineContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                                        ) {
                                            Text(
                                                text = assistant.name.ifBlank { unnamedText },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (assistant.id == state.currentAssistant?.id) {
                                                CurrentIndicator(text = currentText)
                                            }
                                        }
                                    },
                                    supportingContent = {
                                        val desc = assistant.summary.ifBlank {
                                            assistant.systemPrompt.take(40).replace("\n", " ")
                                        }
                                        if (desc.isNotEmpty()) {
                                            Text(
                                                text = desc,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        IconButton(
                                            onClick = { actionSheetAssistant = assistant },
                                            modifier = Modifier.size(MuseIconSizes.touchTarget),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = moreCd,
                                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // 操作区:仅在已有助手时显示,避免空状态重复"新建"
                if (state.assistants.isNotEmpty()) {
                    item {
                        CardGroup(
                            title = {
                                Text(sectionActions)
                            },
                        ) {
                            item(
                                onClick = { createNewAssistant() },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = newActionText,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = { ChevronRight() },
                            )
                            item(
                                onClick = {
                                    runCatching {
                                        importSillyTavernLauncher.launch(
                                            arrayOf("image/png", "application/json", "*/*"),
                                        )
                                    }.onFailure {
                                        MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500)
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.Image,
                                        contentDescription = importSillyTavernCd,
                                        modifier = Modifier.size(MuseIconSizes.icon),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = importSillyTavernText,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                trailingContent = { ChevronRight() },
                            )
                        }
                    }
                }
            }
        }
    }

    // 操作菜单(MuseDialog)
    actionSheetAssistant?.let { assistant ->
        val currentIndex = state.assistants.indexOf(assistant)
        val canMoveUp = currentIndex > 0
        val canMoveDown = currentIndex >= 0 && currentIndex < state.assistants.size - 1

        MuseDialog(
            onDismissRequest = { actionSheetAssistant = null },
            title = assistant.name.ifBlank { unnamedText },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 头部头像
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(assistant = assistant, avatarSize = 56.dp)
                    }
                    Spacer(Modifier.size(12.dp))

                    // 上移
                    if (canMoveUp) {
                        ActionMenuRow(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = moveUpCd,
                            text = moveUpText,
                            onClick = {
                                scope.launch {
                                    val upper = state.assistants[currentIndex - 1]
                                    repo.upsert(
                                        assistant.copy(
                                            sortIndex = currentIndex - 1,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    repo.upsert(
                                        upper.copy(
                                            sortIndex = currentIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                actionSheetAssistant = null
                            },
                        )
                    }

                    // 下移
                    if (canMoveDown) {
                        ActionMenuRow(
                            icon = Icons.Filled.ArrowDownward,
                            contentDescription = moveDownCd,
                            text = moveDownText,
                            onClick = {
                                scope.launch {
                                    val lower = state.assistants[currentIndex + 1]
                                    repo.upsert(
                                        assistant.copy(
                                            sortIndex = currentIndex + 1,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    repo.upsert(
                                        lower.copy(
                                            sortIndex = currentIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                actionSheetAssistant = null
                            },
                        )
                    }

                    // 克隆
                    ActionMenuRow(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = cloneCd,
                        text = cloneText,
                        onClick = {
                            cloneAssistant(assistant)
                            actionSheetAssistant = null
                        },
                    )

                    // 导出角色卡
                    ActionMenuRow(
                        icon = Icons.Outlined.FileUpload,
                        contentDescription = exportCardCd,
                        text = exportCardText,
                        onClick = {
                            exportTarget = assistant
                            actionSheetAssistant = null
                            val safeName = assistant.name
                                .ifBlank { "assistant" }
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            exportLauncher.launch("$safeName.muse-assistant")
                        },
                    )

                    // 删除(默认助手不可删除)
                    if (assistant.id != "default") {
                        ActionMenuRow(
                            icon = Icons.Default.Delete,
                            contentDescription = deleteCd,
                            text = deleteText,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                deleteTarget = assistant
                                actionSheetAssistant = null
                            },
                        )
                    }
                }
            },
            onConfirm = null,
            dismissText = closeText,
            onDismiss = { actionSheetAssistant = null },
        )
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = deleteDialogTitle,
            itemName = target.name,
            onConfirm = {
                scope.launch { repo.delete(target.id) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * "当前"状态指示器:绿色小圆点 + 文字,用于标识当前选中助手。
 */
@Composable
private fun CurrentIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 操作菜单行:图标 + 文字,无边框,整行可点。
 */
@Composable
private fun ActionMenuRow(
    icon: ImageVector,
    contentDescription: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .heightIn(min = MuseIconSizes.touchTarget)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

/**
 * 右箭头指示器,与 CardGroup / 列表项风格一致。
 */
@Composable
private fun ChevronRight(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier.size(MuseIconSizes.iconMedium),
        tint = tint,
    )
}
