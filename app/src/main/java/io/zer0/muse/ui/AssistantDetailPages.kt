package io.zer0.muse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.AvatarStorage
import io.zer0.muse.data.assistant.CharacterCardExporter
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.sharing.CharacterSharer
import io.zer0.muse.data.sharing.ShareableAssistant
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

// ──────────────────────────────────────────────────────────────────────────────
// 即时保存 helper: 不用 ViewModel,直接读 AssistantRepository
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 收集指定 [assistantId] 对应的 [AssistantEntity],自动跟随仓库变化。
 * 返回 null 表示尚未加载。
 */
@Composable
fun rememberAssistant(assistantId: String): AssistantEntity? {
    val repo: AssistantRepository = koinInject()
    return produceState<AssistantEntity?>(initialValue = null, assistantId) {
        repo.observeAll.map { list -> list.find { it.id == assistantId } }.collect { value = it }
    }.value
}

/**
 * 返回一个更新闭包:调用时读取 DB 最新实体,应用 [transform] 后写回。
 * 用于子页即时保存(每次字段变更都 upsert)。
 */
@Composable
fun rememberAssistantUpdater(assistantId: String): ((AssistantEntity) -> AssistantEntity) -> Unit {
    val repo: AssistantRepository = koinInject()
    val scope = rememberCoroutineScope()
    // 审计修复 (3.4): Mutex 串行化读-改-写。快速连续更新(如温度 slider 拖动)时
    // 各协程按顺序执行 getById → upsert,避免多协程乱序完成、旧结果覆盖新结果。
    val mutex = remember { Mutex() }
    return remember(assistantId) {
        { transform ->
            scope.launch {
                mutex.withLock {
                    val current = repo.getById(assistantId) ?: return@withLock
                    repo.upsert(transform(current))
                }
            }
        }
    }
}

/**
 * 防抖文本框:本地缓存输入,停止输入 400ms 后再写 DB,避免每次按键都 upsert 造成频繁数据库写入。
 *
 * - [value] 为当前持久化值(来自 DB);外部变化会同步到本地草稿。
 * - [onPersist] 在防抖结束后触发,传入的值为经过 [transform] 转换后的结果。
 * - [transform] 用于持久化前的转换(如 `take(2)` 限长、`serializeTagsForEdit` 序列化)。
 */
@Composable
@Suppress("FunctionNaming")
internal fun DebouncedTextField(
    value: String,
    onPersist: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    transform: (String) -> String = { it },
) {
    var draft by remember { mutableStateOf(value) }
    // 外部 value 变化(如 DB 回写)同步到草稿
    LaunchedEffect(value) {
        if (draft != value) draft = value
    }
    // 草稿变化后延迟 400ms 持久化(每次输入重置计时,实现防抖)
    LaunchedEffect(draft) {
        if (draft != value) {
            delay(400)
            onPersist(transform(draft))
        }
    }
    // MEM-02: 退出/转屏前强制 flush — 防抖窗口内离开页面时,最后一次编辑不丢。
    // 用 rememberUpdatedState 读取最新草稿,onDispose 时若仍有未落盘改动则立即写入。
    val latestDraft by rememberUpdatedState(draft)
    val latestValue by rememberUpdatedState(value)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDraft != latestValue) {
                onPersist(transform(latestDraft))
            }
        }
    }
    MuseTextField(
        value = draft,
        onValueChange = { v -> draft = v },
        label = label,
        singleLine = singleLine,
        modifier = modifier,
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 详情聚合页: 头部 + 5 个设置入口
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 助手详情聚合页 — 头像 + 名称 + systemPrompt 摘要 + 5 个子页入口。
 *
 * P1-4 平板适配:[onBack] 改为可空(Expanded 双列模式下右栏无需返回按钮)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDetailPage(
    assistantId: String,
    onBack: (() -> Unit)? = null,
    onOpenBasic: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    val titleDefault = stringResource(R.string.assistant_detail_title_default)
    val title = assistant?.name?.ifBlank { titleDefault } ?: titleDefault

    // v1.0.28: 助手详情页直接展示模型选择入口,避免用户不知道基础页有模型选择
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allModels = remember(providers) { providers.flatMap { it.models } }
    val globalSelectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    var showModelPicker by remember { mutableStateOf(false) }

    // ── SillyTavern 角色卡导出 (PNG/JSON) ──
    // SillyTavern 卡是业内通用格式: PNG tEXt chunk (key="chara") 存 base64 JSON, 或纯 JSON
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF 回调时取出待导出实体 (避免在 launch lambda 内读 assistant 状态导致重组错位)
    var exportPngTarget by remember { mutableStateOf<AssistantEntity?>(null) }
    var exportJsonTarget by remember { mutableStateOf<AssistantEntity?>(null) }

    val toastExported = stringResource(R.string.assistant_toast_sillytavern_exported)
    val toastExportFailed = stringResource(R.string.assistant_toast_sillytavern_export_failed)

    // 从 avatarImageUrl 加载头像 Bitmap; 失败或无头像返回 null (导出器用 1x1 透明 PNG 作载体)
    fun loadAvatarBitmap(a: AssistantEntity): Bitmap? {
        if (a.avatarImageUrl.isBlank()) return null
        return runCatching {
            val file = File(a.avatarImageUrl)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    val exportPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val target = exportPngTarget
        exportPngTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val avatar = loadAvatarBitmap(target)
                CharacterCardExporter.exportToPng(context, target, avatar, uri)
                    .onSuccess { MuseToast.show(toastExported) }
                    .onFailure { MuseToast.show(toastExportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportJsonTarget
        exportJsonTarget = null
        if (uri != null && target != null) {
            scope.launch {
                CharacterCardExporter.exportToJson(context, target, uri)
                    .onSuccess { MuseToast.show(toastExported) }
                    .onFailure { MuseToast.show(toastExportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    SettingsSubPageScaffold(title = title, onBack = onBack) {
        item {
            // 头部: 大头像 + 名称 + systemPrompt 摘要
            // v2.x: 动效补齐 — 头部入场(令牌 animateItem)
            Column(
                modifier = museAnimateItem()
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AssistantAvatar(assistant = assistant ?: placeholderAssistant(), avatarSize = 80.dp)
                Text(
                    text = assistant?.name?.ifBlank { stringResource(R.string.assistant_detail_unnamed) }
                        ?: stringResource(R.string.assistant_detail_loading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (assistant != null) {
                    val desc = assistant.summary.ifBlank {
                        assistant.systemPrompt.take(100).let {
                            it + if (assistant.systemPrompt.length > 100) "..." else ""
                        }
                    }
                    if (desc.isNotBlank()) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // v1.0.28: 模型选择入口 — 在详情页直接展示当前使用的模型,点击可切换
        // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = if (assistant?.modelId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        val a = assistant
                        val globalModelName = allModels
                            .firstOrNull { it.id == globalSelectedModelId }?.name
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.assistant_detail_global_default)
                        val currentModelName = a?.modelId?.let { mid ->
                            allModels.firstOrNull { it.id == mid }?.name ?: mid
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.assistant_detail_exclusive_model),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (currentModelName != null) {
                                Text(
                                    text = currentModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.assistant_detail_using_global_model, globalModelName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        item {
            CardGroup(
                modifier = museAnimateItem().padding(horizontal = 0.dp), // inset-guard: allow（外层列表已提供 screen 边距）
            ) {
                item(
                    onClick = onOpenBasic,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.robot) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_basic)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_basic_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenPrompt,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_prompt)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_prompt_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenExtensions,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.puzzle) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_extensions)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_extensions_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenMemory,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.brain) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_memory_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenAdvanced,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.flask) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_advanced)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_advanced_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        // SillyTavern 角色卡分享 (PNG/JSON 导出)
        // 把当前助手导出为业内通用格式, 可在其他支持 SillyTavern 角色卡的 App 中导入
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = { Text(stringResource(R.string.assistant_detail_share_section)) },
            ) {
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        exportPngTarget = a
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        exportPngLauncher.launch("$safeName.png")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.image) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_export_png)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_export_png_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        exportJsonTarget = a
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        exportJsonLauncher.launch("$safeName.json")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_export_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_export_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        // Muse 角色卡分享(系统分享 Intent)
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = { Text(stringResource(R.string.assistant_detail_share_section)) },
            ) {
                // v2.x: 发送完整角色包(含头像,对方可直接导入) — 最常用入口,排首位
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        scope.launch {
                            val ok = AssistantCardExporter.share(context, a)
                            if (!ok) {
                                MuseToast.show(
                                    context.getString(R.string.assistant_detail_share_muse_failed, "pack"),
                                    3000,
                                )
                            }
                        }
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.share) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_pack)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_pack_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        val shareable = ShareableAssistant(
                            name = a.name.ifBlank { "Assistant" },
                            description = a.systemPrompt.take(200),
                            systemPrompt = a.systemPrompt,
                            temperature = a.temperature ?: 0.8f,
                            topP = a.topP ?: 0.95f,
                            maxTokens = a.maxTokens ?: 2048,
                            emoji = a.avatarEmoji,
                            avatarUrl = a.avatarImageUrl,
                        )
                        val uri = CharacterSharer.generateCardPng(context, shareable)
                        if (uri != null) {
                            CharacterSharer.shareCard(context, uri)
                        } else {
                            MuseToast.show(
                                context.getString(
                                    R.string.assistant_detail_share_muse_failed,
                                    "card generation",
                                ),
                                3000,
                            )
                        }
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.share) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_muse_card)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_muse_card_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        val shareable = ShareableAssistant(
                            name = a.name.ifBlank { "Assistant" },
                            description = a.systemPrompt.take(200),
                            systemPrompt = a.systemPrompt,
                            temperature = a.temperature ?: 0.8f,
                            topP = a.topP ?: 0.95f,
                            maxTokens = a.maxTokens ?: 2048,
                            emoji = a.avatarEmoji,
                        )
                        val json = CharacterSharer.exportToJson(shareable)
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        CharacterSharer.shareJson(context, json, "${safeName}_muse.json")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_muse_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_muse_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
    }

    // v1.0.28: 模型选择对话框(与 AssistantBasicPage 共用同一 UI 模式)
    if (showModelPicker) {
        AssistantModelPickerDialog(
            assistant = assistant,
            providers = providers,
            allModels = allModels,
            globalSelectedModelId = globalSelectedModelId,
            update = update,
            onDismiss = { showModelPicker = false },
        )
    }
}

/** 占位助手实体(加载中时用于渲染头像)。 */
private fun placeholderAssistant() = AssistantEntity(id = "", name = "")

/**
 * CONS-04: 模型选择对话框(助手详情页 / 基础页共用,收敛重复 UI)。
 *
 * 顶部突出"使用全局默认"卡片,下方按 Provider 分组列出模型。
 *
 * @param assistant 当前助手实体(null 表示加载中)
 * @param providers 可用 Provider 列表
 * @param allModels 所有 Provider 的模型扁平列表(用于显示全局默认模型名称)
 * @param globalSelectedModelId 全局当前选中模型 id
 * @param update 写回助手的更新闭包(与 rememberAssistantUpdater 返回类型一致)
 * @param onUseGlobalDefault 点击"使用全局默认"时的更新逻辑(各调用方保留原行为)
 * @param onDismiss 关闭对话框回调
 */
@Composable
private fun AssistantModelPickerDialog(
    assistant: AssistantEntity?,
    providers: List<ProviderConfig>,
    allModels: List<Model>,
    globalSelectedModelId: String?,
    update: ((AssistantEntity) -> AssistantEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    // P3-5: 「使用全局默认」语义收敛到组件内部 — 两处调用点此前分别传
    // copy(modelId=null) 与 copy(modelId=null, providerId=null),漂移导致
    // 一处仍残留旧 providerId;统一清空 modelId+providerId,完全跟随全局默认。
    val useGlobalDefault: () -> Unit = { update { it.copy(modelId = null, providerId = null) } }
    val globalDefaultSelected = stringResource(R.string.assistant_detail_global_default_selected)
    val globalDefaultCard = stringResource(R.string.assistant_detail_use_global_default_card)
    val globalModelName = allModels
        .firstOrNull { it.id == globalSelectedModelId }?.name
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.assistant_detail_global_default)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.assistant_detail_select_model),
        confirmText = stringResource(R.string.assistant_detail_close),
        onConfirm = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentModelId = assistant?.modelId
                Surface(
                    color = if (currentModelId == null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MuseShapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            useGlobalDefault()
                            onDismiss()
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = if (currentModelId == null) globalDefaultSelected else globalDefaultCard,
                            fontWeight = FontWeight.Medium,
                            color = if (currentModelId == null) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = globalModelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentModelId == null) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                providers.forEach { provider ->
                    if (provider.models.isNotEmpty()) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        provider.models.forEach { model ->
                            MuseCapsuleButton(
                                text = if (model.id == currentModelId) {
                                    stringResource(R.string.assistant_detail_model_selected, model.name)
                                } else {
                                    model.name
                                },
                                onClick = {
                                    update { it.copy(modelId = model.id, providerId = provider.id) }
                                    onDismiss()
                                },
                                variant = IosCapsuleButtonVariant.Text,
                            )
                        }
                    }
                }
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 子页 1: 基础(基础信息 + 模型 + 头像)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 基础子页 — 名称 / 头像 Emoji / 头像图片 / 模型 / 采样参数 / 推理等级 / 流式输出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantBasicPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v1.52: 注入 SettingsRepository 获取 providers 列表,用于模型选择器
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // 缓存所有 Provider 的模型扁平列表,避免在列表项内每次重组都 flatMap 创建新 List
    val allModels = remember(providers) { providers.flatMap { it.models } }
    // Phase 6: 全局当前选中模型 id,用于显示全局默认模型名称
    val globalSelectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    // 模型选择对话框
    var showModelPicker by remember { mutableStateOf(false) }

    // 头像图片选择器(从相册选图 → 复制到内部存储 → upsert)
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = AvatarStorage.copyToInternal(context, uri, assistantId)
                if (path != null) {
                    update { it.copy(avatarImageUrl = path) }
                } else {
                    // v1.69: 头像保存失败时告知用户,原仅静默不更新
                    MuseToast.show(context.getString(R.string.assistant_detail_avatar_save_failed), 3000)
                }
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_basic), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        // 卡片组 1: 基础信息
        // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                // 头像 + 选图按钮
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistantAvatar(assistant = a, avatarSize = 56.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.assistant_detail_avatar), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (a.avatarImageUrl.isNotBlank()) {
                                        stringResource(R.string.assistant_detail_avatar_image_set)
                                    } else if (a.avatarEmoji.isNotBlank()) {
                                        stringResource(R.string.assistant_detail_avatar_emoji)
                                    } else {
                                        stringResource(R.string.assistant_detail_avatar_initial)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            // v1.69: launch 可能抛 ActivityNotFoundException(无相册 App),原 runCatching 静默吞异常
                            MuseCapsuleButton(
                                text = stringResource(R.string.assistant_detail_pick_image),
                                onClick = {
                                    runCatching { pickAvatarLauncher.launch("image/*") }
                                        .onFailure { MuseToast.show(context.getString(R.string.assistant_detail_no_image_app), 3000) }
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                            if (a.avatarImageUrl.isNotBlank()) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.assistant_detail_clear),
                                    onClick = { update { it.copy(avatarImageUrl = "") } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                            }
                        }
                    },
                )
            }
        }
        // 卡片组 2: 名称与 Emoji
        // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.name,
                            onPersist = { v -> update { it.copy(name = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.avatarEmoji,
                            onPersist = { v -> update { it.copy(avatarEmoji = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_avatar_emoji_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            transform = { it.take(2) },
                        )
                    },
                )
            }
        }
        // 卡片组 3: 模型与采样参数
        // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                // Phase 6: 模型选择器 UX 改进 — 更大点击区域、前置图标、显示全局模型名称
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = if (a.modelId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        val globalModelName = allModels
                            .firstOrNull { it.id == globalSelectedModelId }?.name
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.assistant_detail_global_default)
                        val currentModelName = a.modelId?.let { mid ->
                            allModels.firstOrNull { it.id == mid }?.name ?: mid
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.assistant_detail_exclusive_model), style = MaterialTheme.typography.bodyMedium)
                            if (currentModelName != null) {
                                Text(
                                    text = currentModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.assistant_detail_using_global_model, globalModelName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = { ChevronRight() },
                )
                // Phase 6: 新建助手提示 — 未选择专属模型时显示引导提示
                if (a.modelId == null) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.assistant_detail_model_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        },
                    )
                }
                item(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    R.string.assistant_detail_temperature,
                                    a.temperature?.let { "%.2f".format(it) }
                                        ?: stringResource(R.string.assistant_detail_default),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MuseSlider(
                                value = a.temperature ?: 1.0f,
                                onValueChange = { v -> update { it.copy(temperature = v) } },
                                valueRange = 0f..2f,
                                steps = 39,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.assistant_detail_use_default),
                                    onClick = { update { it.copy(temperature = null) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                MuseCapsuleButton(
                                    text = "0.7",
                                    onClick = { update { it.copy(temperature = 0.7f) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                MuseCapsuleButton(
                                    text = "1.0",
                                    onClick = { update { it.copy(temperature = 1.0f) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                            }
                        }
                    },
                )
                item(
                    headlineContent = {
                        // v1.0.27: 改用 DebouncedTextField 避免 IME composing 被 update 异步回写打断
                        DebouncedTextField(
                            value = a.topP?.toString() ?: "",
                            onPersist = { v ->
                                val parsed = v.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                                update { it.copy(topP = parsed) }
                            },
                            label = { Text(stringResource(R.string.assistant_detail_top_p_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        // v1.0.27: 改用 DebouncedTextField 避免 IME composing 被 update 异步回写打断
                        DebouncedTextField(
                            value = a.maxTokens?.toString() ?: "",
                            onPersist = { v ->
                                val parsed = v.filter(Char::isDigit).toIntOrNull()
                                update { it.copy(maxTokens = parsed) }
                            },
                            label = { Text(stringResource(R.string.assistant_detail_max_tokens_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.assistant_detail_context_message_count, a.contextMessageSize),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MuseSlider(
                                value = a.contextMessageSize.toFloat(),
                                onValueChange = { v ->
                                    update { it.copy(contextMessageSize = v.toInt().coerceAtLeast(1)) }
                                },
                                valueRange = 0f..50f,
                                valueFormatter = { "${it.toInt()}" },
                            )
                        }
                    },
                )
                item(
                    headlineContent = {
                        // 推理等级下拉
                        // v2.0.1: 显示名本地化（value 保持枚举 name 存储键）。
                        val levelOptions = mutableListOf<Pair<String, String>>()
                        for (level in ReasoningLevel.entries) {
                            levelOptions += level.name to stringResource(reasoningLevelLabelRes(level))
                        }
                        MuseDropdown(
                            value = a.reasoningLevel,
                            onValueChange = { selected -> update { it.copy(reasoningLevel = selected) } },
                            label = stringResource(R.string.assistant_detail_reasoning_level),
                            options = levelOptions,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_stream_output)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_stream_output_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.streamOutput,
                            onCheckedChange = { v -> update { it.copy(streamOutput = v) } },
                        )
                    },
                )
            }
        }
        // 卡片组 4: 简介 + 显示名 + 群聊(v1.0.19 Assistant 字段补齐)
        // v2.x: 动效补齐 — 卡片组入场(令牌 animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.summary,
                            onPersist = { v -> update { it.copy(summary = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_summary)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_summary_hint)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_use_assistant_name)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_use_assistant_name_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.useAssistantName,
                            onCheckedChange = { v -> update { it.copy(useAssistantName = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_allow_group_chat)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_allow_group_chat_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.allowGroupChat,
                            onCheckedChange = { v -> update { it.copy(allowGroupChat = v) } },
                        )
                    },
                )
            }
        }

        // v2.6.2（用户反馈）: 助手级聊天背景 —— 优先级：会话级 > 助手级 > 全局默认。
        item {
            val assistantBackgrounds by settings.assistantChatBackgroundFlow.collectAsStateWithLifecycle(
                initialValue = emptyMap(),
            )
            val assistantBg = assistantBackgrounds[assistantId]
            val bgScope = rememberCoroutineScope()
            val bgContext = LocalContext.current
            val bgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        bgScope.launch {
                            val savedPath = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.zer0.muse.ui.settings.ChatBackgroundStore.saveFromUri(
                                    bgContext,
                                    picked,
                                    io.zer0.muse.ui.settings.ChatBackgroundStore.assistantSlot(assistantId),
                                )
                            }
                            if (savedPath != null) {
                                settings.saveAssistantChatBackground(assistantId, savedPath)
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(bgContext.getString(R.string.chat_background_save_failed))
                            }
                        }
                    }
                }
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.settings_chat_background_assistant_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_chat_background_assistant_subtitle))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (assistantBg != null) {
                                Text(
                                    text = stringResource(R.string.action_clear),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            bgScope.launch {
                                                settings.saveAssistantChatBackground(assistantId, null)
                                                io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(
                                                    bgContext,
                                                    io.zer0.muse.ui.settings.ChatBackgroundStore.assistantSlot(assistantId),
                                                )
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.settings_chat_background_choose),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            bgLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    },
                    modifier =
                    Modifier.clickable {
                        bgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }
    }

    // Phase 6: 模型选择对话框 UX 改进 — 顶部突出“使用全局默认”卡片,显示全局模型名称
    if (showModelPicker) {
        AssistantModelPickerDialog(
            assistant = assistant,
            providers = providers,
            allModels = allModels,
            globalSelectedModelId = globalSelectedModelId,
            update = update,
            onDismiss = { showModelPicker = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 子页 2: 提示词
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 提示词子页 — systemPrompt / messageTemplate / presetMessagesJson。
 */
@Composable
fun AssistantPromptPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_prompt), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.personaDefinition,
                            onPersist = { v -> update { it.copy(personaDefinition = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_persona_definition_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_detail_persona_definition_hint))
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.thinkingChain,
                            onPersist = { v -> update { it.copy(thinkingChain = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_thinking_chain_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_detail_thinking_chain_hint))
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.systemPrompt,
                            onPersist = { v -> update { it.copy(systemPrompt = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_system_prompt_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.messageTemplate,
                            onPersist = { v -> update { it.copy(messageTemplate = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_message_template_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.presetMessagesJson,
                            onPersist = { v -> update { it.copy(presetMessagesJson = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_preset_messages_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        )
                    },
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 子页 3: 扩展(关联资源)
// ──────────────────────────────────────────────────────────────────────────────

private enum class ExtensionType {
    QUICK_MESSAGE,
    LOREBOOK,
    MODE_INJECTION,
    SKILL,
    MCP_SERVER,
    TOOL,
    KNOWLEDGE_BASE,
}

/** v2.4.6: 助手可绑定的知识库作用域条目(KB 整库或其中的文件夹)。 */
private data class KbScopeItem(
    val id: String,
    val label: String,
    val kbId: String,
    val path: String,
)

@Composable
private fun ExtensionType.titleText(): String = stringResource(
    when (this) {
        ExtensionType.QUICK_MESSAGE -> R.string.assistant_detail_ext_quick_message
        ExtensionType.LOREBOOK -> R.string.assistant_detail_ext_lorebook
        ExtensionType.MODE_INJECTION -> R.string.assistant_detail_ext_mode_injection
        ExtensionType.SKILL -> R.string.assistant_detail_ext_skill
        ExtensionType.MCP_SERVER -> R.string.assistant_detail_ext_mcp_server
        ExtensionType.TOOL -> R.string.assistant_detail_ext_tool
        ExtensionType.KNOWLEDGE_BASE -> R.string.assistant_detail_ext_knowledge_base
    },
)

@Composable
private fun <T> rememberFlowList(flow: Flow<List<T>>): List<T> {
    return produceState<List<T>>(initialValue = emptyList(), flow) {
        flow.collect { value = it }
    }.value
}

/**
 * 多选 chips 弹窗 — 用 FlowRow 展示所有候选项,点击即时切换。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> MultiSelectChipsDialog(
    title: String,
    items: List<T>,
    selectedIds: Set<String>,
    itemId: (T) -> String,
    itemLabel: (T) -> String,
    onToggle: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_detail_no_options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            } else {
                // v1.95: 全选 / 清空操作行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.assistant_detail_select_all),
                        onClick = {
                            // 全选:选中所有选项
                            onSelectionChange(items.map { itemId(it) })
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    Spacer(Modifier.size(8.dp))
                    MuseCapsuleButton(
                        text = stringResource(R.string.assistant_detail_clear_all),
                        onClick = {
                            // 清空
                            onSelectionChange(emptyList())
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
                // v1.0.92: 由横向 LazyRow chips 改为竖向列表 — 候选项一多,
                // 横向滚动既看不全也难点(用户实测技能列表溢出屏幕)。
                Column(modifier = Modifier.fillMaxWidth()) {
                    items.forEach { item ->
                        val id = itemId(item)
                        val selected = id in selectedIds
                        MuseListItem(
                            onClick = { onToggle(id) },
                            modifier = Modifier.semantics { this.selected = selected },
                            headlineContent = {
                                Text(
                                    text = itemLabel(item),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = if (selected) MuseIcons.check else MuseIcons.circle,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.assistant_detail_done),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * 扩展子页 — 显示 6 类关联资源,点击行弹出多选 chips 弹窗,切换后即时保存。
 */
@Composable
fun AssistantExtensionsPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val repo: AssistantRepository = koinInject()
    val update = rememberAssistantUpdater(assistantId)

    val qmRepo: QuickMessageRepository = koinInject()
    val loreRepo: LorebookRepository = koinInject()
    val injRepo: PromptInjectionRepository = koinInject()
    val skillRepo: SkillRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val toolRegistry: ToolRegistry = koinInject()

    val quickMessages = rememberFlowList(qmRepo.observeAll())
    val lorebooks = rememberFlowList(loreRepo.observeAll())
    val promptInjections = rememberFlowList(injRepo.observeAll())
    val skills = rememberFlowList(skillRepo.observeAll)
    val mcpServers = rememberFlowList(settings.mcpServersFlow)
    val tools = remember(toolRegistry) { toolRegistry.listTools() }
    // v1.133: 知识库列表(KB 多选用)
    val kbDao: io.zer0.muse.data.knowledge.KnowledgeBaseDao = koinInject()
    val knowledgeBases = rememberFlowList(kbDao.observeAll())
    // v2.4.6: 文件夹列表 — 让助手可绑定到某个文件夹(独立检索域)
    val knowledgeFolderDao: io.zer0.muse.data.knowledge.KnowledgeFolderDao = koinInject()
    val knowledgeFolders = rememberFlowList(knowledgeFolderDao.observeAll())

    var activeType by remember { mutableStateOf<ExtensionType?>(null) }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_extensions), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                ExtensionRow(
                    type = ExtensionType.QUICK_MESSAGE,
                    count = repo.parseQuickMessageIds(a).size,
                    onClick = { activeType = ExtensionType.QUICK_MESSAGE },
                )
                ExtensionRow(
                    type = ExtensionType.LOREBOOK,
                    count = repo.parseLorebookIds(a).size,
                    onClick = { activeType = ExtensionType.LOREBOOK },
                )
                ExtensionRow(
                    type = ExtensionType.MODE_INJECTION,
                    count = repo.parseModeInjectionIds(a).size,
                    onClick = { activeType = ExtensionType.MODE_INJECTION },
                )
                ExtensionRow(
                    type = ExtensionType.SKILL,
                    count = repo.parseSkillIds(a).size,
                    onClick = { activeType = ExtensionType.SKILL },
                )
                ExtensionRow(
                    type = ExtensionType.MCP_SERVER,
                    count = repo.parseMcpServerIds(a).size,
                    onClick = { activeType = ExtensionType.MCP_SERVER },
                )
                ExtensionRow(
                    type = ExtensionType.TOOL,
                    count = repo.parseToolIds(a).size,
                    onClick = { activeType = ExtensionType.TOOL },
                )
                // v1.133/v2.4.6: 知识库/文件夹绑定(作用域条目数)
                ExtensionRow(
                    type = ExtensionType.KNOWLEDGE_BASE,
                    count = repo.parseKnowledgeFolderScopes(a).size
                        .takeIf { it > 0 }
                        ?: repo.parseKnowledgeBaseIds(a).size,
                    onClick = { activeType = ExtensionType.KNOWLEDGE_BASE },
                )
            }
        }
    }

    assistant?.let { a ->
        when (val type = activeType) {
            ExtensionType.QUICK_MESSAGE -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = quickMessages,
                selectedIds = repo.parseQuickMessageIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseQuickMessageIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(quickMessageIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(quickMessageIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.LOREBOOK -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = lorebooks,
                selectedIds = repo.parseLorebookIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseLorebookIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(lorebookIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(lorebookIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.MODE_INJECTION -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = promptInjections,
                selectedIds = repo.parseModeInjectionIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.displayName.takeIf { name -> name.isNotBlank() } ?: it.name },
                onToggle = { id ->
                    val current = repo.parseModeInjectionIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(modeInjectionIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(modeInjectionIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.SKILL -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = skills,
                selectedIds = repo.parseSkillIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseSkillIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(skillIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(skillIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.MCP_SERVER -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = mcpServers,
                selectedIds = repo.parseMcpServerIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseMcpServerIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(mcpServerIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(mcpServerIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.TOOL -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = tools,
                selectedIds = repo.parseToolIds(a).toSet(),
                itemId = { it.name },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseToolIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(toolIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(toolIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            // v2.4.6: 知识库绑定 — 多选「知识库 / 知识库内文件夹」,写入
            // AssistantEntity.knowledgeFolderScopesJson(形如 kbId::path)。
            // 一个文件夹 = 一个独立检索域,互不串。未选任何文件夹时退化为整库绑定。
            ExtensionType.KNOWLEDGE_BASE -> {
                // 展开:每个 KB 一行(整库) + 其下每个文件夹一行(独立域)
                val scopeItems: List<KbScopeItem> = run {
                    val foldersByKb = knowledgeFolders.groupBy { it.kbId }
                    knowledgeBases.flatMap { kb ->
                        val wholeKbLabel = stringResource(R.string.assistant_kb_scope_whole)
                        val folderRows = foldersByKb[kb.id].orEmpty().map { f ->
                            KbScopeItem(
                                id = "${kb.id}::${f.path}",
                                label = "${kb.name} / ${f.path}",
                                kbId = kb.id,
                                path = f.path,
                            )
                        }
                        listOf(
                            KbScopeItem(
                                id = "${kb.id}::",
                                label = "${kb.name}$wholeKbLabel",
                                kbId = kb.id,
                                path = "",
                            ),
                        ) + folderRows
                    }
                }
                val currentScopes = repo.parseKnowledgeFolderScopes(a)
                val currentIds = currentScopes.map { "${it.first}::${it.second}" }.toSet()
                MultiSelectChipsDialog(
                    title = type.titleText(),
                    items = scopeItems,
                    selectedIds = currentIds,
                    itemId = { it.id },
                    itemLabel = { it.label },
                    onToggle = { id ->
                        val updated = if (id in currentIds) currentIds - id else currentIds + id
                        val scopes = scopeItems.filter { it.id in updated }.map { it.kbId to it.path }
                        update {
                            it.copy(
                                knowledgeFolderScopesJson = repo.serializeStringList(repo.encodeKnowledgeFolderScopes(scopes)),
                                // 同步 KB id 列表(兼容旧路径与旧数据读取)
                                knowledgeBaseIdsJson = repo.serializeStringList(scopes.map { s -> s.first }.distinct()),
                            )
                        }
                    },
                    onSelectionChange = { newIds ->
                        val scopes = scopeItems.filter { it.id in newIds }.map { it.kbId to it.path }
                        update {
                            it.copy(
                                knowledgeFolderScopesJson = repo.serializeStringList(repo.encodeKnowledgeFolderScopes(scopes)),
                                knowledgeBaseIdsJson = repo.serializeStringList(scopes.map { s -> s.first }.distinct()),
                            )
                        }
                    },
                    onDismiss = { activeType = null },
                )
            }
            null -> {}
        }
    }
}

private fun CardGroupScope.ExtensionRow(type: ExtensionType, count: Int, onClick: () -> Unit) {
    item(
        onClick = onClick,
        headlineContent = { Text(type.titleText()) },
        supportingContent = { Text(stringResource(R.string.assistant_detail_selected_count, count)) },
        trailingContent = { ChevronRight() },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 子页 4: 记忆
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 记忆子页 — memoryEnabled / useGlobalMemory / enableRecentChatsReference / enableTimeReminder。
 */
@Composable
fun AssistantMemoryPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    // v2.6.2: 预解析提示文案 —— 回调 lambda 中直接用 context.getString 会触发
    // lint LocalContextGetResourceValueCall（应使用 stringResource 在组合期取值）。
    val missingValueText = androidx.compose.ui.res.stringResource(R.string.assistant_detail_memory_edit_missing)

    // P2-9: 手动记忆读写生产侧同源 store — 默认助手(空/"default")→ 全局 facts.db、
    // scope "main";子助手 → FactDbProvider 分库 facts_<id>.db、scope = assistantId。
    // 与 SystemPromptAssembler / ChatViewModel 的读取口径一致,修复此前读写全局池对 AI 无效。
    val factDbProvider: FactDbProvider = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    var showAddFactDialog by remember { mutableStateOf(false) }
    var editingFactId by remember { mutableStateOf<Long?>(null) }
    var factInput by remember { mutableStateOf("") }
    val expandedFactIds = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val memoryScope: String =
        if (assistant == null || assistant.id.isBlank() || assistant.id == "default") "main" else assistant.id
    val memoryStore: FactStore =
        // P3-4（审计报告）: 去掉 !! —— memoryScope != "main" 蕴含 assistant 非空，
        // 但显式兜底比依赖这个隐含推理更安全（后续重构很容易破坏）。
        if (memoryScope == "main") {
            koinInject<FactStore>()
        } else {
            factDbProvider.getFactStore(assistant?.id ?: memoryScope)
        }
    // Pinned memories use the same assistant scope as the prompt injector.
    val pinnedMemoryStore: PinnedMemoryStore = koinInject()
    val includeGlobalPinned = assistant?.useGlobalMemory == true || assistantId == "default"
    val pinnedMemories by produceState(
        initialValue = emptyList<PinnedMemoryStore.PinnedEntry>(),
        refreshKey,
        assistantId,
        includeGlobalPinned,
    ) {
        value =
            resultOf { pinnedMemoryStore.getForAssistant(assistantId, includeGlobal = includeGlobalPinned) }
                .onError { message, error -> Logger.w("AssistantMemoryPage", "读取置顶记忆失败: $message", error) }
                .getOrNull()
                .orEmpty()
    }
    // spaceId 取当前 Space,与生产读取(按 currentSpaceId 过滤)保持同一维度。
    val spaceId by produceState(initialValue = "default", refreshKey) {
        value = settings.currentSpaceIdFlow.first().ifBlank { "default" }
    }
    // 注:此处按 scope+space 读取该 store 下 AI 实际使用的事实;增删后递增 refreshKey 触发重新拉取。
    val facts by produceState<List<FactStore.Fact>>(initialValue = emptyList(), refreshKey, memoryScope, spaceId) {
        value =
            resultOf { memoryStore.getByScopeAndSpace(memoryScope, spaceId) }
                .onError { message, error -> Logger.w("AssistantMemoryPage", "读取助手记忆失败: $message", error) }
                .getOrNull()
                .orEmpty()
    }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_memory), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_enable_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_enable_memory_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.memoryEnabled,
                            onCheckedChange = { v -> update { it.copy(memoryEnabled = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_use_global_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_use_global_memory_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.useGlobalMemory,
                            onCheckedChange = { v -> update { it.copy(useGlobalMemory = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_recent_chats_reference)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_recent_chats_reference_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.enableRecentChatsReference,
                            onCheckedChange = { v -> update { it.copy(enableRecentChatsReference = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_time_reminder)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_time_reminder_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.enableTimeReminder,
                            onCheckedChange = { v -> update { it.copy(enableTimeReminder = v) } },
                        )
                    },
                )
            }
        }
        // 手动记忆入口
        item {
            CardGroup(modifier = museAnimateItem(), title = { Text(stringResource(R.string.assistant_detail_manual_memory)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_add_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_add_memory_desc)) },
                    trailingContent = { ChevronRight() },
                    onClick = { showAddFactDialog = true },
                )
            }
        }
        if (pinnedMemories.isNotEmpty()) {
            item {
                CardGroup(
                    modifier = museAnimateItem(),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.assistant_detail_global_pinned_count, pinnedMemories.size))
                            Text(
                                text = stringResource(
                                    if (a.useGlobalMemory) {
                                        R.string.assistant_detail_global_pinned_enabled
                                    } else {
                                        R.string.assistant_detail_global_pinned_disabled
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    pinnedMemories.forEach { entry ->
                        item(
                            key = entry.id,
                            headlineContent = {
                                Text(entry.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(entry.createdAt) },
                        )
                    }
                }
            }
        }
        // 已存记忆列表(支持展开、长按编辑和删除)
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.assistant_detail_saved_memory_count, facts.size))
                        if (facts.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.assistant_detail_saved_memory_interaction_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            ) {
                if (facts.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.assistant_detail_no_memory)) })
                } else {
                    facts.forEach { fact ->
                        item(
                            key = fact.id,
                            onClick = {
                                if (expandedFactIds[fact.id] == true) {
                                    expandedFactIds.remove(fact.id)
                                } else {
                                    expandedFactIds[fact.id] = true
                                }
                            },
                            onLongClick = {
                                factInput = fact.fact
                                editingFactId = fact.id
                            },
                            headlineContent = {
                                val expanded = expandedFactIds[fact.id] == true
                                Text(
                                    text = fact.fact,
                                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(fact.createdAt) },
                            trailingContent = {
                                var showDeleteConfirm by remember { mutableStateOf(false) }
                                MuseTactileButton(
                                    icon = MuseIcons.trash,
                                    onClick = { showDeleteConfirm = true },
                                    contentDescription = stringResource(R.string.assistant_detail_delete_cd),
                                )
                                if (showDeleteConfirm) {
                                    ConfirmDeleteDialog(
                                        title = stringResource(R.string.assistant_detail_delete_memory),
                                        itemName = fact.fact,
                                        // MEM-04: 统一句式 + 后果说明(该助手不再引用这条记忆)
                                        consequence = stringResource(R.string.assistant_memory_delete_consequence),
                                        onConfirm = {
                                            showDeleteConfirm = false
                                            scope.launch {
                                                val deleted =
                                                    resultOf {
                                                        memoryStore.delete(
                                                            id = fact.id,
                                                            assistantId = if (memoryScope == "main") "default" else memoryScope,
                                                            scope = memoryScope,
                                                            spaceId = spaceId,
                                                        )
                                                    }
                                                        .onError { message, error ->
                                                            Logger.w("AssistantMemoryPage", "删除助手记忆失败: $message", error)
                                                        }
                                                        .getOrNull() == true
                                                if (deleted) expandedFactIds.remove(fact.id)
                                                refreshKey++
                                            }
                                        },
                                        onDismiss = { showDeleteConfirm = false },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 手动新增/长按编辑共用对话框。
    if (showAddFactDialog || editingFactId != null) {
        val targetFactId = editingFactId
        val isEditing = targetFactId != null
        fun dismissFactEditor() {
            showAddFactDialog = false
            editingFactId = null
            factInput = ""
        }
        MuseDialog(
            onDismissRequest = ::dismissFactEditor,
            title = stringResource(
                if (isEditing) {
                    R.string.assistant_detail_edit_memory_dialog_title
                } else {
                    R.string.assistant_detail_add_memory_dialog_title
                },
            ),
            content = {
                MuseTextField(
                    value = factInput,
                    onValueChange = { factInput = it },
                    label = { Text(stringResource(R.string.assistant_detail_input_fact)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            },
            confirmText = stringResource(R.string.assistant_detail_save),
            // MEM-07: 空内容禁用保存按钮
            confirmEnabled = factInput.isNotBlank(),
            onConfirm = {
                val content = factInput.trim()
                scope.launch {
                    // Keep the same assistant/scope store that prompt assembly reads from.
                    val result =
                        resultOf {
                            if (targetFactId == null) {
                                memoryStore.add(FactStore.Fact(fact = content), scope = memoryScope, spaceId = spaceId)
                                true
                            } else {
                                memoryStore.update(id = targetFactId, content = content, scope = memoryScope)
                            }
                        }
                    val saved = result.getOrNull() ?: false
                    if (saved) {
                        targetFactId?.let { expandedFactIds[it] = true }
                        refreshKey++
                        dismissFactEditor()
                    } else if (result.isError) {
                        val message = (result as? Result.Error)?.let { it.throwable?.message ?: it.message }.orEmpty()
                        MuseToast.show(
                            context.getString(
                                if (targetFactId == null) R.string.memory_add_failed else R.string.memory_edit_failed,
                                message.ifBlank { missingValueText },
                            ),
                        )
                    } else {
                        MuseToast.show(missingValueText)
                    }
                }
            },
            dismissText = stringResource(R.string.assistant_detail_cancel),
            onDismiss = ::dismissFactEditor,
        )
    }
}

/** v2.0.1: 推理等级本地化显示名（value 匹配仍用枚举 name）。 */
@androidx.annotation.StringRes
private fun reasoningLevelLabelRes(level: ReasoningLevel): Int = when (level) {
    ReasoningLevel.OFF -> R.string.reasoning_level_off
    ReasoningLevel.AUTO -> R.string.reasoning_level_auto
    ReasoningLevel.LOW -> R.string.reasoning_level_low
    ReasoningLevel.MEDIUM -> R.string.reasoning_level_medium
    ReasoningLevel.HIGH -> R.string.reasoning_level_high
    ReasoningLevel.XHIGH -> R.string.reasoning_level_xhigh
}
