package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatGradient
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * v1.0.20: 新增"工具调用批准"分组(置顶),含三档会话权限模式。
 * 单工具级策略仍在工具页配置,本页不提供跳转入口。
 */
@Composable
fun ChatSettingsPage(
    onBack: () -> Unit,
    /** v2.6.0: 打开消息快捷按钮自定义页。 */
    onOpenQuickActions: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = ChatPreferences())
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        // v2.5.3 (P1-1): 不再用收集到的 prefs 快照整份回写（快速拖动 slider / 快速输入时
        // 会用过时快照覆盖别处的修改）；改为在 DataStore edit 事务内原子读-改-写。
        scope.launch { settings.updateChatPreferences(block) }
    }

    // v1.0.47 P5-3: Token 估算开关(默认关闭,实验性,避免 BPE 性能开销)
    val tokenEstimateEnabled by settings.tokenEstimateEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val customTitlePrompt by settings.customTitlePromptFlow.collectAsStateWithLifecycle(initialValue = null)
    val pasteAsFileThreshold by settings.pasteAsFileThresholdFlow.collectAsStateWithLifecycle(initialValue = 2000)
    var showTitlePromptDialog by remember { mutableStateOf(false) }
    var titlePromptDraft by remember(customTitlePrompt) { mutableStateOf(customTitlePrompt.orEmpty()) }
    // v1.0.47 P5-2: 长文本粘贴转文件开关(默认开启)
    val pasteAsFileEnabled by settings.pasteAsFileEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    // v1.0.51: 回复通知策略(从记忆设置页移入,与聊天行为更相关)
    val notificationPolicy by settings.notificationPolicyFlow
        .collectAsStateWithLifecycle(initialValue = "when_unfocused")
    val toolLoopMaxRounds by settings.toolLoopMaxRoundsFlow
        .collectAsStateWithLifecycle(initialValue = 0)
    // 全局默认审批策略（工具页与本页共用同一数据源）
    val permissionMode by settings.defaultSessionPermissionModeFlow
        .collectAsStateWithLifecycle(initialValue = SessionPermissionMode.ASK)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_chat_title), onBack = onBack) {
        // ── 0. 工具调用批准（置顶）──
        item { SectionLabel(stringResource(R.string.settings_chat_tool_approval_section)) }
        item {
            SettingsGroup {
                val modeOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tool_approval_trusted),
                        stringResource(R.string.settings_chat_tool_approval_ask),
                        stringResource(R.string.settings_chat_tool_approval_strict),
                    )
                val modeIndex =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> 0
                        SessionPermissionMode.ASK -> 1
                        SessionPermissionMode.STRICT -> 2
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.shieldCheck,
                    title = stringResource(R.string.settings_chat_tool_approval_title),
                    subtitle = stringResource(R.string.settings_chat_tool_approval_subtitle),
                    options = modeOptions,
                    selectedIndex = modeIndex,
                    onSelectedChange = { idx ->
                        val newMode =
                            when (idx) {
                                0 -> SessionPermissionMode.TRUSTED
                                2 -> SessionPermissionMode.STRICT
                                else -> SessionPermissionMode.ASK
                            }
                        scope.launch { settings.setDefaultSessionPermissionMode(newMode) }
                    },
                )
                SettingsGroupDivider()
                // 当前模式说明（随选中档位动态切换）
                val modeDescRes =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> R.string.settings_chat_tool_approval_trusted_desc
                        SessionPermissionMode.ASK -> R.string.settings_chat_tool_approval_ask_desc
                        SessionPermissionMode.STRICT -> R.string.settings_chat_tool_approval_strict_desc
                    }
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.lock,
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
        // ── v1.0.74: 聊天背景(聊天/Agent/群聊共用) ──
        item { SectionLabel(stringResource(R.string.settings_chat_background_section)) }
        item {
            val chatBackground by settings.chatBackgroundFlow.collectAsStateWithLifecycle(initialValue = null)
            val bgScope = rememberCoroutineScope()
            val bgContext = LocalContext.current
            val bgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        bgScope.launch {
                            // 审计修复 (6.5): 背景图改为存文件路径,DataStore 只存路径字符串。
                            // 原实现把 ~500KB base64 写进 DataStore Preferences,每次 edit 全量重写,
                            // 设置文件膨胀、冷启动变慢、ANR 风险。文件存 filesDir,体积可控。
                            val savedPath =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val bytes =
                                            bgContext.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                                                ?: return@runCatching null
                                        val bmp =
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                ?: return@runCatching null
                                        val maxSide = 1920
                                        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
                                        val scaled =
                                            if (scale < 1f) {
                                                android.graphics.Bitmap.createScaledBitmap(
                                                    bmp,
                                                    (bmp.width * scale).toInt(),
                                                    (bmp.height * scale).toInt(),
                                                    true,
                                                )
                                            } else {
                                                bmp
                                            }
                                        val out = java.io.ByteArrayOutputStream()
                                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                                        if (scaled !== bmp) scaled.recycle()
                                        bmp.recycle()
                                        val target = java.io.File(bgContext.filesDir, "chat_background.jpg")
                                        java.io.FileOutputStream(target).use { fos ->
                                            fos.write(out.toByteArray())
                                        }
                                        out.close()
                                        // 兼容旧值:已存 base64 的继续用,新值存路径(以 file:// 开头区分)
                                        "file://" + target.absolutePath
                                    }.getOrNull()
                                }
                            if (savedPath != null) {
                                settings.saveChatBackground(savedPath)
                                // v1.0.74 fix: 此前成功/失败均无反馈,用户以为坏了
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_failed))
                            }
                        }
                    }
                }
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            bgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_background_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_background_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (chatBackground != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    bgScope.launch {
                                        settings.saveChatBackground(null)
                                        io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(bgContext, null)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_chat_background_choose),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        item {
            // v2.6.2（用户反馈）: 深色模式专用背景 —— 配合明暗自动切换，让背景也跟着换。
            val darkBg by settings.chatBackgroundDarkFlow.collectAsStateWithLifecycle(initialValue = null)
            val darkBgScope = rememberCoroutineScope()
            val darkBgContext = LocalContext.current
            val darkBgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        darkBgScope.launch {
                            val savedPath = withContext(Dispatchers.IO) {
                                io.zer0.muse.ui.settings.ChatBackgroundStore.saveFromUri(
                                    darkBgContext,
                                    picked,
                                    "chat_background_dark",
                                )
                            }
                            if (savedPath != null) {
                                settings.saveChatBackgroundDark(savedPath)
                                MuseToast.show(darkBgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(darkBgContext.getString(R.string.chat_background_save_failed))
                            }
                        }
                    }
                }
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            darkBgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_background_dark_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_background_dark_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (darkBg != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    darkBgScope.launch {
                                        settings.saveChatBackgroundDark(null)
                                        io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(
                                            darkBgContext,
                                            "chat_background_dark",
                                        )
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_chat_background_choose),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        item {
            // E3: 动态渐变背景 — 双色线性渐变预设(背景图优先,渐变在无背景图时生效)
            val chatGradient by settings.chatGradientFlow.collectAsStateWithLifecycle(initialValue = null)
            val gradScope = rememberCoroutineScope()
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (chatGradient != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    gradScope.launch { settings.saveChatGradient(null) }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_CHAT_GRADIENTS.forEach { (start, end) ->
                        val selected = chatGradient?.startColorArgb == start && chatGradient?.endColorArgb == end
                        Box(
                            modifier =
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(start.toInt()), Color(end.toInt())),
                                    ),
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    gradScope.launch { settings.saveChatGradient(ChatGradient(start, end)) }
                                },
                        )
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_chat_message_display)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_mood),
                    subtitle = stringResource(R.string.settings_chat_show_mood_subtitle),
                    checked = prefs.showMoodBlock,
                    onCheckedChange = { v -> update { it.copy(showMoodBlock = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_reasoning),
                    subtitle = stringResource(R.string.settings_chat_show_reasoning_subtitle),
                    checked = prefs.showReasoning,
                    onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
                )
                SettingsGroupDivider()
                // v2.6.1（用户反馈）: 强制模型返回思考 —— 打开后不被助手级 OFF/AUTO 压掉。
                SettingsSwitchRow(
                    icon = MuseIcons.brain,
                    title = stringResource(R.string.settings_chat_force_reasoning),
                    subtitle = stringResource(R.string.settings_chat_force_reasoning_subtitle),
                    checked = prefs.forceReasoning,
                    onCheckedChange = { v -> update { it.copy(forceReasoning = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_reflection),
                    subtitle = stringResource(R.string.settings_chat_show_reflection_subtitle),
                    checked = prefs.showReflectionBlock,
                    onCheckedChange = { v -> update { it.copy(showReflectionBlock = v) } },
                )
                SettingsGroupDivider()
                // v1.0.52: 移除 showTokenEstimate 死开关 — 该开关从未被任何 UI 读取,
                // 用户反复开启它但无效果。真正生效的是"性能"分组里的"Token 估算"开关。
                SettingsSwitchRow(
                    icon = MuseIcons.switch,
                    title = stringResource(R.string.settings_chat_show_model),
                    subtitle = stringResource(R.string.settings_chat_show_model_subtitle),
                    checked = prefs.showModelName,
                    onCheckedChange = { v -> update { it.copy(showModelName = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.user,
                    title = stringResource(R.string.settings_chat_show_assistant_name),
                    subtitle = stringResource(R.string.settings_chat_show_assistant_name_subtitle),
                    checked = prefs.showAssistantName,
                    onCheckedChange = { v -> update { it.copy(showAssistantName = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.settings_chat_show_timestamp),
                    subtitle = stringResource(R.string.settings_chat_show_timestamp_subtitle),
                    checked = prefs.showTimestamp,
                    onCheckedChange = { v -> update { it.copy(showTimestamp = v) } },
                )
            }
        }

        // ── v2.6.10: 跨会话衔接 ──
        // 开新对话时把上一个会话末尾的对话带进上下文。
        // 默认关闭：会占用输入 token，用户明确需要连续感时再打开。
        item { SectionLabel(stringResource(R.string.settings_chat_carry_over_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_chat_carry_over),
                    subtitle = stringResource(R.string.settings_chat_carry_over_subtitle),
                    checked = prefs.carryOverEnabled,
                    onCheckedChange = { v -> update { it.copy(carryOverEnabled = v) } },
                )
                if (prefs.carryOverEnabled) {
                    SettingsGroupDivider()
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(
                                R.string.settings_chat_carry_over_count,
                                prefs.carryOverMessageCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_chat_carry_over_count_hint,
                                prefs.carryOverMessageCount / 2,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = prefs.carryOverMessageCount.toFloat(),
                            onValueChange = { v ->
                                // 按 2 条一档（一问一答），避免用户调到奇怪的奇数。
                                val snapped = (v.toInt() / 2 * 2).coerceIn(4, 40)
                                update { it.copy(carryOverMessageCount = snapped) }
                            },
                            valueRange = 4f..40f,
                            steps = 17,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ── v2.6.0: 消息快捷按钮自定义（Via 式三级页）──
        item { SectionLabel(stringResource(R.string.settings_chat_quick_actions)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.copy,
                    title = stringResource(R.string.settings_chat_quick_customize),
                    subtitle = stringResource(R.string.settings_chat_quick_actions_desc),
                    onClick = onOpenQuickActions,
                ) { ChevronRight() }
            }
        }

        // ── 正文排版(消息字号 / 字间距, v2.0)──
        item { SectionLabel(stringResource(R.string.settings_chat_typography_section)) }
        // v2.5.0: 排版预览面板 —— 当前聊天外观设置的合成效果,所见即所得
        item {
            SettingsGroup {
                BubblePreviewPanel(prefs = prefs)
            }
        }
        item {
            SettingsGroup {
                // 消息字号:0.85~1.25 倍,默认 100%
                var fontScaleDraft by remember { mutableStateOf(prefs.messageFontScale) }
                LaunchedEffect(prefs.messageFontScale) {
                    if (prefs.messageFontScale != fontScaleDraft) {
                        fontScaleDraft = prefs.messageFontScale
                    }
                }
                LaunchedEffect(fontScaleDraft) {
                    if (fontScaleDraft != prefs.messageFontScale) {
                        delay(400)
                        update { it.copy(messageFontScale = fontScaleDraft) }
                    }
                }
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // LAYOUT-01: 设置项名称取剩余宽度 + 限行,百分比是定宽短值,限 1 行。
                        Text(
                            text = stringResource(R.string.settings_chat_message_font_size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(fontScaleDraft * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_chat_message_font_size_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = fontScaleDraft,
                        onValueChange = { v -> fontScaleDraft = v },
                        valueRange = 0.85f..1.25f,
                        steps = 7,
                        valueFormatter = { v -> "${(v * 100).toInt()}%" },
                    )
                }
                SettingsGroupDivider()
                // 字间距:-0.02em ~ 0.08em
                var letterSpacingDraft by remember { mutableStateOf(prefs.messageLetterSpacingEm) }
                LaunchedEffect(prefs.messageLetterSpacingEm) {
                    if (prefs.messageLetterSpacingEm != letterSpacingDraft) {
                        letterSpacingDraft = prefs.messageLetterSpacingEm
                    }
                }
                LaunchedEffect(letterSpacingDraft) {
                    if (letterSpacingDraft != prefs.messageLetterSpacingEm) {
                        delay(400)
                        update { it.copy(messageLetterSpacingEm = letterSpacingDraft) }
                    }
                }
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // LAYOUT-01: 同上,设置项名称取宽 + 限行,数值列限 1 行。
                        Text(
                            text = stringResource(R.string.settings_chat_letter_spacing),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "%.2f em".format(letterSpacingDraft),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_chat_letter_spacing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = letterSpacingDraft,
                        onValueChange = { v -> letterSpacingDraft = v },
                        valueRange = -0.02f..0.08f,
                        steps = 9,
                        valueFormatter = { v -> "%.2f".format(v) },
                    )
                }
            }
        }

        // ── 生成风格(全局温度 + 语气风格 + 语气)──
        item { SectionLabel(stringResource(R.string.settings_chat_generation_style)) }
        item {
            SettingsGroup {
                // 温度滑块:0-2,步长 0.1
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.temperature,
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
                            // LAYOUT-01: 同上(此时行内还叠加了图标与 Column.weight),名称取宽 + 限行。
                            Text(
                                text = stringResource(R.string.settings_chat_randomness),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "%.1f".format(temperatureDraft),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = stringResource(temperatureHint(temperatureDraft)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        MuseSlider(
                            value = temperatureDraft,
                            onValueChange = { v -> temperatureDraft = v },
                            valueRange = 0f..2f,
                            // 步长 0.1,0-2 共 21 个点,steps = 21 - 2
                            steps = 19,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                // 风格选择:concise/balanced/detailed
                val styleOptions =
                    listOf(
                        stringResource(R.string.settings_chat_style_concise),
                        stringResource(R.string.settings_chat_style_balanced),
                        stringResource(R.string.settings_chat_style_detailed),
                    )
                val selectedStyleIndex =
                    when (prefs.responseStyle) {
                        "concise" -> 0
                        "detailed" -> 2
                        else -> 1
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_style),
                    subtitle = stringResource(R.string.settings_chat_style_subtitle),
                    options = styleOptions,
                    selectedIndex = selectedStyleIndex,
                    onSelectedChange = { idx ->
                        val value =
                            when (idx) {
                                0 -> "concise"
                                2 -> "detailed"
                                else -> "balanced"
                            }
                        update { it.copy(responseStyle = value) }
                    },
                )
                SettingsGroupDivider()
                // 语气选择:neutral/friendly/formal/humorous
                val toneOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tone_neutral),
                        stringResource(R.string.settings_chat_tone_friendly),
                        stringResource(R.string.settings_chat_tone_formal),
                        stringResource(R.string.settings_chat_tone_humorous),
                    )
                val selectedToneIndex =
                    when (prefs.responseTone) {
                        "neutral" -> 0
                        "friendly" -> 1
                        "formal" -> 2
                        "humorous" -> 3
                        else -> 0
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.switch,
                    title = stringResource(R.string.settings_chat_tone),
                    subtitle = stringResource(R.string.settings_chat_tone_subtitle),
                    options = toneOptions,
                    selectedIndex = selectedToneIndex,
                    onSelectedChange = { idx ->
                        val value =
                            when (idx) {
                                0 -> "neutral"
                                1 -> "friendly"
                                2 -> "formal"
                                3 -> "humorous"
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
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_mood_expand),
                    subtitle = stringResource(R.string.settings_chat_mood_expand_subtitle),
                    checked = prefs.moodExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(moodExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_expand_button),
                    subtitle = stringResource(R.string.settings_chat_expand_button_subtitle),
                    checked = prefs.showExpandButton,
                    onCheckedChange = { v -> update { it.copy(showExpandButton = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_reasoning_expand),
                    subtitle = stringResource(R.string.settings_chat_reasoning_expand_subtitle),
                    checked = prefs.reasoningExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reasoningExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
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
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_chat_streaming),
                    subtitle = stringResource(R.string.settings_chat_streaming_subtitle),
                    checked = prefs.streamResponse,
                    onCheckedChange = { v -> update { it.copy(streamResponse = v) } },
                )
                SettingsGroupDivider()
                // issue #3.5: 快速回复模式 —— 跳过每轮的相关记忆检索(首字延迟大头)。
                SettingsSwitchRow(
                    icon = MuseIcons.arrowRight,
                    title = stringResource(R.string.settings_chat_fast_reply),
                    subtitle = stringResource(R.string.settings_chat_fast_reply_subtitle),
                    checked = prefs.fastReplyMode,
                    onCheckedChange = { v -> update { it.copy(fastReplyMode = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.arrowRight,
                    title = stringResource(R.string.settings_chat_auto_scroll),
                    subtitle = stringResource(R.string.settings_chat_auto_scroll_subtitle),
                    checked = prefs.autoScrollToBottom,
                    onCheckedChange = { v -> update { it.copy(autoScrollToBottom = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.volume,
                    title = stringResource(R.string.settings_chat_volume_scroll),
                    subtitle = stringResource(R.string.settings_chat_volume_scroll_subtitle),
                    checked = prefs.volumeKeyScroll,
                    onCheckedChange = { v -> update { it.copy(volumeKeyScroll = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.handFinger,
                    title = stringResource(R.string.settings_chat_enter_send),
                    subtitle = stringResource(R.string.settings_chat_enter_send_subtitle),
                    checked = prefs.enterToSend,
                    onCheckedChange = { v -> update { it.copy(enterToSend = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.bolt,
                    title = stringResource(R.string.settings_chat_haptic),
                    subtitle = stringResource(R.string.settings_chat_haptic_subtitle),
                    checked = prefs.hapticFeedback,
                    onCheckedChange = { v -> update { it.copy(hapticFeedback = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.handFinger,
                    title = stringResource(R.string.settings_chat_predictive_back),
                    subtitle = stringResource(R.string.settings_chat_predictive_back_subtitle),
                    checked = prefs.predictiveBackEnabled,
                    onCheckedChange = { v -> update { it.copy(predictiveBackEnabled = v) } },
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
                    icon = MuseIcons.eye,
                    title = stringResource(R.string.settings_chat_show_tool_calls),
                    subtitle = stringResource(R.string.settings_chat_show_tool_calls_subtitle),
                    checked = prefs.showToolCallDetails,
                    onCheckedChange = { v -> update { it.copy(showToolCallDetails = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.pause,
                    title = stringResource(R.string.settings_chat_pause_tool_execution),
                    subtitle = stringResource(R.string.settings_chat_pause_tool_execution_subtitle),
                    checked = prefs.pauseToolExecution,
                    onCheckedChange = { v -> update { it.copy(pauseToolExecution = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.settings_chat_24h),
                    subtitle = stringResource(R.string.settings_chat_24h_subtitle),
                    checked = prefs.use24Hour,
                    onCheckedChange = { v -> update { it.copy(use24Hour = v) } },
                )
                SettingsGroupDivider()
                // v1.0.4 (P3-4): 性能模式 — 接入 MessagePaginator,超长会话仅渲染最近 N 条
                SettingsSwitchRow(
                    icon = MuseIcons.gauge,
                    title = stringResource(R.string.settings_chat_performance_mode),
                    subtitle = stringResource(R.string.settings_chat_performance_mode_subtitle),
                    checked = prefs.performanceMode,
                    onCheckedChange = { v -> update { it.copy(performanceMode = v) } },
                )
                SettingsGroupDivider()
                // v2.x: 会话缓存上限(高级) — 后台保留的会话消息内存副本数,热更生效
                SessionCacheSizeRow(settings = settings, scope = scope)
                SettingsGroupDivider()
                // v1.0.47 P5-3: Token 估算(默认关闭)——开启后输入栏显示 Token 计数按钮,点击查看上下文占用
                // v1.0.52: 这是唯一真正生效的 Token 开关(消息显示分组里的"显示Token"已移除,因为它从未工作)
                SettingsSwitchRow(
                    icon = MuseIcons.chartBar,
                    title = stringResource(R.string.settings_chat_token_estimate),
                    subtitle = stringResource(R.string.settings_chat_token_estimate_subtitle),
                    checked = tokenEstimateEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveTokenEstimateEnabled(v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.clipboard,
                    title = stringResource(R.string.settings_chat_paste_as_file),
                    subtitle = stringResource(R.string.settings_chat_paste_as_file_subtitle),
                    checked = pasteAsFileEnabled,
                    onCheckedChange = { v -> scope.launch { settings.savePasteAsFileEnabled(v) } },
                )
                if (pasteAsFileEnabled) {
                    SettingsGroupDivider()
                    val thresholdOptions = listOf("1000", "2000", "5000")
                    val thresholdIndex =
                        when (pasteAsFileThreshold) {
                            1000 -> 0
                            5000 -> 2
                            else -> 1
                        }
                    SettingsSegmentedRow(
                        icon = MuseIcons.clipboard,
                        title = stringResource(R.string.settings_chat_paste_as_file_threshold),
                        subtitle = stringResource(R.string.settings_chat_paste_as_file_threshold_subtitle, pasteAsFileThreshold),
                        options = thresholdOptions,
                        selectedIndex = thresholdIndex,
                        onSelectedChange = { idx ->
                            val threshold =
                                when (idx) {
                                    0 -> 1000
                                    2 -> 5000
                                    else -> 2000
                                }
                            scope.launch { settings.savePasteAsFileThreshold(threshold) }
                        },
                    )
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_chat_custom_title_prompt),
                    subtitle = customTitlePrompt?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_chat_custom_title_prompt_default),
                    onClick = {
                        titlePromptDraft = customTitlePrompt.orEmpty()
                        showTitlePromptDialog = true
                    },
                ) { ChevronRight() }
            }
        }

        // ── v2.x: 拆分子分组 — 原「高级」重复标签改为三个语义分组(上下文/记忆/通知)──
        item { SectionLabel(stringResource(R.string.settings_chat_context_section)) }
        // v2.x: 工具轮次上限(默认无限制) — Agent Loop 放开后长任务不再被轮次卡断;
        // 卡死/重复调用/连续失败等循环保护仍独立生效。
        item {
            SettingsGroup {
                val roundOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tool_rounds_unlimited),
                        "100",
                        "50",
                        "25",
                    )
                val roundIndex =
                    when (toolLoopMaxRounds) {
                        100 -> 1
                        50 -> 2
                        25 -> 3
                        else -> 0
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.refresh,
                    title = stringResource(R.string.settings_chat_tool_rounds),
                    subtitle = stringResource(R.string.settings_chat_tool_rounds_subtitle),
                    options = roundOptions,
                    selectedIndex = roundIndex,
                    onSelectedChange = { idx ->
                        val limit =
                            when (idx) {
                                1 -> 100
                                2 -> 50
                                3 -> 25
                                else -> 0
                            }
                        scope.launch { settings.saveToolLoopMaxRounds(limit) }
                    },
                )
            }
        }
        // ── 通知 ──
        item { SectionLabel(stringResource(R.string.settings_memory_notification_section)) }
        item {
            SettingsGroup {
                // 回复通知策略(never / when_unfocused / always)
                NotificationPolicyRow(
                    current = notificationPolicy,
                    onChange = { v ->
                        scope.launch { settings.saveNotificationPolicy(v) }
                    },
                )
            }
        }
    }

    if (showTitlePromptDialog) {
        MuseDialog(
            onDismissRequest = { showTitlePromptDialog = false },
            title = stringResource(R.string.settings_chat_custom_title_prompt),
            content = {
                MuseTextField(
                    value = titlePromptDraft,
                    onValueChange = { titlePromptDraft = it },
                    label = { Text(stringResource(R.string.settings_chat_custom_title_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveCustomTitlePrompt(titlePromptDraft.trim().takeIf { it.isNotBlank() }) }
                showTitlePromptDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTitlePromptDialog = false },
        )
    }
}

/**
 * v1.0.51: 回复通知策略选择行(从 MemorySettingsPage 移入)。
 *
 * 三档:never / when_unfocused / always,用 MuseChip 横向排列。
 */
@Composable
private fun NotificationPolicyRow(current: String, onChange: (String) -> Unit) {
    val replyNotificationCd = stringResource(R.string.settings_memory_reply_notification_cd)
    val replyNotificationTitle = stringResource(R.string.settings_memory_reply_notification)
    val replyNotificationSubtitle = stringResource(R.string.settings_memory_reply_notification_subtitle)
    val policies =
        listOf(
            "never" to R.string.settings_memory_policy_never,
            "when_unfocused" to R.string.settings_memory_policy_when_unfocused,
            "always" to R.string.settings_memory_policy_always,
        )
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = MuseIcons.cloudOff,
                contentDescription = replyNotificationCd,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = replyNotificationTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = replyNotificationSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            policies.forEach { (value, labelRes) ->
                MuseChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = stringResource(labelRes),
                )
            }
        }
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

/** 解析压缩模型的显示名称(未绑定回退默认文本)。 */

/**
 * v2.x: 会话缓存上限选择行(高级) — 后台保留的会话消息内存副本数。
 *
 * 越大:会话问切换越快,内存占用越高。热更生效(上限由 SettingsRepository 实时读取)。
 *
 * @param settings 设置仓库(读写 session_cache_size)
 * @param scope 协程作用域(保存操作)
 */
@Composable
private fun SessionCacheSizeRow(settings: SettingsRepository, scope: CoroutineScope) {
    val current by settings.sessionCacheSizeFlow.collectAsStateWithLifecycle(initialValue = 5)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MuseIcons.stack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_chat_session_cache),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_chat_session_cache_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3, 5, 8, 12).forEach { size ->
                    MuseChip(
                        selected = current == size,
                        onClick = { scope.launch { settings.saveSessionCacheSize(size) } },
                        label = size.toString(),
                    )
                }
            }
        }
    }
}
