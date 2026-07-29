package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.IosSlider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSegmentedRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.31: 聊天二级设置页 — 给用户更多控制权。
 *
 * 把聊天相关开关从外观页拆出,单独成页,组织为 4 个分组:
 *  1. 消息显示 — MOOD/思考/token/模型名/时间戳
 *  2. 默认展开状态 — MOOD/思考默认展开
 *  3. 交互行为 — 流式/自动滚动/音量键/回车发送/haptic
 *  4. 高级 — 长消息阈值/工具调用详情/24 小时制
 *
 * 所有开关打包到 [ChatPreferences] 数据类里一次序列化存储。
 * MessageBubble / ChatScreen / InputBar 读取这些开关决定渲染与交互行为。
 *
 * v1.0.20: 新增"工具调用批准"分组(置顶),含三档会话权限模式 + 单工具策略管理入口。
 */
@Composable
fun ChatSettingsPage(
    onBack: () -> Unit,
    /** v1.0.20: 打开工具批准管理页(单工具策略)。 */
    onOpenToolsSettings: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = ChatPreferences())
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        scope.launch { settings.saveChatPreferences(block(prefs)) }
    }

    // v1.0.20: 全局默认会话权限模式(三档:TRUSTED / ASK / STRICT)
    val permissionMode by settings.defaultSessionPermissionModeFlow
        .collectAsStateWithLifecycle(initialValue = SessionPermissionMode.ASK)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_chat_title), onBack = onBack) {
        // ── v1.0.20: 工具调用批准(置顶,用户最关心的安全开关)──
        item { SectionLabel(stringResource(R.string.settings_chat_tool_approval_section)) }
        item {
            SettingsGroup {
                // 三档会话权限模式分段选择
                val modeOptions = listOf(
                    stringResource(R.string.settings_chat_tool_approval_trusted),
                    stringResource(R.string.settings_chat_tool_approval_ask),
                    stringResource(R.string.settings_chat_tool_approval_strict),
                )
                val modeIndex = when (permissionMode) {
                    SessionPermissionMode.TRUSTED -> 0
                    SessionPermissionMode.ASK -> 1
                    SessionPermissionMode.STRICT -> 2
                }
                SettingsSegmentedRow(
                    icon = TablerIcons.ShieldCheck,
                    title = stringResource(R.string.settings_chat_tool_approval_title),
                    subtitle = stringResource(R.string.settings_chat_tool_approval_subtitle),
                    options = modeOptions,
                    selectedIndex = modeIndex,
                    onSelectedChange = { idx ->
                        val newMode = when (idx) {
                            0 -> SessionPermissionMode.TRUSTED
                            2 -> SessionPermissionMode.STRICT
                            else -> SessionPermissionMode.ASK
                        }
                        scope.launch { settings.setDefaultSessionPermissionMode(newMode) }
                    },
                )
                SettingsGroupDivider()
                // 跳转到单工具策略管理页
                SettingsItemRow(
                    icon = TablerIcons.Tools,
                    title = stringResource(R.string.settings_chat_tool_approval_per_tool),
                    subtitle = stringResource(R.string.settings_chat_tool_approval_per_tool_subtitle),
                    onClick = onOpenToolsSettings,
                ) { ChevronRight() }
                SettingsGroupDivider()
                // 当前模式说明(动态显示)
                val modeDescRes = when (permissionMode) {
                    SessionPermissionMode.TRUSTED -> R.string.settings_chat_tool_approval_trusted_desc
                    SessionPermissionMode.ASK -> R.string.settings_chat_tool_approval_ask_desc
                    SessionPermissionMode.STRICT -> R.string.settings_chat_tool_approval_strict_desc
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(modeDescRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ── 1. 消息显示 ──
        item { SectionLabel(stringResource(R.string.settings_chat_message_display)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_chat_show_mood),
                    subtitle = stringResource(R.string.settings_chat_show_mood_subtitle),
                    checked = prefs.showMoodBlock,
                    onCheckedChange = { v -> update { it.copy(showMoodBlock = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_chat_show_reasoning),
                    subtitle = stringResource(R.string.settings_chat_show_reasoning_subtitle),
                    checked = prefs.showReasoning,
                    onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_chat_show_reflection),
                    subtitle = stringResource(R.string.settings_chat_show_reflection_subtitle),
                    checked = prefs.showReflectionBlock,
                    onCheckedChange = { v -> update { it.copy(showReflectionBlock = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Calculator,
                    title = stringResource(R.string.settings_chat_show_token),
                    subtitle = stringResource(R.string.settings_chat_show_token_subtitle),
                    checked = prefs.showTokenEstimate,
                    onCheckedChange = { v -> update { it.copy(showTokenEstimate = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.ToggleLeft,
                    title = stringResource(R.string.settings_chat_show_model),
                    subtitle = stringResource(R.string.settings_chat_show_model_subtitle),
                    checked = prefs.showModelName,
                    onCheckedChange = { v -> update { it.copy(showModelName = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.CalendarTime,
                    title = stringResource(R.string.settings_chat_show_timestamp),
                    subtitle = stringResource(R.string.settings_chat_show_timestamp_subtitle),
                    checked = prefs.showTimestamp,
                    onCheckedChange = { v -> update { it.copy(showTimestamp = v) } },
                )
            }
        }

        // ── 生成风格(全局温度 + 语气风格 + 语气)──
        item { SectionLabel(stringResource(R.string.settings_chat_generation_style)) }
        item {
            SettingsGroup {
                // 温度滑块:0-2,步长 0.1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Temperature,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        // 本地草稿 + 400ms 防抖:拖动时只更新草稿,停止后再写 DataStore,避免每次微调都持久化
                        var temperatureDraft by remember { mutableStateOf(prefs.globalTemperature) }
                        LaunchedEffect(prefs.globalTemperature) {
                            if (prefs.globalTemperature != temperatureDraft) {
                                temperatureDraft = prefs.globalTemperature
                            }
                        }
                        LaunchedEffect(temperatureDraft) {
                            if (temperatureDraft != prefs.globalTemperature) {
                                delay(400)
                                update { it.copy(globalTemperature = temperatureDraft) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_chat_randomness),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "%.1f".format(temperatureDraft),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = stringResource(temperatureHint(temperatureDraft)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        IosSlider(
                            value = temperatureDraft,
                            onValueChange = { v -> temperatureDraft = v },
                            valueRange = 0f..2f,
                            steps = 19,  // 步长 0.1,0-2 共 21 个点,steps = 21 - 2
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                // 风格选择:concise/balanced/detailed
                val styleOptions = listOf(
                    stringResource(R.string.settings_chat_style_concise),
                    stringResource(R.string.settings_chat_style_balanced),
                    stringResource(R.string.settings_chat_style_detailed),
                )
                val selectedStyleIndex = when (prefs.responseStyle) {
                    "concise" -> 0; "detailed" -> 2; else -> 1
                }
                SettingsSegmentedRow(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_chat_style),
                    subtitle = stringResource(R.string.settings_chat_style_subtitle),
                    options = styleOptions,
                    selectedIndex = selectedStyleIndex,
                    onSelectedChange = { idx ->
                        val value = when (idx) { 0 -> "concise"; 2 -> "detailed"; else -> "balanced" }
                        update { it.copy(responseStyle = value) }
                    },
                )
                SettingsGroupDivider()
                // 语气选择:neutral/friendly/formal/humorous
                val toneOptions = listOf(
                    stringResource(R.string.settings_chat_tone_neutral),
                    stringResource(R.string.settings_chat_tone_friendly),
                    stringResource(R.string.settings_chat_tone_formal),
                    stringResource(R.string.settings_chat_tone_humorous),
                )
                val selectedToneIndex = when (prefs.responseTone) {
                    "neutral" -> 0; "friendly" -> 1; "formal" -> 2; "humorous" -> 3; else -> 0
                }
                SettingsSegmentedRow(
                    icon = TablerIcons.ToggleLeft,
                    title = stringResource(R.string.settings_chat_tone),
                    subtitle = stringResource(R.string.settings_chat_tone_subtitle),
                    options = toneOptions,
                    selectedIndex = selectedToneIndex,
                    onSelectedChange = { idx ->
                        val value = when (idx) {
                            0 -> "neutral"; 1 -> "friendly"; 2 -> "formal"; 3 -> "humorous"
                            else -> "neutral"
                        }
                        update { it.copy(responseTone = value) }
                    },
                )
            }
        }

        // ── 2. 默认展开状态 ──
        item { SectionLabel(stringResource(R.string.settings_chat_default_expand)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.ArrowsMaximize,
                    title = stringResource(R.string.settings_chat_mood_expand),
                    subtitle = stringResource(R.string.settings_chat_mood_expand_subtitle),
                    checked = prefs.moodExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(moodExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.ArrowsMaximize,
                    title = stringResource(R.string.settings_chat_expand_button),
                    subtitle = stringResource(R.string.settings_chat_expand_button_subtitle),
                    checked = prefs.showExpandButton,
                    onCheckedChange = { v -> update { it.copy(showExpandButton = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.ArrowsMaximize,
                    title = stringResource(R.string.settings_chat_reasoning_expand),
                    subtitle = stringResource(R.string.settings_chat_reasoning_expand_subtitle),
                    checked = prefs.reasoningExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reasoningExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.ArrowsMaximize,
                    title = stringResource(R.string.settings_chat_reflection_expand),
                    subtitle = stringResource(R.string.settings_chat_reflection_expand_subtitle),
                    checked = prefs.reflectionExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reflectionExpandedByDefault = v) } },
                )
            }
        }

        // ── 3. 交互行为 ──
        item { SectionLabel(stringResource(R.string.settings_chat_interaction)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Adjustments,
                    title = stringResource(R.string.settings_chat_streaming),
                    subtitle = stringResource(R.string.settings_chat_streaming_subtitle),
                    checked = prefs.streamResponse,
                    onCheckedChange = { v -> update { it.copy(streamResponse = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.ArrowRight,
                    title = stringResource(R.string.settings_chat_auto_scroll),
                    subtitle = stringResource(R.string.settings_chat_auto_scroll_subtitle),
                    checked = prefs.autoScrollToBottom,
                    onCheckedChange = { v -> update { it.copy(autoScrollToBottom = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Volume,
                    title = stringResource(R.string.settings_chat_volume_scroll),
                    subtitle = stringResource(R.string.settings_chat_volume_scroll_subtitle),
                    checked = prefs.volumeKeyScroll,
                    onCheckedChange = { v -> update { it.copy(volumeKeyScroll = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.HandFinger,
                    title = stringResource(R.string.settings_chat_enter_send),
                    subtitle = stringResource(R.string.settings_chat_enter_send_subtitle),
                    checked = prefs.enterToSend,
                    onCheckedChange = { v -> update { it.copy(enterToSend = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Bolt,
                    title = stringResource(R.string.settings_chat_haptic),
                    subtitle = stringResource(R.string.settings_chat_haptic_subtitle),
                    checked = prefs.hapticFeedback,
                    onCheckedChange = { v -> update { it.copy(hapticFeedback = v) } },
                )
            }
        }

        // ── 4. 高级 ──
        item { SectionLabel(stringResource(R.string.settings_common_advanced)) }
        item {
            SettingsGroup {
                // 注:长消息折叠阈值(longMessageThreshold)设置项已移除 ——
                // MessageBubble 不再对助手回复正文做整体折叠,该阈值无消费方,保留会误导用户。
                SettingsSwitchRow(
                    icon = TablerIcons.Eye,
                    title = stringResource(R.string.settings_chat_show_tool_calls),
                    subtitle = stringResource(R.string.settings_chat_show_tool_calls_subtitle),
                    checked = prefs.showToolCallDetails,
                    onCheckedChange = { v -> update { it.copy(showToolCallDetails = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.CalendarTime,
                    title = stringResource(R.string.settings_chat_24h),
                    subtitle = stringResource(R.string.settings_chat_24h_subtitle),
                    checked = prefs.use24Hour,
                    onCheckedChange = { v -> update { it.copy(use24Hour = v) } },
                )
                SettingsGroupDivider()
                // v1.110: 全局默认深度思考开关,免去每次新会话都要按按钮
                SettingsSwitchRow(
                    icon = TablerIcons.Atom,
                    title = stringResource(R.string.settings_chat_default_deep_thinking),
                    subtitle = stringResource(R.string.settings_chat_default_deep_thinking_subtitle),
                    checked = prefs.defaultDeepThinking,
                    onCheckedChange = { v -> update { it.copy(defaultDeepThinking = v) } },
                )
                SettingsGroupDivider()
                // v1.0.4 (P3-4): 性能模式 — 接入 MessagePaginator,超长会话仅渲染最近 N 条
                SettingsSwitchRow(
                    icon = TablerIcons.Gauge,
                    title = stringResource(R.string.settings_chat_performance_mode),
                    subtitle = stringResource(R.string.settings_chat_performance_mode_subtitle),
                    checked = prefs.performanceMode,
                    onCheckedChange = { v -> update { it.copy(performanceMode = v) } },
                )
            }
        }

        // ── v1.95: 表情包库 ──
        item { SectionLabel(stringResource(R.string.settings_chat_sticker_section)) }
        item { StickerLibrarySection(settings = settings, scope = scope) }
    }
}

/** 把温度值转成可读提示的资源 ID(0=确定 1=平衡 2=创造性)。 */
private fun temperatureHint(value: Float): Int = when {
    value <= 0.3f -> R.string.settings_chat_temp_hint_certain
    value <= 0.7f -> R.string.settings_chat_temp_hint_balanced_low
    value <= 1.2f -> R.string.settings_chat_temp_hint_balanced
    value <= 1.6f -> R.string.settings_chat_temp_hint_creative_low
    else -> R.string.settings_chat_temp_hint_creative
}

/**
 * v1.95: 表情包库管理区 — 启用开关 + 发送概率 + 导入 zip + 分类筛选 + 预览网格。
 *
 * 外层是 LazyColumn,这里用 FlowRow(而非 LazyVerticalGrid)布局预览网格,
 * 避免嵌套滚动冲突。表情包库存储独立于 Room,通过 [StickerLibraryRepository]
 * 读写 filesDir/stickers 目录。
 *
 * 交互:
 *  - 启用开关:控制 AI 回复时是否可发送表情包
 *  - 发送概率:0-100 连续滑块,AI 每次回复时有此概率调用 send_sticker
 *  - 导入 zip:通过 SAF 选取 zip,按文件夹结构自动分类
 *  - 分类胶囊:首个为"全部",其余为各分类;点击切换筛选
 *  - 预览网格:80x80dp 缩略图,长按弹删除确认对话框
 *  - 空态:灰色图标 + 标题 + 副标题
 *
 * @param settings 设置仓库(读写 sticker_enabled / sticker_send_probability)
 * @param scope 协程作用域(发起导入/删除/保存操作)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StickerLibrarySection(
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val stickerRepo: StickerLibraryRepository = koinInject()
    val stickerEnabled by settings.stickerEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val probability by settings.stickerSendProbabilityFlow.collectAsStateWithLifecycle(initialValue = 30)

    // 刷新触发器:导入/删除后自增以重新加载分类与列表
    var refreshTrigger by remember { mutableStateOf(0) }
    // 当前选中分类;空字符串表示"全部"
    var selectedCategory by remember { mutableStateOf("") }
    // 待删除的表情包项(非 null 时弹删除确认对话框)
    var pendingDelete by remember { mutableStateOf<StickerItem?>(null) }
    // v1.112 (F1-F2): 批量删除模式
    var batchDeleteMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // v1.112: 批量删除/清空确认
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 分类列表(导入/删除后刷新)
    val categories by produceState(initialValue = emptyList<String>(), refreshTrigger) {
        value = stickerRepo.listCategories()
    }

    // 表情包列表(按选中分类筛选;导入/删除/切分类时刷新)
    val stickers by produceState(
        initialValue = emptyList<StickerItem>(),
        refreshTrigger,
        selectedCategory,
    ) {
        value = stickerRepo.listStickers(selectedCategory.takeIf { it.isNotBlank() })
    }

    // 导入 zip launcher(SAF OpenDocument,限定 zip MIME)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            stickerRepo.importZip(uri)
                .onSuccess { count ->
                    MuseToast.show(context.getString(R.string.settings_sticker_imported, count))
                    refreshTrigger++
                }
                .onError { msg, _ ->
                    MuseToast.show(context.getString(R.string.settings_sticker_import_failed, msg), 3500)
                }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup {
            // 启用开关
            SettingsSwitchRow(
                icon = TablerIcons.Photo,
                title = stringResource(R.string.settings_sticker_enabled),
                subtitle = stringResource(R.string.settings_sticker_enabled_subtitle),
                checked = stickerEnabled,
                onCheckedChange = { v -> scope.launch { settings.saveStickerEnabled(v) } },
            )
            SettingsGroupDivider()
            // 发送概率滑块(0-100 连续,百分比显示)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = TablerIcons.Adjustments,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Column(modifier = Modifier.weight(1f)) {
                    // 本地草稿 + 400ms 防抖:拖动时只更新草稿,停止后再写 DataStore
                    var probDraft by remember { mutableStateOf(probability.toFloat()) }
                    LaunchedEffect(probability) {
                        if (probability.toFloat() != probDraft) probDraft = probability.toFloat()
                    }
                    LaunchedEffect(probDraft) {
                        if (probDraft.toInt() != probability) {
                            delay(400)
                            scope.launch { settings.saveStickerSendProbability(probDraft.toInt()) }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_sticker_send_probability),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${probDraft.toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_sticker_send_probability_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    IosSlider(
                        value = probDraft,
                        onValueChange = { v -> probDraft = v },
                        valueRange = 0f..100f,
                        steps = 0,  // 连续
                        showValueLabel = false,  // 百分比已在标题行显示
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            SettingsGroupDivider()
            // 导入 zip(通过 SAF 选取压缩包)
            SettingsItemRow(
                icon = TablerIcons.FileUpload,
                title = stringResource(R.string.settings_sticker_upload_zip),
                subtitle = stringResource(R.string.settings_sticker_upload_zip_subtitle),
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                },
            ) { ChevronRight() }
            // v1.112 (F1-F2): 批量删除 / 清空入口
            if (stickers.isNotEmpty()) {
                SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            batchDeleteMode = !batchDeleteMode
                            if (!batchDeleteMode) selectedIds.clear()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (batchDeleteMode) "完成" else "批量删除",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "清空全部",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // 分类胶囊标签(仅当有表情包时才显示)
        if (stickers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IosChip(
                    selected = selectedCategory.isBlank(),
                    onClick = { selectedCategory = "" },
                    label = stringResource(R.string.settings_sticker_category_all),
                )
                categories.forEach { cat ->
                    IosChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = cat,
                    )
                }
            }
        }

        // 预览网格 / 空态
        if (stickers.isEmpty()) {
            // 空态:灰色图标 + 标题 + 副标题
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = TablerIcons.Photo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            // v1.112 (F5): 用 LazyVerticalGrid 替代 FlowRow,懒加载 600 张不卡顿
            // 高度固定为 3 行(240dp),内部可滚动;加 key 让 Compose 精准重组
            val gridColumns = GridCells.Adaptive(80.dp)
            LazyVerticalGrid(
                columns = gridColumns,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = stickers,
                    key = { it.id },
                ) { item ->
                    val file = stickerRepo.getStickerFileByPath(item.relativePath)
                    val isSelected = selectedIds[item.id] == true
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(MuseShapes.medium)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (batchDeleteMode) {
                                        selectedIds[item.id] = !isSelected
                                    }
                                },
                                onLongClick = {
                                    if (!batchDeleteMode) pendingDelete = item
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context)
                                .data(file)
                                // v1.112 (F5): 限制解码尺寸为 80dp,避免大图全分辨率解码浪费内存
                                .size(160)
                                .crossfade(false)
                                .build(),
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // v1.112 (F1-F2): 批量删除模式下的选中蒙层
                        if (batchDeleteMode && isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = TablerIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
            // v1.112 (F1-F2): 批量删除模式下,底部显示删除按钮
            if (batchDeleteMode && selectedIds.isNotEmpty()) {
                Surface(
                    onClick = { showBatchDeleteConfirm = true },
                    color = MaterialTheme.colorScheme.error,
                    shape = MuseShapes.pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = "删除选中的 ${selectedIds.count { it.value }} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }

    // 删除确认对话框(长按表情包后弹出)
    pendingDelete?.let { item ->
        MuseDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.settings_sticker_delete_confirm),
            content = { Text(item.fileName) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    stickerRepo.deleteSticker(item.id)
                    pendingDelete = null
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }

    // v1.112 (F1-F2): 批量删除确认
    if (showBatchDeleteConfirm) {
        val count = selectedIds.count { it.value }
        MuseDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = "批量删除确认",
            content = { Text("确定删除选中的 $count 个表情包?此操作不可撤销。") },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    val ids = selectedIds.filter { it.value }.keys
                    val deleted = stickerRepo.deleteStickers(ids)
                    MuseToast.show("已删除 $deleted 个表情包")
                    selectedIds.clear()
                    batchDeleteMode = false
                    showBatchDeleteConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showBatchDeleteConfirm = false },
            destructive = true,
        )
    }

    // v1.112 (F1-F2): 清空全部确认
    if (showClearAllConfirm) {
        MuseDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = "清空全部表情包",
            content = { Text("确定删除所有表情包?此操作不可撤销。") },
            confirmText = "清空",
            onConfirm = {
                scope.launch {
                    val deleted = stickerRepo.clearAll()
                    MuseToast.show("已清空 $deleted 个表情包")
                    selectedIds.clear()
                    batchDeleteMode = false
                    showClearAllConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearAllConfirm = false },
            destructive = true,
        )
    }
}
