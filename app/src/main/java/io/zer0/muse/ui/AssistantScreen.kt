package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantCardImportBridge
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.CharacterCardImporter
import io.zer0.muse.data.assistant.PersonaDiff
import io.zer0.muse.data.assistant.PersonaFieldChange
import io.zer0.muse.ui.common.animation.museSharedElement
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 助手列表页 —— 全量重写。
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
fun AssistantScreen(onBack: () -> Unit, onOpenDetail: (String) -> Unit, viewModel: ChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repo: AssistantRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val defaultAssistantId by settings.defaultAssistantIdFlow.collectAsStateWithLifecycle(initialValue = "default")
    // 前端修复 (持久化-2): Boolean 弹窗态改 rememberSaveable
    var showDefaultAssistantPicker by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 前端修复 (持久化-2): AssistantEntity 无法直接 saveable,改为存 id(String) 再查找
    var actionSheetAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var exportTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    // v2.x: 待确认导入的角色卡(解析完成,用户确认后才写入)
    var pendingImportCard by remember { mutableStateOf<AssistantCardExporter.ParsedCard?>(null) }

    // 前端修复 (持久化-2): 由保存的 id 反查实体(列表来自 ViewModel state,进程重建后仍可恢复;
    // id 找不到时反查为 null,弹窗自然不显示,安全降级)
    val actionSheetAssistant = actionSheetAssistantId?.let { id -> state.assistants.find { it.id == id } }
    val deleteTarget = deleteTargetId?.let { id -> state.assistants.find { it.id == id } }
    val exportTarget = exportTargetId?.let { id -> state.assistants.find { it.id == id } }

    // i18n: 预提取字符串资源
    val screenTitle = stringResource(R.string.assistant_screen_title)
    val importCardCd = stringResource(R.string.assistant_import_card_cd)
    val importPreviewTitle = stringResource(R.string.assistant_import_preview_title)
    val importPreviewConfirm = stringResource(R.string.assistant_import_preview_confirm)
    val emptyCreateText = stringResource(R.string.assistant_empty_create)
    val unnamedText = stringResource(R.string.assistant_unnamed)
    val currentText = stringResource(R.string.assistant_current)
    val moreCd = stringResource(R.string.assistant_more_cd)
    val newActionText = stringResource(R.string.assistant_new_action)
    val moveUpText = stringResource(R.string.assistant_move_up)
    val moveDownText = stringResource(R.string.assistant_move_down)
    val cloneText = stringResource(R.string.assistant_clone)
    val exportCardText = stringResource(R.string.assistant_export_card)
    val deleteText = stringResource(R.string.assistant_delete)
    val closeText = stringResource(R.string.assistant_close)
    val deleteDialogTitle = stringResource(R.string.assistant_delete_title)
    val importSillyTavernCd = stringResource(R.string.assistant_import_sillytavern_cd)
    val importSillyTavernText = stringResource(R.string.assistant_import_sillytavern)
    val toastSillyTavernImported = stringResource(R.string.assistant_toast_sillytavern_imported)
    val toastSillyTavernImportFailed = stringResource(R.string.assistant_toast_sillytavern_import_failed)
    val sectionAssistants = stringResource(R.string.assistant_section_my_assistants)
    val sectionActions = stringResource(R.string.assistant_section_actions)
    val defaultAssistantTitle = stringResource(R.string.assistant_default_title)
    val defaultAssistantPickerTitle = stringResource(R.string.assistant_default_picker_title)
    val defaultAssistantHint = stringResource(R.string.assistant_default_hint)

    // 导出角色卡 launcher(SAF CreateDocument,.muse-assistant 文件)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportTarget
        exportTargetId = null
        if (uri != null && target != null) {
            scope.launch {
                runCatching { AssistantCardExporter.export(context, target, uri) }
                    .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_exported, target.name)) }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_export_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // 导入角色卡 launcher(SAF OpenDocument) — v2.x: 先解析再弹预览确认,确认后才写入
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { AssistantCardExporter.parse(context, it) }
                    .onSuccess { parsed ->
                        if (parsed != null) {
                            pendingImportCard = parsed
                        } else {
                            MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                        }
                    }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // v2.x: 外部点开角色包文件(.muse-assistant)→ 解析并弹预览确认
    val pendingExternalCardUri by AssistantCardImportBridge.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalCardUri) {
        val uri = pendingExternalCardUri ?: return@LaunchedEffect
        AssistantCardImportBridge.consume()
        runCatching { AssistantCardExporter.parse(context, uri) }
            .onSuccess { parsed ->
                if (parsed != null) {
                    pendingImportCard = parsed
                } else {
                    MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                }
            }
            .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
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
                    // v1.0.51: 新建助手预填默认 prompt(从 assets 加载,带 locale 回落)
                    systemPrompt = repo.loadDefaultPrompt(),
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

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = screenTitle,
                onBack = onBack,
                largeTitle = true,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        contentDescription = importCardCd,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconMedium,
                    )
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
                // v2.x: 动效补齐 — 骨架屏入场(令牌 animateItem)
                item {
                    Box(museAnimateItem()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        ) {
                            repeat(6) {
                                io.zer0.muse.ui.common.surface.AvatarRowSkeleton()
                            }
                        }
                    }
                }
            } else {
                // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
                item {
                    Box(museAnimateItem()) {
                        CardGroup(
                            title = {
                                Text(defaultAssistantTitle)
                            },
                        ) {
                            item(
                                onClick = { showDefaultAssistantPicker = true },
                                leadingContent = {
                                    Icon(
                                        imageVector = MuseIcons.brain,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(defaultAssistantTitle)
                                },
                                supportingContent = {
                                    val currentDefault = state.assistants.firstOrNull { it.id == defaultAssistantId }
                                    Text(currentDefault?.name?.ifBlank { null } ?: defaultAssistantTitle)
                                },
                                trailingContent = { ChevronRight() },
                            )
                        }
                    }
                }

                // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
                item {
                    Box(museAnimateItem()) {
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
                                            imageVector = MuseIcons.plus,
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
                                        onLongClick = { actionSheetAssistantId = assistant.id },
                                        leadingContent = {
                                            // F2 链路 A：与助手详情页头 80dp 头像同源共享
                                            AssistantAvatar(
                                                assistant = assistant,
                                                avatarSize = 44.dp,
                                                modifier = Modifier.museSharedElement(key = "assistant-avatar-${assistant.id}"),
                                            )
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
                                            MuseTactileButton(
                                                icon = MuseIcons.moreVertical,
                                                onClick = { actionSheetAssistantId = assistant.id },
                                                contentDescription = moreCd,
                                                size = MuseIconSizes.touchTarget,
                                                iconSize = MuseIconSizes.iconMedium,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 操作区:仅在已有助手时显示,避免空状态重复"新建"
                // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
                if (state.assistants.isNotEmpty()) {
                    item {
                        Box(museAnimateItem()) {
                            CardGroup(
                                title = {
                                    Text(sectionActions)
                                },
                            ) {
                                item(
                                    onClick = { createNewAssistant() },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.plus,
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
                                            imageVector = MuseIcons.image,
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
    }

    if (showDefaultAssistantPicker) {
        MuseDialog(
            onDismissRequest = { showDefaultAssistantPicker = false },
            title = defaultAssistantPickerTitle,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.assistants.isEmpty()) {
                        Text(
                            text = defaultAssistantHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        state.assistants.forEach { assistant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showDefaultAssistantPicker = false
                                        scope.launch { settings.saveDefaultAssistantId(assistant.id) }
                                    }
                                    .padding(vertical = MusePaddings.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AssistantAvatar(assistant = assistant, avatarSize = 36.dp)
                                Spacer(Modifier.width(MusePaddings.contentGap))
                                Text(
                                    text = assistant.name.ifBlank { unnamedText },
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (assistant.id == defaultAssistantId) {
                                    Text(
                                        text = currentText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmText = closeText,
            onConfirm = { showDefaultAssistantPicker = false },
            onDismiss = { showDefaultAssistantPicker = false },
        )
    }

    // 操作菜单
    actionSheetAssistant?.let { assistant ->
        val currentIndex = state.assistants.indexOf(assistant)
        val canMoveUp = currentIndex > 0
        val canMoveDown = currentIndex >= 0 && currentIndex < state.assistants.size - 1

        // 与任务列表/文件夹/气泡长按同一个件:同一个"行操作菜单"不该有两种弹法、两种动画、两种宽度。
        MuseBottomSheet(
            onDismissRequest = { actionSheetAssistantId = null },
            maxHeightFraction = 0.6f,
        ) {
            Text(
                text = assistant.name.ifBlank { unnamedText },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                MuseActionSheetRow(
                    icon = MuseIcons.arrowUp,
                    text = moveUpText,
                    onClick = {
                        scope.launch {
                            val upper = state.assistants[currentIndex - 1]
                            // P2-7: 交换真实 sortIndex,而非把列表下标当 sortIndex 写回(≥3 项会错乱)
                            repo.upsert(
                                assistant.copy(
                                    sortIndex = upper.sortIndex,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            repo.upsert(
                                upper.copy(
                                    sortIndex = assistant.sortIndex,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        actionSheetAssistantId = null
                    },
                )
            }

            // 下移
            if (canMoveDown) {
                MuseActionSheetRow(
                    icon = MuseIcons.arrowDown,
                    text = moveDownText,
                    onClick = {
                        scope.launch {
                            val lower = state.assistants[currentIndex + 1]
                            // P2-7: 交换真实 sortIndex,而非把列表下标当 sortIndex 写回
                            repo.upsert(
                                assistant.copy(
                                    sortIndex = lower.sortIndex,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            repo.upsert(
                                lower.copy(
                                    sortIndex = assistant.sortIndex,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        actionSheetAssistantId = null
                    },
                )
            }

            // 克隆
            MuseActionSheetRow(
                icon = MuseIcons.copy,
                text = cloneText,
                onClick = {
                    cloneAssistant(assistant)
                    actionSheetAssistantId = null
                },
            )

            // U-26: 启用/停用(停用后从候选列表隐藏,不影响既有会话)
            MuseActionSheetRow(
                icon = if (assistant.enabled) MuseIcons.eyeOff else MuseIcons.eye,
                text = stringResource(
                    if (assistant.enabled) R.string.assistant_disable else R.string.assistant_enable,
                ),
                onClick = {
                    scope.launch { repo.setEnabled(assistant.id, !assistant.enabled) }
                    actionSheetAssistantId = null
                },
            )

            // 导出角色卡
            MuseActionSheetRow(
                icon = MuseIcons.upload,
                text = exportCardText,
                onClick = {
                    exportTargetId = assistant.id
                    actionSheetAssistantId = null
                    val safeName = assistant.name
                        .ifBlank { "assistant" }
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    exportLauncher.launch("$safeName.muse-assistant")
                },
            )

            // 删除（v2.6.2: 自带/默认助手也可删 —— 底层已有「自动把默认指针切给另一个助手」保护，
            // 仅当只剩一个助手时才拦下。旧实现用 `id != "default"` 把入口藏了，用户无法删除自带助手。）
            MuseActionSheetRow(
                icon = MuseIcons.trash,
                text = deleteText,
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    deleteTargetId = assistant.id
                    actionSheetAssistantId = null
                },
            )
        }
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = deleteDialogTitle,
            itemName = target.name,
            // MEM-04: 点名助手 + 说明后果(其独立记忆库会被删除;已属于它的对话保留但失去绑定)
            consequence = stringResource(R.string.assistant_delete_consequence),
            onConfirm = {
                // v2.x（用户反馈）: 默认助手也可删（条件保护）——
                // ① 至少保留两个助手；
                // ② 删除的是默认助手（id=default 或默认指针指向的）时，
                //    自动把默认指针切到另一个助手，避免"无默认可用"。
                scope.launch {
                    val all = repo.getAll()
                    if (all.size < 2) {
                        MuseToast.show(
                            context.getString(R.string.err_chat_assistant_delete_last),
                        )
                        return@launch
                    }
                    val currentDefaultId = settings.getDefaultAssistantId()
                    if (target.id == currentDefaultId || target.id == "default") {
                        val successor = all.firstOrNull { it.id != target.id }
                        if (successor != null) {
                            settings.saveDefaultAssistantId(successor.id)
                        }
                    }
                    repo.delete(target.id)
                }
                deleteTargetId = null
            },
            onDismiss = { deleteTargetId = null },
        )
    }

    // v2.x: 导入预览确认对话框(解析完成后弹出,确认才写入)
    // E3: 预览内容从「一张名片的样子」换成「将改哪些项」的差分,并明说不会切换助手。
    pendingImportCard?.let { card ->
        // 同名助手已存在时,差分是拿它做基准的(用户最关心的就是"会不会覆盖我在用的那份")。
        val sameName = state.assistants.firstOrNull { it.name == card.persona.name }
        val changes: List<PersonaFieldChange> = if (sameName == null) {
            emptyList()
        } else {
            PersonaDiff.changes(sameName, card.persona)
        }
        MuseDialog(
            onDismissRequest = { pendingImportCard = null },
            title = importPreviewTitle,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AssistantAvatar(assistant = card.entity, avatarSize = 72.dp)
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = card.entity.name.ifBlank { unnamedText },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(MusePaddings.auxGap))
                    // 差分头部:新建 / 有改动 / 无改动 三种说法各不相同,
                    // 不给一个"看起来都一样"的通用文案。
                    Text(
                        text = if (sameName == null) {
                            context.getString(R.string.assistant_diff_will_create)
                        } else {
                            context.getString(R.string.assistant_diff_will_change, changes.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (card.legacy) {
                        Spacer(Modifier.height(MusePaddings.auxGap))
                        Text(
                            text = context.getString(R.string.assistant_diff_legacy_pack),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(MusePaddings.auxGap))
                    Text(
                        text = context.getString(R.string.assistant_diff_no_switch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    changes.take(8).forEach { change ->
                        Spacer(Modifier.height(MusePaddings.tightGap))
                        Text(
                            text = stringResource(change.labelRes) + ": " + change.from + " → " + change.to,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (changes.size > 8) {
                        Text(
                            text = context.getString(R.string.assistant_diff_more, changes.size - 8),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            },
            confirmText = importPreviewConfirm,
            onConfirm = {
                pendingImportCard?.let { target ->
                    pendingImportCard = null
                    scope.launch {
                        runCatching { AssistantCardExporter.commitParsed(context, repo, target) }
                            .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_imported, it.name)) }
                            .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
                    }
                }
            },
            onDismiss = { pendingImportCard = null },
        )
    }
}

/**
 * "当前"状态指示器:绿色小圆点 + 文字,用于标识当前选中助手。
 */
@Composable
private fun CurrentIndicator(text: String, modifier: Modifier = Modifier) {
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
 * 右箭头指示器,与 CardGroup / 列表项风格一致。
 */
@Composable
private fun ChevronRight(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        imageVector = MuseIcons.chevronRight,
        contentDescription = null,
        modifier = modifier.size(MuseIconSizes.iconMedium),
        tint = tint,
    )
}
