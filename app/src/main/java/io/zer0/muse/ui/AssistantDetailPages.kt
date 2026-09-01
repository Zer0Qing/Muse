package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import io.zer0.muse.ui.common.form.MuseChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ReasoningLevel
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.AvatarStorage
import io.zer0.muse.data.assistant.CharacterCardExporter
import io.zer0.muse.data.sharing.CharacterSharer
import io.zer0.muse.data.sharing.ShareableAssistant
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.memory.fact.FactDao
import io.zer0.memory.fact.FactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.compose.koinInject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            Column(
                modifier = Modifier
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
        item {
            CardGroup {
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = if (assistant?.modelId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
                modifier = Modifier.padding(horizontal = 0.dp),
            ) {
                item(
                    onClick = onOpenBasic,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Tune) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_basic)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_basic_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenPrompt,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.AutoMirrored.Outlined.Article) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_prompt)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_prompt_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenExtensions,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Extension) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_extensions)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_extensions_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenMemory,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Psychology) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_memory_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenAdvanced,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Science) },
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
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Image) },
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
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.AutoMirrored.Outlined.Article) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_export_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_export_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        // Muse 角色卡分享(系统分享 Intent)
        item {
            CardGroup(
                title = { Text(stringResource(R.string.assistant_detail_share_section)) },
            ) {
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
                        val uri = CharacterSharer.generateCardPng(context, shareable)
                        if (uri != null) {
                            CharacterSharer.shareCard(context, uri)
                        } else {
                            MuseToast.show(
                                context.getString(
                                    R.string.assistant_detail_share_muse_failed,
                                    "card generation"
                                ),
                                3000,
                            )
                        }
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.Outlined.Share) },
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
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(Icons.AutoMirrored.Outlined.Article) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_muse_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_muse_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
    }

    // v1.0.28: 模型选择对话框(与 AssistantBasicPage 共用同一 UI 模式)
    if (showModelPicker) {
        val globalDefaultSelected = stringResource(R.string.assistant_detail_global_default_selected)
        val globalDefaultCard = stringResource(R.string.assistant_detail_use_global_default_card)
        val globalModelName = allModels
            .firstOrNull { it.id == globalSelectedModelId }?.name
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.assistant_detail_global_default)
        MuseDialog(
            onDismissRequest = { showModelPicker = false },
            title = stringResource(R.string.assistant_detail_select_model),
            confirmText = stringResource(R.string.assistant_detail_close),
            onConfirm = { showModelPicker = false },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val currentModelId = assistant?.modelId
                    Surface(
                        color = if (currentModelId == null)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MuseShapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                update { it.copy(modelId = null, providerId = null) }
                                showModelPicker = false
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Text(
                                text = if (currentModelId == null) globalDefaultSelected else globalDefaultCard,
                                fontWeight = FontWeight.Medium,
                                color = if (currentModelId == null)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = globalModelName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (currentModelId == null)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                TextButton(
                                    onClick = {
                                        update { it.copy(modelId = model.id, providerId = provider.id) }
                                        showModelPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (model.id == currentModelId)
                                            stringResource(R.string.assistant_detail_model_selected, model.name)
                                        else model.name,
                                        fontWeight = if (model.id == currentModelId) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** 占位助手实体(加载中时用于渲染头像)。 */
private fun placeholderAssistant() = AssistantEntity(id = "", name = "")

// ──────────────────────────────────────────────────────────────────────────────
// 子页 1: 基础(基础信息 + 模型 + 头像)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 基础子页 — 名称 / 头像 Emoji / 头像图片 / 模型 / 采样参数 / 推理等级 / 流式输出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantBasicPage(
    assistantId: String,
    onBack: () -> Unit,
) {
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
        item {
            CardGroup {
                // 头像 + 选图按钮
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistantAvatar(assistant = a, avatarSize = 56.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.assistant_detail_avatar), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (a.avatarImageUrl.isNotBlank()) stringResource(R.string.assistant_detail_avatar_image_set)
                                    else if (a.avatarEmoji.isNotBlank()) stringResource(R.string.assistant_detail_avatar_emoji)
                                    else stringResource(R.string.assistant_detail_avatar_initial),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            // v1.69: launch 可能抛 ActivityNotFoundException(无相册 App),原 runCatching 静默吞异常
                            TextButton(
                                onClick = {
                                    runCatching { pickAvatarLauncher.launch("image/*") }
                                        .onFailure { MuseToast.show(context.getString(R.string.assistant_detail_no_image_app), 3000) }
                                },
                            ) {
                                Text(stringResource(R.string.assistant_detail_pick_image))
                            }
                            if (a.avatarImageUrl.isNotBlank()) {
                                TextButton(onClick = { update { it.copy(avatarImageUrl = "") } }) {
                                    Text(stringResource(R.string.assistant_detail_clear))
                                }
                            }
                        }
                    },
                )
            }
        }
        // 卡片组 2: 名称与 Emoji
        item {
            CardGroup {
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
        item {
            CardGroup {
                // Phase 6: 模型选择器 UX 改进 — 更大点击区域、前置图标、显示全局模型名称
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = if (a.modelId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                TextButton(onClick = { update { it.copy(temperature = null) } }) { Text(stringResource(R.string.assistant_detail_use_default)) }
                                TextButton(onClick = { update { it.copy(temperature = 0.7f) } }) { Text("0.7") }
                                TextButton(onClick = { update { it.copy(temperature = 1.0f) } }) { Text("1.0") }
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
                        MuseDropdown(
                            value = a.reasoningLevel,
                            onValueChange = { selected -> update { it.copy(reasoningLevel = selected) } },
                            label = stringResource(R.string.assistant_detail_reasoning_level),
                            options = ReasoningLevel.entries.map { it.name to it.name },
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
        item {
            CardGroup {
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
    }

    // Phase 6: 模型选择对话框 UX 改进 — 顶部突出“使用全局默认”卡片,显示全局模型名称
    if (showModelPicker) {
        val globalDefaultSelected = stringResource(R.string.assistant_detail_global_default_selected)
        val globalDefaultCard = stringResource(R.string.assistant_detail_use_global_default_card)
        val globalModelName = allModels
            .firstOrNull { it.id == globalSelectedModelId }?.name
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.assistant_detail_global_default)
        MuseDialog(
            onDismissRequest = { showModelPicker = false },
            title = stringResource(R.string.assistant_detail_select_model),
            confirmText = stringResource(R.string.assistant_detail_close),
            onConfirm = { showModelPicker = false },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val currentModelId = assistant?.modelId
                    // Phase 6: 突出“使用全局默认模型”选项(card 风格 + 全局模型名)
                    Surface(
                        color = if (currentModelId == null)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MuseShapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                update { it.copy(modelId = null) }
                                showModelPicker = false
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (currentModelId == null) globalDefaultSelected else globalDefaultCard,
                                fontWeight = FontWeight.Medium,
                                color = if (currentModelId == null)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = globalModelName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (currentModelId == null)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                TextButton(
                                    onClick = {
                                        update { it.copy(modelId = model.id, providerId = provider.id) }
                                        showModelPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (model.id == currentModelId) stringResource(R.string.assistant_detail_model_selected, model.name) else model.name,
                                        fontWeight = if (model.id == currentModelId) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            },
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
fun AssistantPromptPage(
    assistantId: String,
    onBack: () -> Unit,
) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_prompt), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.systemPrompt,
                            onPersist = { v -> update { it.copy(systemPrompt = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_system_prompt_label)) },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.messageTemplate,
                            onPersist = { v -> update { it.copy(messageTemplate = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_message_template_label)) },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
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
                    TextButton(onClick = {
                        // 全选:选中所有选项
                        onSelectionChange(items.map { itemId(it) })
                    }) {
                        Text(stringResource(R.string.assistant_detail_select_all))
                    }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        // 清空
                        onSelectionChange(emptyList())
                    }) {
                        Text(stringResource(R.string.assistant_detail_clear_all))
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEach { item ->
                        val id = itemId(item)
                        val selected = id in selectedIds
                        MuseChip(
                            selected = selected,
                            onClick = { onToggle(id) },
                            label = itemLabel(item),
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
fun AssistantExtensionsPage(
    assistantId: String,
    onBack: () -> Unit,
) {
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

    var activeType by remember { mutableStateOf<ExtensionType?>(null) }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_extensions), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup {
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
                // v1.133: 知识库绑定
                ExtensionRow(
                    type = ExtensionType.KNOWLEDGE_BASE,
                    count = repo.parseKnowledgeBaseIds(a).size,
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
            // v1.133: 知识库绑定 — 多选 KB,写入 AssistantEntity.knowledgeBaseIdsJson
            ExtensionType.KNOWLEDGE_BASE -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = knowledgeBases,
                selectedIds = repo.parseKnowledgeBaseIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseKnowledgeBaseIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(knowledgeBaseIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(knowledgeBaseIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            null -> {}
        }
    }
}

private fun CardGroupScope.ExtensionRow(
    type: ExtensionType,
    count: Int,
    onClick: () -> Unit,
) {
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
fun AssistantMemoryPage(
    assistantId: String,
    onBack: () -> Unit,
) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)

    // 手动记忆相关状态(用全局 FactDao,即 facts.db 默认池)
    val factDao: FactDao = koinInject()
    val scope = rememberCoroutineScope()
    var showAddFactDialog by remember { mutableStateOf(false) }
    var factInput by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    // 注:FactDao 暂无 observeAll() Flow 接口,此处用 produceState + refreshKey 手动刷新;
    // 增删记忆后递增 refreshKey 触发重新拉取。后续若 DAO 增加 Flow 可改用 collectAsStateWithLifecycle。
    val facts by produceState<List<FactEntity>>(initialValue = emptyList(), refreshKey) {
        value = factDao.getAll()
    }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_memory), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup {
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
            CardGroup(title = { Text(stringResource(R.string.assistant_detail_manual_memory)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_add_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_add_memory_desc)) },
                    trailingContent = { ChevronRight() },
                    onClick = { showAddFactDialog = true },
                )
            }
        }
        // 已存记忆列表(支持删除)
        item {
            CardGroup(title = { Text(stringResource(R.string.assistant_detail_saved_memory_count, facts.size)) }) {
                if (facts.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.assistant_detail_no_memory)) })
                } else {
                    facts.forEach { fact ->
                        item(
                            key = fact.id,
                            headlineContent = {
                                Text(
                                    text = fact.fact,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(fact.createdAt) },
                            trailingContent = {
                                var showDeleteConfirm by remember { mutableStateOf(false) }
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.assistant_detail_delete_cd))
                                }
                                if (showDeleteConfirm) {
                                    ConfirmDeleteDialog(
                                        title = stringResource(R.string.assistant_detail_delete_memory),
                                        itemName = fact.fact,
                                        onConfirm = { showDeleteConfirm = false; scope.launch { factDao.deleteById(fact.id); refreshKey++ } },
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

    // 添加记忆对话框
    if (showAddFactDialog) {
        MuseDialog(
            onDismissRequest = { showAddFactDialog = false },
            title = stringResource(R.string.assistant_detail_add_memory_dialog_title),
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
            onConfirm = {
                if (factInput.isNotBlank()) {
                    scope.launch {
                        val now = java.time.LocalDateTime.now().toString()
                        factDao.insert(
                            FactEntity(
                                fact = factInput.trim(),
                                createdAt = now,
                                lastHitAt = now,
                            )
                        )
                        factInput = ""
                        showAddFactDialog = false
                        refreshKey++
                    }
                }
            },
            dismissText = stringResource(R.string.assistant_detail_cancel),
            onDismiss = {
                showAddFactDialog = false
                factInput = ""
            },
        )
    }
}

