package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
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
 * 全局默认工具权限与单工具策略统一归属工具设置页，本页仅展示当前档位摘要并提供跳转入口。
 */
@Composable
fun ChatSettingsPage(
    onBack: () -> Unit,
    /** v2.6.0: 打开消息快捷按钮自定义页。 */
    onOpenQuickActions: () -> Unit = {},
    /** 打开工具与权限设置页（全局默认审批策略主归属页）。 */
    onOpenToolsSettings: () -> Unit = {},
    /** 打开表情包库独立设置页。 */
    onOpenStickers: () -> Unit = {},
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
        // ── 0. 工具调用批准（摘要跳转至工具设置页，避免两页重复配置同一策略）──
        item(key = "chat_tool_approval") {
            val (modeLabelRes, modeDescRes) = when (permissionMode) {
                SessionPermissionMode.TRUSTED ->
                    R.string.settings_chat_tool_approval_trusted to R.string.settings_chat_tool_approval_trusted_desc
                SessionPermissionMode.ASK ->
                    R.string.settings_chat_tool_approval_ask to R.string.settings_chat_tool_approval_ask_desc
                SessionPermissionMode.STRICT ->
                    R.string.settings_chat_tool_approval_strict to R.string.settings_chat_tool_approval_strict_desc
            }
            CardGroup(title = { Text(stringResource(R.string.settings_chat_tool_approval_section)) }) {
                navigationItem(
                    key = "tool_approval_mode_nav",
                    title = stringResource(R.string.settings_chat_tool_approval_title),
                    subtitle = stringResource(modeDescRes),
                    value = stringResource(modeLabelRes),
                    onClick = onOpenToolsSettings,
                    leadingContent = {
                        Icon(
                            MuseIcons.shieldCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        // ── 1. 消息显示 ──
        item(key = "chat_message_display") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_message_display)) }) {
                switchItem(
                    key = "show_mood",
                    title = stringResource(R.string.settings_chat_show_mood),
                    checked = prefs.showMoodBlock,
                    onCheckedChange = { v -> update { it.copy(showMoodBlock = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.moodSmile,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_reasoning",
                    title = stringResource(R.string.settings_chat_show_reasoning),
                    checked = prefs.showReasoning,
                    onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.brain,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "force_reasoning",
                    title = stringResource(R.string.settings_chat_force_reasoning),
                    checked = prefs.forceReasoning,
                    onCheckedChange = { v -> update { it.copy(forceReasoning = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.brain,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_reflection",
                    title = stringResource(R.string.settings_chat_show_reflection),
                    checked = prefs.showReflectionBlock,
                    onCheckedChange = { v -> update { it.copy(showReflectionBlock = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.reflect,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_model",
                    title = stringResource(R.string.settings_chat_show_model),
                    checked = prefs.showModelName,
                    onCheckedChange = { v -> update { it.copy(showModelName = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.switch,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_assistant_name",
                    title = stringResource(R.string.settings_chat_show_assistant_name),
                    checked = prefs.showAssistantName,
                    onCheckedChange = { v -> update { it.copy(showAssistantName = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.user,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_user_name",
                    title = stringResource(R.string.settings_chat_show_user_name),
                    checked = prefs.showUserName,
                    onCheckedChange = { v -> update { it.copy(showUserName = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.user,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_timestamp",
                    title = stringResource(R.string.settings_chat_show_timestamp),
                    checked = prefs.showTimestamp,
                    onCheckedChange = { v -> update { it.copy(showTimestamp = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.calendarTime,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        // ── 跨会话衔接 ──
        item(key = "chat_carry_over") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_carry_over_section)) }) {
                switchItem(
                    key = "carry_over_toggle",
                    title = stringResource(R.string.settings_chat_carry_over),
                    checked = prefs.carryOverEnabled,
                    onCheckedChange = { v -> update { it.copy(carryOverEnabled = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.chat,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                if (prefs.carryOverEnabled) {
                    content(key = "carry_over_slider") {
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_chat_carry_over_count, prefs.carryOverMessageCount),
                            subtitle = stringResource(R.string.settings_chat_carry_over_count_hint, prefs.carryOverMessageCount / 2),
                            value = prefs.carryOverMessageCount.toFloat(),
                            valueRange = 4f..40f,
                            steps = 17,
                            valueText = "${prefs.carryOverMessageCount}",
                            onValueChange = { v ->
                                val snapped = (v.toInt() / 2 * 2).coerceIn(4, 40)
                                update { it.copy(carryOverMessageCount = snapped) }
                            },
                        )
                    }
                }
            }
        }

        // ── 消息快捷按钮自定义 ──
        item(key = "chat_quick_actions") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_quick_actions)) }) {
                navigationItem(
                    key = "quick_actions_entry",
                    title = stringResource(R.string.settings_chat_quick_customize),
                    leadingContent = {
                        Icon(
                            MuseIcons.copy,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = onOpenQuickActions,
                )
            }
        }

        // ── 生成风格 ──
        item(key = "chat_generation_style") {
            var temperatureDraft by remember { mutableStateOf(prefs.globalTemperature) }
            LaunchedEffect(prefs.globalTemperature) {
                if (prefs.globalTemperature != temperatureDraft) temperatureDraft = prefs.globalTemperature
            }
            LaunchedEffect(temperatureDraft) {
                if (temperatureDraft != prefs.globalTemperature) {
                    delay(400)
                    update { it.copy(globalTemperature = temperatureDraft) }
                }
            }
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
            CardGroup(title = { Text(stringResource(R.string.settings_chat_generation_style)) }) {
                switchItem(
                    key = "reply_user_lang",
                    title = stringResource(R.string.settings_chat_reply_language),
                    checked = prefs.replyInUserLanguageEnabled,
                    onCheckedChange = { v -> update { it.copy(replyInUserLanguageEnabled = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.languages,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                content(key = "global_temp") {
                    SettingsSliderRow(
                        icon = MuseIcons.temperature,
                        title = stringResource(R.string.settings_chat_randomness),
                        subtitle = stringResource(temperatureHint(temperatureDraft)),
                        value = temperatureDraft,
                        valueRange = 0f..2f,
                        steps = 19,
                        valueText = "%.1f".format(temperatureDraft),
                        onValueChange = { temperatureDraft = it },
                    )
                }
                content(key = "response_style") {
                    SettingsSegmentedRow(
                        icon = MuseIcons.typography,
                        title = stringResource(R.string.settings_chat_style),
                        options = styleOptions,
                        selectedIndex = selectedStyleIndex,
                        onSelectedChange = { idx ->
                            val value = when (idx) {
                                0 -> "concise"
                                2 -> "detailed"
                                else -> "balanced"
                            }
                            update { it.copy(responseStyle = value) }
                        },
                    )
                }
                content(key = "response_tone") {
                    SettingsSegmentedRow(
                        icon = MuseIcons.switch,
                        title = stringResource(R.string.settings_chat_tone),
                        options = toneOptions,
                        selectedIndex = selectedToneIndex,
                        onSelectedChange = { idx ->
                            val value = when (idx) {
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
        }

        // ── 卡片与生成式界面（从外观页归位聊天页）──
        item(key = "chat_generative_ui") {
            GenerativeUiSection(prefs = prefs, update = ::update)
        }

        // ── 2. 默认展开状态 ──
        item(key = "chat_default_expand") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_default_expand)) }) {
                switchItem(
                    key = "expand_mood",
                    title = stringResource(R.string.settings_chat_mood_expand),
                    checked = prefs.moodExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(moodExpandedByDefault = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.expand,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "show_expand_btn",
                    title = stringResource(R.string.settings_chat_expand_button),
                    checked = prefs.showExpandButton,
                    onCheckedChange = { v -> update { it.copy(showExpandButton = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.expand,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "expand_reasoning",
                    title = stringResource(R.string.settings_chat_reasoning_expand),
                    checked = prefs.reasoningExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reasoningExpandedByDefault = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.expand,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "expand_reflection",
                    title = stringResource(R.string.settings_chat_reflection_expand),
                    checked = prefs.reflectionExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reflectionExpandedByDefault = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.expand,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        // ── 3. 交互行为 ──
        item(key = "chat_interaction") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_interaction)) }) {
                switchItem(
                    key = "stream_resp",
                    title = stringResource(R.string.settings_chat_streaming),
                    checked = prefs.streamResponse,
                    onCheckedChange = { v -> update { it.copy(streamResponse = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.chat,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "fast_reply",
                    title = stringResource(R.string.settings_chat_fast_reply),
                    checked = prefs.fastReplyMode,
                    onCheckedChange = { v -> update { it.copy(fastReplyMode = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.arrowRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "auto_scroll",
                    title = stringResource(R.string.settings_chat_auto_scroll),
                    checked = prefs.autoScrollToBottom,
                    onCheckedChange = { v -> update { it.copy(autoScrollToBottom = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.arrowRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "volume_scroll",
                    title = stringResource(R.string.settings_chat_volume_scroll),
                    checked = prefs.volumeKeyScroll,
                    onCheckedChange = { v -> update { it.copy(volumeKeyScroll = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.volume,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "enter_send",
                    title = stringResource(R.string.settings_chat_enter_send),
                    checked = prefs.enterToSend,
                    onCheckedChange = { v -> update { it.copy(enterToSend = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.handFinger,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "haptic",
                    title = stringResource(R.string.settings_chat_haptic),
                    checked = prefs.hapticFeedback,
                    onCheckedChange = { v -> update { it.copy(hapticFeedback = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.bolt,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "predictive_back",
                    title = stringResource(R.string.settings_chat_predictive_back),
                    checked = prefs.predictiveBackEnabled,
                    onCheckedChange = { v -> update { it.copy(predictiveBackEnabled = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.handFinger,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        // ── 4. 高级 ──
        item(key = "chat_advanced") {
            val titlePromptSubtitle = customTitlePrompt?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.settings_chat_custom_title_prompt_default)
            CardGroup(title = { Text(stringResource(R.string.settings_common_advanced)) }) {
                switchItem(
                    key = "show_tool_calls",
                    title = stringResource(R.string.settings_chat_show_tool_calls),
                    checked = prefs.showToolCallDetails,
                    onCheckedChange = { v -> update { it.copy(showToolCallDetails = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.eye,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "pause_tools",
                    title = stringResource(R.string.settings_chat_pause_tool_execution),
                    checked = prefs.pauseToolExecution,
                    onCheckedChange = { v -> update { it.copy(pauseToolExecution = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.pause,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "use_24h",
                    title = stringResource(R.string.settings_chat_24h),
                    checked = prefs.use24Hour,
                    onCheckedChange = { v -> update { it.copy(use24Hour = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.calendarTime,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "perf_mode",
                    title = stringResource(R.string.settings_chat_performance_mode),
                    checked = prefs.performanceMode,
                    onCheckedChange = { v -> update { it.copy(performanceMode = v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.gauge,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                content(key = "session_cache_size") {
                    SessionCacheSizeRow(settings = settings, scope = scope)
                }
                switchItem(
                    key = "token_estimate",
                    title = stringResource(R.string.settings_chat_token_estimate),
                    checked = tokenEstimateEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveTokenEstimateEnabled(v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.chartBar,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                switchItem(
                    key = "paste_as_file",
                    title = stringResource(R.string.settings_chat_paste_as_file),
                    checked = pasteAsFileEnabled,
                    onCheckedChange = { v -> scope.launch { settings.savePasteAsFileEnabled(v) } },
                    leadingContent = {
                        Icon(
                            MuseIcons.clipboard,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                if (pasteAsFileEnabled) {
                    val thresholdOptions = listOf("1000", "2000", "5000")
                    val thresholdIndex = when (pasteAsFileThreshold) {
                        1000 -> 0
                        5000 -> 2
                        else -> 1
                    }
                    content(key = "paste_threshold") {
                        SettingsSegmentedRow(
                            icon = MuseIcons.clipboard,
                            title = stringResource(R.string.settings_chat_paste_as_file_threshold),
                            subtitle = stringResource(R.string.settings_chat_paste_as_file_threshold_subtitle, pasteAsFileThreshold),
                            options = thresholdOptions,
                            selectedIndex = thresholdIndex,
                            onSelectedChange = { idx ->
                                val threshold = when (idx) {
                                    0 -> 1000
                                    2 -> 5000
                                    else -> 2000
                                }
                                scope.launch { settings.savePasteAsFileThreshold(threshold) }
                            },
                        )
                    }
                }
                navigationItem(
                    key = "custom_title_prompt",
                    title = stringResource(R.string.settings_chat_custom_title_prompt),
                    subtitle = titlePromptSubtitle,
                    leadingContent = {
                        Icon(
                            MuseIcons.edit,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = {
                        titlePromptDraft = customTitlePrompt.orEmpty()
                        showTitlePromptDialog = true
                    },
                )
            }
        }

        // ── 上下文与工具轮次 ──
        item(key = "chat_context_section") {
            val roundOptions =
                listOf(
                    stringResource(R.string.settings_chat_tool_rounds_unlimited),
                    "100",
                    "50",
                    "25",
                )
            val roundIndex = when (toolLoopMaxRounds) {
                100 -> 1
                50 -> 2
                25 -> 3
                else -> 0
            }
            CardGroup(title = { Text(stringResource(R.string.settings_chat_context_section)) }) {
                content(key = "tool_loop_rounds") {
                    SettingsSegmentedRow(
                        icon = MuseIcons.refresh,
                        title = stringResource(R.string.settings_chat_tool_rounds),
                        options = roundOptions,
                        selectedIndex = roundIndex,
                        onSelectedChange = { idx ->
                            val limit = when (idx) {
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
        }

        // ── 通知 ──
        item(key = "chat_notification_section") {
            CardGroup(title = { Text(stringResource(R.string.settings_memory_notification_section)) }) {
                content(key = "notification_policy") {
                    NotificationPolicyRow(
                        current = notificationPolicy,
                        onChange = { v -> scope.launch { settings.saveNotificationPolicy(v) } },
                    )
                }
            }
        }

        // ── 表情包库 ──
        item(key = "chat_stickers_section") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_sticker_section)) }) {
                navigationItem(
                    key = "chat_stickers_nav",
                    title = stringResource(R.string.settings_chat_sticker_section),
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sticker,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = onOpenStickers,
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
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch { settings.saveCustomTitlePrompt(titlePromptDraft.trim().takeIf { it.isNotBlank() }) }
                showTitlePromptDialog = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showTitlePromptDialog = false },
        )
    }
}

/**
 * 卡片与生成式界面：是否允许模型用可交互卡片承载内容，以及是否主动优先卡片而不是长文字。
 */
@Composable
private fun GenerativeUiSection(prefs: ChatPreferences, update: ((ChatPreferences) -> ChatPreferences) -> Unit) {
    CardGroup(title = { Text(stringResource(R.string.settings_screen_group_cards)) }) {
        switchItem(
            key = "gen_ui_enabled",
            title = stringResource(R.string.settings_chat_generative_ui),
            checked = prefs.generativeUiEnabled,
            onCheckedChange = { v -> update { it.copy(generativeUiEnabled = v) } },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.sparkle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
        if (prefs.generativeUiEnabled) {
            switchItem(
                key = "gen_ui_prefer_card",
                title = stringResource(R.string.settings_chat_prefer_card_output),
                checked = prefs.preferCardOutput,
                onCheckedChange = { v -> update { it.copy(preferCardOutput = v) } },
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.sparkle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
            )
        }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
