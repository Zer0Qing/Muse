package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** v1.0.72: 每日上限"无限"档哨兵值(永不触发上限)。 */
private const val UNLIMITED_DAILY_SENTINEL = 9999

/**
 * v1.27: Agent 配置二级页。
 *
 * 集中 Agent 相关的所有配置:
 *  - Agent 默认助手选择(选哪个助手作为 Agent Tab 的默认陪伴助手)
 *  - 多 Agent 协作入口(跳转到团队管理)
 *  - 主动消息配置(开关 + 间隔 + 随机偏移 + 指定 Agent)
 *  - Agent 行为偏好(问候语 / 自动上下文等)
 */
@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    onOpenMultiAgentSettings: () -> Unit,
    onOpenAgentDm: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val proactiveConfig by settings.proactiveMessageConfigFlow.collectAsStateWithLifecycle(
        initialValue = ProactiveMessageConfig()
    )
    // v1.0.72: 每日总结推送开关
    val dailySummaryEnabled by settings.dailySummaryEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val nightPatrolEnabled by settings.nightPatrolEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val multiAgentConfig by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MultiAgentConfig()
    )
    // v1.52: Agent 当前模型选择 — 收集 providers / activeProviderId / selectedModelId
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val selectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    // v1.60-A: 工具模型(工具调用轮次使用,null 表示沿用主对话模型)
    val toolModelId by settings.toolModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.0.72: 主动消息测试发送(避免重复触发)
    var testSending by remember { mutableStateOf(false) }
    // v1.0.72: 发送概率选择弹窗
    var showProbabilityPicker by remember { mutableStateOf(false) }
    // v1.0.72: 主动消息 Runner(测试发送用)
    val proactiveRunner: io.zer0.muse.schedule.ProactiveMessageRunner = koinInject()

    var showAssistantPicker by remember { mutableStateOf(false) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    var showOffsetPicker by remember { mutableStateOf(false) }
    // v1.95: 允许发送时段选择弹窗(开始/结束小时)
    var showAllowedStartPicker by remember { mutableStateOf(false) }
    var showAllowedEndPicker by remember { mutableStateOf(false) }
    // v2.0 5.9: 每日上限与温度选择弹窗
    var showMaxDailyPicker by remember { mutableStateOf(false) }
    // v1.x: 保持后台运行引导弹窗(被动入口)
    var showKeepAliveGuide by remember { mutableStateOf(false) }
    var showTemperaturePicker by remember { mutableStateOf(false) }
    // v1.52: 模型选择弹窗
    var showModelPicker by remember { mutableStateOf(false) }
    // v1.60-A: 工具模型选择弹窗
    var showToolModelPicker by remember { mutableStateOf(false) }

    // M-AS2: 将 currentModelName / toolModelName 提到 item 外,用 remember 缓存避免每次重组都重算
    // 注意:stringResource 只能在 @Composable 上下文中调用,需先提取到 remember 外部
    val modelNotConfiguredText = stringResource(R.string.settings_agent_model_not_configured)
    val toolModelNotSetText = stringResource(R.string.settings_agent_tool_model_not_set)
    val toolModelNotSetInheritText = stringResource(R.string.settings_agent_tool_model_not_set_inherit)
    val currentModelName = remember(providers, activeProviderId, selectedModelId, modelNotConfiguredText) {
        val provider = providers.firstOrNull { it.id == activeProviderId }
            ?: providers.firstOrNull()
        val model = provider?.models?.firstOrNull { it.id == selectedModelId }
            ?: provider?.models?.firstOrNull()
        model?.name ?: modelNotConfiguredText
    }
    // L-ADP4 同类: 缓存 flatMap 结果避免每次重组都重算
    val allModels = remember(providers) { providers.flatMap { it.models } }
    val toolModelName = remember(toolModelId, allModels, toolModelNotSetText, toolModelNotSetInheritText) {
        toolModelId?.let { tid ->
            allModels.firstOrNull { it.id == tid }?.name ?: toolModelNotSetText
        } ?: toolModelNotSetInheritText
    }

    SettingsSubPageScaffold(title = "Agent", onBack = onBack) {
        // ── Agent 默认助手 ──
        item { SectionLabel(stringResource(R.string.settings_agent_section_assistant)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                val currentAgent = assistants?.find { it.id == proactiveConfig.agentId }
                    ?: assistants?.firstOrNull { it.id == "default" }
                    ?: assistants?.firstOrNull()
                SettingsItemRow(
                    icon = TablerIcons.User,
                    title = stringResource(R.string.settings_agent_default_assistant_title),
                    subtitle = if (assistants == null) stringResource(R.string.settings_agent_loading) else currentAgent?.name ?: stringResource(R.string.settings_agent_use_first_assistant),
                    onClick = { showAssistantPicker = true },
                ) {
                    ChevronRight()
                }
            }
        }

        // ── v1.52: Agent 当前模型 ──
        item { SectionLabel(stringResource(R.string.settings_agent_section_model)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    icon = TablerIcons.User,
                    title = stringResource(R.string.settings_agent_current_model),
                    // v1.0.74 fix: 提示用户这是全局默认模型,不是 Agent 专属(此前语义误导)
                    subtitle = currentModelName + " · " + stringResource(R.string.settings_agent_current_model_hint),
                    onClick = { showModelPicker = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                // v1.60-A: 工具模型 — 工具调用轮次使用的轻量模型,null 时沿用主对话模型
                SettingsItemRow(
                    icon = TablerIcons.Tools,
                    title = stringResource(R.string.settings_agent_tool_model_title),
                    subtitle = toolModelName,
                    onClick = { showToolModelPicker = true },
                ) {
                    ChevronRight()
                }
            }
        }

        // ── 多 Agent 协作 ──
        item { SectionLabel(stringResource(R.string.settings_agent_multi_agent_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    icon = TablerIcons.Users,
                    title = stringResource(R.string.settings_agent_collab_team),
                    subtitle = if (multiAgentConfig.enabled) {
                        stringResource(R.string.settings_agent_multi_agent_enabled, multiAgentConfig.teams.size)
                    } else {
                        stringResource(R.string.settings_agent_manage_collab_team)
                    },
                    onClick = onOpenMultiAgentSettings,
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                // v1.126: Agent 私信收件箱入口
                SettingsItemRow(
                    icon = TablerIcons.Mail,
                    title = stringResource(R.string.agent_dm_title),
                    subtitle = stringResource(R.string.settings_agent_dm_subtitle),
                    onClick = onOpenAgentDm,
                ) {
                    ChevronRight()
                }
            }
        }

        // ── 主动消息 ──
        item { SectionLabel(stringResource(R.string.settings_agent_proactive_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsSwitchRow(
                    icon = TablerIcons.Bell,
                    title = stringResource(R.string.settings_agent_proactive_title),
                    subtitle = stringResource(R.string.settings_agent_proactive_subtitle),
                    checked = proactiveConfig.enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveProactiveMessageConfig(proactiveConfig.copy(enabled = v)) }
                    },
                )
                if (proactiveConfig.enabled) {
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.CalendarTime,
                        title = stringResource(R.string.settings_agent_send_interval),
                        subtitle = intervalLabel(proactiveConfig.intervalMinutes),
                        onClick = { showIntervalPicker = true },
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Switch,
                        title = stringResource(R.string.settings_agent_random_offset),
                        subtitle = offsetLabel(proactiveConfig.randomOffsetMinutes),
                        onClick = { showOffsetPicker = true },
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    val senderAgent = assistants?.find { it.id == proactiveConfig.agentId }
                    SettingsItemRow(
                        icon = TablerIcons.User,
                        title = stringResource(R.string.settings_agent_send_agent),
                        subtitle = senderAgent?.name ?: stringResource(R.string.settings_agent_default_assistant_fallback),
                        onClick = { showAssistantPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v1.95: 允许发送时段(避免夜间打扰)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.CalendarTime,
                        title = stringResource(R.string.settings_agent_allowed_start),
                        subtitle = "${proactiveConfig.allowedHourStart}:00",
                        onClick = { showAllowedStartPicker = true },
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.CalendarTime,
                        title = stringResource(R.string.settings_agent_allowed_end),
                        subtitle = "${proactiveConfig.allowedHourEnd}:00",
                        onClick = { showAllowedEndPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v1.95: 仅Agent会话开关
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = TablerIcons.User,
                        title = stringResource(R.string.settings_agent_agent_only),
                        subtitle = stringResource(R.string.settings_agent_agent_only_subtitle),
                        checked = proactiveConfig.agentOnly,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveProactiveMessageConfig(proactiveConfig.copy(agentOnly = v)) }
                        },
                    )
                    // v2.0 5.9: 每日主动消息上限(可配置,替代 ScoreEngine 硬编码)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Bell,
                        title = stringResource(R.string.settings_agent_daily_limit),
                        subtitle = if (proactiveConfig.maxDailyMessages >= UNLIMITED_DAILY_SENTINEL) {
                            stringResource(R.string.settings_agent_daily_unlimited)
                        } else {
                            stringResource(R.string.settings_agent_daily_per_day, proactiveConfig.maxDailyMessages)
                        },
                        onClick = { showMaxDailyPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v2.0 5.9: LLM 调用温度(决策阶段用 temperature×0.5,生成阶段用本值)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Switch,
                        title = stringResource(R.string.settings_agent_temperature),
                        subtitle = "%.1f".format(proactiveConfig.temperature),
                        onClick = { showTemperaturePicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v1.0.72: 发送概率(决策通过后再掷骰子,0-100%)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Dice,
                        title = stringResource(R.string.settings_agent_send_probability),
                        subtitle = probabilityLabel(proactiveConfig.sendProbability),
                        onClick = { showProbabilityPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v1.0.72: 测试主动消息(模拟真实发送:LLM 总结 + 通知)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Bell,
                        title = stringResource(R.string.settings_agent_test_message),
                        subtitle = if (testSending) {
                            stringResource(R.string.settings_agent_test_generating)
                        } else {
                            stringResource(R.string.settings_agent_test_message_subtitle)
                        },
                        onClick = {
                            if (testSending) return@SettingsItemRow
                            testSending = true
                            scope.launch {
                                try {
                                    proactiveRunner.triggerTestSend()
                                    android.widget.Toast.makeText(context, context.getString(R.string.settings_agent_test_sent), android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                                    Logger.w("AgentSettingsPage", "测试主动消息失败: ${e.message}")
                                    android.widget.Toast.makeText(context, context.getString(R.string.settings_agent_test_failed, e.message), android.widget.Toast.LENGTH_SHORT).show()
                                } finally {
                                    testSending = false
                                }
                            }
                        },
                    ) {
                        ChevronRight()
                    }
                    // v1.0.72: 每日总结推送(每天 19:30 固定推送今日小结)
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = TablerIcons.CalendarStats,
                        title = stringResource(R.string.settings_agent_daily_summary),
                        subtitle = stringResource(R.string.settings_agent_daily_summary_subtitle),
                        checked = dailySummaryEnabled,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveDailySummaryEnabled(v) }
                        },
                    )
                    // v1.0.74: 深夜自主行动(时段外写日记不推送)
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = TablerIcons.Moon,
                        title = stringResource(R.string.settings_agent_night_patrol_title),
                        subtitle = stringResource(R.string.settings_agent_night_patrol_subtitle),
                        checked = nightPatrolEnabled,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveNightPatrolEnabled(v) }
                        },
                    )
                    // v1.x: 保持后台运行引导(被动入口,不主动打扰)
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = TablerIcons.Lifebuoy,
                        title = stringResource(R.string.settings_agent_keep_alive_title),
                        subtitle = stringResource(R.string.settings_agent_keep_alive_subtitle),
                        onClick = { showKeepAliveGuide = true },
                    ) {
                        ChevronRight()
                    }
                }
            }
        }
    }

    // ── 助手选择弹窗 ──
    if (showAssistantPicker) {
        MuseDialog(
            onDismissRequest = { showAssistantPicker = false },
            title = stringResource(R.string.settings_agent_select_assistant_title),
            content = {
                Column {
                    // v1.74: 局部变量捕获避免 !!(委托属性无法 smart-cast)
                    val assistantList = assistants
                    when {
                        assistantList == null -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.screen),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                        assistantList.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.settings_agent_no_assistants_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(MusePaddings.screen),
                            )
                        }
                        else -> {
                            assistantList.forEach { assistant ->
                                val selected = assistant.id == proactiveConfig.agentId ||
                                    (proactiveConfig.agentId.isBlank() && assistant.id == "default")
                                AgentPickerRow(
                                    assistant = assistant,
                                    selected = selected,
                                    onClick = {
                                        scope.launch {
                                            settings.saveProactiveMessageConfig(
                                                proactiveConfig.copy(agentId = assistant.id)
                                            )
                                        }
                                        showAssistantPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAssistantPicker = false },
        )
    }

    // ── 间隔选择弹窗(v1.30: 改为 Slider 自定义,步长 15 分钟,范围 15 分钟 ~ 24 小时)──
    if (showIntervalPicker) {
        var sliderMinutes by rememberSaveable { mutableStateOf(proactiveConfig.intervalMinutes) }
        // v1.95: 无极调节,不再对齐到 15 分钟步长(下限 15 分钟)
        val alignedMinutes = sliderMinutes.coerceIn(15, 1440)
        MuseDialog(
            onDismissRequest = { showIntervalPicker = false },
            title = stringResource(R.string.settings_agent_send_interval),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = intervalLabel(alignedMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_interval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedMinutes.toFloat(),
                        onValueChange = { sliderMinutes = it.toInt() },
                        valueRange = 15f..1440f,
                        // v1.95: 无极调节,去掉 steps(原 15 分钟步长 steps=93)
                        valueFormatter = { "${it.toInt()} min" },
                    )
                    // 分档参考标签
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.settings_agent_interval_15min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(stringResource(R.string.settings_agent_interval_24h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(
                            intervalMinutes = alignedMinutes,
                            // 如果随机偏移超过新间隔,自动钳制到间隔值
                            randomOffsetMinutes = proactiveConfig.randomOffsetMinutes.coerceIn(0, alignedMinutes),
                        )
                    )
                }
                showIntervalPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showIntervalPicker = false },
        )
    }

    // ── 随机偏移选择弹窗(v1.30: 改为 Slider 自定义,步长 15 分钟,范围 0 ~ 间隔值)──
    if (showOffsetPicker) {
        var sliderMinutes by rememberSaveable { mutableStateOf(proactiveConfig.randomOffsetMinutes) }
        val maxOffset = proactiveConfig.intervalMinutes
        // v1.95: 无极调节,不再对齐到 15 分钟步长(不超过当前间隔)
        val alignedMinutes = sliderMinutes.coerceIn(0, maxOffset)
        MuseDialog(
            onDismissRequest = { showOffsetPicker = false },
            title = stringResource(R.string.settings_agent_random_offset),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = offsetLabel(alignedMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_offset_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedMinutes.toFloat(),
                        onValueChange = { sliderMinutes = it.toInt() },
                        valueRange = 0f..maxOffset.toFloat(),
                        // v1.95: 无极调节,去掉 steps(原 15 分钟步长)
                        valueFormatter = { "${it.toInt()} min" },
                    )
                    // 分档参考标签
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.settings_agent_offset_off_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(intervalLabel(maxOffset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(randomOffsetMinutes = alignedMinutes)
                    )
                }
                showOffsetPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showOffsetPicker = false },
        )
    }

    // ── v1.95: 允许时段开始选择弹窗(无极调节,0-23 小时)──
    if (showAllowedStartPicker) {
        var sliderHour by rememberSaveable { mutableStateOf(proactiveConfig.allowedHourStart) }
        val alignedHour = sliderHour.coerceIn(0, 23)
        MuseDialog(
            onDismissRequest = { showAllowedStartPicker = false },
            title = stringResource(R.string.settings_agent_allowed_start),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${alignedHour}:00",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_allowed_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedHour.toFloat(),
                        onValueChange = { sliderHour = it.toInt() },
                        valueRange = 0f..23f,
                        // v1.95: 无极调节,无步长
                        valueFormatter = { "${it.toInt()}:00" },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("23:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(allowedHourStart = alignedHour))
                }
                showAllowedStartPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAllowedStartPicker = false },
        )
    }

    // ── v1.95: 允许时段结束选择弹窗(无极调节,0-23 小时)──
    if (showAllowedEndPicker) {
        var sliderHour by rememberSaveable { mutableStateOf(proactiveConfig.allowedHourEnd) }
        val alignedHour = sliderHour.coerceIn(0, 23)
        MuseDialog(
            onDismissRequest = { showAllowedEndPicker = false },
            title = stringResource(R.string.settings_agent_allowed_end),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${alignedHour}:00",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_allowed_end_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedHour.toFloat(),
                        onValueChange = { sliderHour = it.toInt() },
                        valueRange = 0f..23f,
                        // v1.95: 无极调节,无步长
                        valueFormatter = { "${it.toInt()}:00" },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("23:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(allowedHourEnd = alignedHour))
                }
                showAllowedEndPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAllowedEndPicker = false },
        )
    }

    // ── v2.0 5.9: 每日上限选择弹窗(1 ~ 10 条/天 + 无限)──
    // v1.0.72: 新增"无限"档(哨兵值 9999,永不触发上限)
    if (showMaxDailyPicker) {
        var sliderValue by rememberSaveable { mutableStateOf(proactiveConfig.maxDailyMessages.coerceIn(1, 10).toFloat()) }
        var unlimited by rememberSaveable {
            mutableStateOf(proactiveConfig.maxDailyMessages >= UNLIMITED_DAILY_SENTINEL)
        }
        val alignedValue = sliderValue.toInt().coerceIn(1, 10)
        val dailyCountFmt = stringResource(R.string.settings_agent_daily_count)
        MuseDialog(
            onDismissRequest = { showMaxDailyPicker = false },
            title = stringResource(R.string.settings_agent_daily_limit),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (unlimited) {
                            stringResource(R.string.settings_agent_daily_unlimited)
                        } else {
                            stringResource(R.string.settings_agent_daily_per_day, alignedValue)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = if (unlimited) {
                            stringResource(R.string.settings_agent_daily_unlimited_hint)
                        } else {
                            stringResource(R.string.settings_agent_daily_limited_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue.toFloat(),
                        onValueChange = {
                            unlimited = false
                            sliderValue = it
                        },
                        valueRange = 1f..10f,
                        valueFormatter = { dailyCountFmt.format(it.toInt()) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_agent_daily_min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        // v1.0.72: 无限档按钮
                        TextButton(onClick = { unlimited = true }) {
                            Text(
                                text = if (unlimited) {
                                    stringResource(R.string.settings_agent_daily_unlimited_checked)
                                } else {
                                    stringResource(R.string.settings_agent_daily_set_unlimited)
                                },
                                color = if (unlimited) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Text(stringResource(R.string.settings_agent_daily_max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(
                            maxDailyMessages = if (unlimited) UNLIMITED_DAILY_SENTINEL else alignedValue,
                        )
                    )
                }
                showMaxDailyPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMaxDailyPicker = false },
        )
    }

    // ── v2.0 5.9: 生成温度选择弹窗(0.0 ~ 2.0,步长 0.1)──
    if (showTemperaturePicker) {
        var sliderValue by rememberSaveable { mutableStateOf(proactiveConfig.temperature) }
        // 对齐到 0.1 步长:kotlin.math.round(Float) 返回 Float,避免 Math.round 重载歧义
        val alignedValue = (kotlin.math.round(sliderValue * 10f) / 10f).coerceIn(0f, 2f)
        MuseDialog(
            onDismissRequest = { showTemperaturePicker = false },
            title = stringResource(R.string.settings_agent_temperature),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "%.1f".format(alignedValue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_temperature_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..2f,
                        valueFormatter = { "%.1f".format(it) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.settings_agent_temperature_stable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(stringResource(R.string.settings_agent_temperature_creative), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(temperature = alignedValue))
                }
                showTemperaturePicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTemperaturePicker = false },
        )
    }

    // ── v1.0.72: 发送概率选择弹窗(0-100%,步长 5)──
    if (showProbabilityPicker) {
        var sliderValue by rememberSaveable { mutableStateOf(proactiveConfig.sendProbability) }
        val alignedValue = (sliderValue / 5 * 5).coerceIn(0, 100)
        MuseDialog(
            onDismissRequest = { showProbabilityPicker = false },
            title = stringResource(R.string.settings_agent_send_probability),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = probabilityLabel(alignedValue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_send_probability_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue.toFloat(),
                        onValueChange = { sliderValue = it.toInt() },
                        valueRange = 0f..100f,
                        valueFormatter = { "${it.toInt()}%" },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.settings_agent_probability_never), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(stringResource(R.string.settings_agent_probability_always), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(sendProbability = alignedValue))
                }
                showProbabilityPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showProbabilityPicker = false },
        )
    }

    // ── v1.x: 保持后台运行引导(被动入口,点击才弹出)──
    if (showKeepAliveGuide) {
        KeepAliveGuideDialog(onDismiss = { showKeepAliveGuide = false })
    }

    // ── v1.52: 模型选择弹窗 ──
    if (showModelPicker) {
        // v1.52 修订: 当 activeProviderId/selectedModelId 为 null 时,显示逻辑会回退到
        // 第一个 Provider 的首个模型;弹窗选中态需与显示逻辑一致,否则用户看到"有当前模型"
        // 但弹窗无任何勾选,产生困惑。
        val effectiveProviderId = activeProviderId ?: providers.firstOrNull()?.id
        val effectiveModelId = selectedModelId
            ?: providers.firstOrNull { it.id == effectiveProviderId }?.models?.firstOrNull()?.id
        MuseDialog(
            onDismissRequest = { showModelPicker = false },
            title = stringResource(R.string.settings_agent_select_model),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    providers.forEach { provider ->
                        if (provider.models.isNotEmpty()) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            provider.models.forEach { model ->
                                val isSelected = model.id == effectiveModelId &&
                                    provider.id == effectiveProviderId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                // 切换 Provider + 模型
                                                settings.setActiveProvider(provider.id)
                                                settings.saveSelectedModel(model.id)
                                            }
                                            showModelPicker = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isSelected) {
                                        Icon(TablerIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (providers.isEmpty() || providers.all { it.models.isEmpty() }) {
                        Text(
                            text = stringResource(R.string.settings_agent_no_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(MusePaddings.screen),
                        )
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showModelPicker = false },
        )
    }

    // ── v1.60-A: 工具模型选择弹窗 ──
    if (showToolModelPicker) {
        MuseDialog(
            onDismissRequest = { showToolModelPicker = false },
            title = stringResource(R.string.settings_agent_select_tool_model),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 清除选项:沿用主对话模型(toolModelId = null)
                    val isCleared = toolModelId == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { settings.saveToolModel(null) }
                                showToolModelPicker = false
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_agent_clear_tool_model),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCleared) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCleared) {
                            Icon(TablerIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                    // 跨 Provider 列出所有模型,选中即保存其 id(不切换激活 Provider)
                    providers.forEach { provider ->
                        if (provider.models.isNotEmpty()) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            provider.models.forEach { model ->
                                val isSelected = model.id == toolModelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch { settings.saveToolModel(model.id) }
                                            showToolModelPicker = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isSelected) {
                                        Icon(TablerIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (providers.isEmpty() || providers.all { it.models.isEmpty() }) {
                        Text(
                            text = stringResource(R.string.settings_agent_no_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(MusePaddings.screen),
                        )
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showToolModelPicker = false },
        )
    }
}

/**
 * 助手选择行:头像 + 名字 + 单选指示。
 */
@Composable
private fun AgentPickerRow(
    assistant: AssistantEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar(
            assistant = assistant,
            avatarSize = 36.dp,
            modifier = Modifier.clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = assistant.name.ifBlank { stringResource(R.string.settings_agent_unnamed_assistant) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(TablerIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 把间隔分钟数转成可读文案。
 * v1.30: 从小时单位改为分钟单位,支持 15/30/45 分钟等细分粒度。
 */
@Composable
private fun intervalLabel(minutes: Int): String {
    val m = minutes.coerceAtLeast(15)
    return when {
        m < 60 -> stringResource(R.string.settings_agent_interval_minutes, m)
        m == 60 -> stringResource(R.string.settings_agent_interval_hourly)
        m % 60 == 0 -> {
            val h = m / 60
            if (h == 24) stringResource(R.string.settings_agent_interval_daily)
            else stringResource(R.string.settings_agent_interval_hours, h)
        }
        else -> {
            val h = m / 60
            val rem = m % 60
            stringResource(R.string.settings_agent_interval_hours_minutes, h, rem)
        }
    }
}

/**
 * 把随机偏移分钟数转成可读文案。
 * v1.30: 从小时单位改为分钟单位,支持 15/30/45 分钟等细分粒度。
 */
@Composable
private fun offsetLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.settings_agent_offset_disabled)
    return when {
        minutes < 60 -> stringResource(R.string.settings_agent_offset_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.settings_agent_offset_hours, minutes / 60)
        else -> stringResource(R.string.settings_agent_offset_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** v1.0.72: 发送概率文案。 */
@Composable
private fun probabilityLabel(probability: Int): String = when {
    probability >= 100 -> stringResource(R.string.settings_agent_probability_always_label)
    probability <= 0 -> stringResource(R.string.settings_agent_probability_never_label)
    else -> stringResource(R.string.settings_agent_probability_value, probability)
}
