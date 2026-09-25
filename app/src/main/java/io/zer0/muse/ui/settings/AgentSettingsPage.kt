package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.zer0.muse.data.proactive.ProactivePace
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

/**
 * v1.27: Agent 配置二级页。
 *
 * 集中 Agent 相关的所有配置:
 *  - Agent 默认助手选择(选哪个助手作为 Agent Tab 的默认陪伴助手)
 *  - 多 Agent 协作入口(跳转到团队管理)
 *  - 主动消息配置(开关 + 间隔 + 随机偏移 + 指定 Agent)
 *  - Agent 行为偏好(问候语 / 自动上下文等)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    onOpenMultiAgentSettings: () -> Unit,
    onOpenAgentDm: () -> Unit = {},
    /** 独立主动消息页复用同一套配置组件时隐藏 Agent/协作区。 */
    proactiveOnly: Boolean = false,
    showProactiveSettings: Boolean = false,
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val proactiveConfig by settings.proactiveMessageConfigFlow.collectAsStateWithLifecycle(
        initialValue = ProactiveMessageConfig()
    )
    // v1.0.72: 每日总结推送开关
    val dailySummaryEnabled by settings.dailySummaryEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // v1.xxx: 每日总结时段(24 小时制整点小时)
    val dailySummarySlots by settings.dailySummarySlotsFlow.collectAsStateWithLifecycle(initialValue = listOf(0, 9, 12, 21))
    // v1.xxx: AI 朋友圈每日条数(0 = 关闭)
    val dailyMomentCount by settings.dailyMomentCountFlow.collectAsStateWithLifecycle(initialValue = 2)
    // v1.xxx: 后台调度总控开关
    val scheduleWorkEnabled by settings.scheduleWorkEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
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
    // v2.0: 子代理模型(后台子 agent 使用,null 表示沿用主对话模型)
    val subagentModelId by settings.subagentModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.0.72: 主动消息测试发送(避免重复触发)
    var testSending by remember { mutableStateOf(false) }
    // P0: 主动程度 / 免打扰时段 / 主动内容 / 高级 弹窗
    var showPacePicker by remember { mutableStateOf(false) }
    var showQuietHoursPicker by remember { mutableStateOf(false) }
    var showContentPicker by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    // v1.0.72: 主动消息 Runner(测试发送用)
    val proactiveRunner: io.zer0.muse.schedule.ProactiveMessageRunner = koinInject()

    var showAssistantPicker by remember { mutableStateOf(false) }
    var showOffsetPicker by remember { mutableStateOf(false) }
    // v1.xxx: 每日总结时段输入弹窗
    var showSlotsDialog by remember { mutableStateOf(false) }
    // v1.xxx: AI 朋友圈每日条数选择弹窗
    var showMomentCountPicker by remember { mutableStateOf(false) }
    // v1.x: 保持后台运行引导弹窗(被动入口)
    var showKeepAliveGuide by remember { mutableStateOf(false) }
    var showTemperaturePicker by remember { mutableStateOf(false) }
    // v1.52: 模型选择弹窗
    var showModelPicker by remember { mutableStateOf(false) }
    // v1.60-A: 工具模型选择弹窗
    var showToolModelPicker by remember { mutableStateOf(false) }
    // v2.0: 子代理模型选择弹窗
    var showSubagentModelPicker by remember { mutableStateOf(false) }

    // P0: 主动消息收敛后的摘要文案(在 composable 作用域预取,供列表项 subtitle 使用)
    val paceLabel = ProactivePace.from(
        proactiveConfig.intervalMinutes,
        proactiveConfig.sendProbability,
        proactiveConfig.maxDailyMessages,
    )?.let { paceTitle(it) } ?: stringResource(R.string.settings_agent_pace_custom)
    val quietHoursLabel = String.format(
        Locale.getDefault(),
        "%02d:00 – %02d:00",
        proactiveConfig.allowedHourEnd,
        proactiveConfig.allowedHourStart,
    )
    val contentSummary = buildList {
        if (dailySummaryEnabled) add(stringResource(R.string.settings_agent_content_daily_summary))
        if (nightPatrolEnabled) add(stringResource(R.string.settings_agent_content_night_diary))
        if (dailyMomentCount > 0) add(stringResource(R.string.settings_agent_content_moment))
    }
    val contentLabel = if (contentSummary.isEmpty()) {
        stringResource(R.string.settings_agent_content_none)
    } else {
        contentSummary.joinToString(" / ")
    }

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
    // v2.0: 子代理模型显示名(同工具模型规则)
    val subagentModelName = remember(subagentModelId, allModels, toolModelNotSetText, toolModelNotSetInheritText) {
        subagentModelId?.let { tid ->
            allModels.firstOrNull { it.id == tid }?.name ?: toolModelNotSetText
        } ?: toolModelNotSetInheritText
    }

    SettingsSubPageScaffold(
        title = if (proactiveOnly) stringResource(R.string.settings_agent_proactive_title) else "Agent",
        onBack = onBack,
    ) {
        if (!proactiveOnly) {
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
                    icon = MuseIcons.user,
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
                    icon = MuseIcons.user,
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
                    icon = MuseIcons.wrench,
                    title = stringResource(R.string.settings_agent_tool_model_title),
                    subtitle = toolModelName,
                    onClick = { showToolModelPicker = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                // v2.0: 子代理模型 — 后台子 agent 使用的轻量模型
                SettingsItemRow(
                    icon = MuseIcons.bolt,
                    title = stringResource(R.string.settings_agent_subagent_model_title),
                    subtitle = subagentModelName,
                    onClick = { showSubagentModelPicker = true },
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
                    icon = MuseIcons.users,
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
                    icon = MuseIcons.mail,
                    title = stringResource(R.string.agent_dm_title),
                    subtitle = stringResource(R.string.settings_agent_dm_subtitle),
                    onClick = onOpenAgentDm,
                ) {
                    ChevronRight()
                }
            }
        }

        }
        if (showProactiveSettings) {
        // ── 主动消息 ──
        item { SectionLabel(stringResource(R.string.settings_agent_proactive_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsSwitchRow(
                    icon = MuseIcons.bell,
                    title = stringResource(R.string.settings_agent_proactive_title),
                    subtitle = stringResource(R.string.settings_agent_proactive_subtitle),
                    checked = proactiveConfig.enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveProactiveMessageConfig(proactiveConfig.copy(enabled = v)) }
                    },
                )
                if (proactiveConfig.enabled) {
                    // UI-FIX: 子分组标签 —— 原先十几行开关平铺在同一层，看不出哪几个是一组
                    // P0 收敛: 「间隔 / 偏移 / 概率 / 每日上限」四个实现参数合并为一个
                    // 用户能表达的「主动程度」档位(少/标准/多);随机偏移与温度降到「高级」。
                    ProactiveGroupLabel(stringResource(R.string.settings_agent_group_pace))
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.calendarTime,
                        title = stringResource(R.string.settings_agent_pace_title),
                        subtitle = paceLabel,
                        onClick = { showPacePicker = true },
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    // P0 收敛: 「允许时段开始 / 结束」两个滑块合并为一个「免打扰时段」范围
                    SettingsItemRow(
                        icon = MuseIcons.moon,
                        title = stringResource(R.string.settings_agent_quiet_hours_title),
                        subtitle = quietHoursLabel,
                        onClick = { showQuietHoursPicker = true },
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    val senderAgent = assistants?.find { it.id == proactiveConfig.agentId }
                    SettingsItemRow(
                        icon = MuseIcons.user,
                        title = stringResource(R.string.settings_agent_send_agent),
                        subtitle = senderAgent?.name ?: stringResource(R.string.settings_agent_default_assistant_fallback),
                        onClick = { showAssistantPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // P0 收敛: 温度 / 概率 / 测试发送 / 仅 Agent 会话 收进「高级」
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.sliders,
                        title = stringResource(R.string.settings_agent_group_advanced),
                        subtitle = stringResource(R.string.settings_agent_advanced_subtitle),
                        onClick = { showAdvancedDialog = true },
                    ) {
                        ChevronRight()
                    }
                    // P0 收敛: 每日总结 / 深夜日记 / 朋友圈 收进「主动内容」入口
                    ProactiveGroupLabel(stringResource(R.string.settings_agent_group_daily))
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.calendarStats,
                        title = stringResource(R.string.settings_agent_content_title),
                        subtitle = contentLabel,
                        onClick = { showContentPicker = true },
                    ) {
                        ChevronRight()
                    }
                }
            }
        }

        // P0: 「后台与可靠性」独立分区 —— 后台任务总控管的是全部周期后台任务,
        // 与主动消息开关解耦;保持后台运行引导也归到这里。
        item { SectionLabel(stringResource(R.string.settings_agent_group_background)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
                SettingsSwitchRow(
                    icon = MuseIcons.switch,
                    title = stringResource(R.string.settings_agent_schedule_work_title),
                    subtitle = stringResource(R.string.settings_agent_schedule_work_subtitle),
                    checked = scheduleWorkEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveScheduleWorkEnabled(v) }
                    },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.lifebuoy,
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

    // ── v1.xxx: 每日总结时段选择弹窗(整点 chip 多选,替代此前裸文本逗号输入;仍以逗号分隔小时存储)──
    if (showSlotsDialog) {
        // 仅保留合法 0-23 的初始值;用 remember(非 saveable)保存 Set,避免非 Bundle 类型在进程重建时崩溃
        var selectedSlots by remember { mutableStateOf(dailySummarySlots.filter { it in 0..23 }.toSet()) }
        // 按自然时段分组展示 0-23 全部整点,聚焦常见时段
        val slotGroups = listOf(
            stringResource(R.string.settings_agent_daily_summary_slots_group_early) to listOf(0, 1, 2, 3, 4, 5),
            stringResource(R.string.settings_agent_daily_summary_slots_group_morning) to listOf(6, 7, 8, 9, 10, 11),
            stringResource(R.string.settings_agent_daily_summary_slots_group_noon) to listOf(12, 13, 14, 15, 16, 17),
            stringResource(R.string.settings_agent_daily_summary_slots_group_evening) to listOf(18, 19, 20, 21, 22, 23),
        )
        val hasSelection = selectedSlots.isNotEmpty()
        MuseDialog(
            onDismissRequest = { showSlotsDialog = false },
            title = stringResource(R.string.settings_agent_daily_summary_slots_title),
            confirmEnabled = hasSelection,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    slotGroups.forEach { (groupLabel, hours) ->
                        Text(
                            text = groupLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            hours.forEach { h ->
                                val selected = h in selectedSlots
                                MuseChip(
                                    selected = selected,
                                    onClick = {
                                        // 点击即时切换选中态
                                        selectedSlots = if (selected) selectedSlots - h else selectedSlots + h
                                    },
                                    label = String.format(Locale.getDefault(), "%02d:00", h),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    // 底部:无选择时红色提示;否则展示当前已选时段中文描述
                    Text(
                        text = if (hasSelection) {
                            stringResource(
                                R.string.settings_agent_daily_summary_slots_selected,
                                selectedSlots.sorted().joinToString("、") {
                                    String.format(Locale.getDefault(), "%02d:00", it)
                                },
                            )
                        } else {
                            stringResource(R.string.settings_agent_daily_summary_slots_empty_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (hasSelection) FontWeight.Medium else FontWeight.SemiBold,
                        color = if (hasSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                // 保存排序后的逗号分隔小时,兼容 SettingsRepository.saveDailySummarySlots
                scope.launch { settings.saveDailySummarySlots(selectedSlots.sorted()) }
                showSlotsDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showSlotsDialog = false },
        )
    }

    // ── v1.xxx: AI 朋友圈每日条数选择弹窗(0 = 关闭)──
    if (showMomentCountPicker) {
        var countValue by rememberSaveable { mutableStateOf(dailyMomentCount.coerceIn(0, 5)) }
        val alignedValue = countValue.coerceIn(0, 5)
        MuseDialog(
            onDismissRequest = { showMomentCountPicker = false },
            title = stringResource(R.string.settings_agent_moment_count_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (alignedValue <= 0) {
                            stringResource(R.string.settings_agent_moment_count_off)
                        } else {
                            stringResource(R.string.settings_agent_moment_count_per_day, alignedValue)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue.toFloat(),
                        onValueChange = { countValue = it.toInt() },
                        valueRange = 0f..5f,
                        // 0..5 共 6 档,steps=4 → start/end 之间 4 个停点
                        steps = 4,
                        valueFormatter = { if (it.toInt() <= 0) "关闭" else "${it.toInt()} 条/天" },
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveDailyMomentCount(alignedValue) }
                showMomentCountPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMomentCountPicker = false },
        )
    }

    // ── P0: 主动程度选择弹窗(少/标准/多;反推不到则显示自定义) ──
    if (showPacePicker) {
        val currentPace = ProactivePace.from(
            proactiveConfig.intervalMinutes,
            proactiveConfig.sendProbability,
            proactiveConfig.maxDailyMessages,
        )
        MuseDialog(
            onDismissRequest = { showPacePicker = false },
            title = stringResource(R.string.settings_agent_pace_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_pace_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    ProactivePace.values().forEach { pace ->
                        val selected = currentPace == pace
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settings.saveProactiveMessageConfig(
                                            proactiveConfig.copy(
                                                intervalMinutes = pace.intervalMinutes,
                                                sendProbability = pace.sendProbability,
                                                maxDailyMessages = pace.maxDailyMessages,
                                                randomOffsetMinutes = proactiveConfig.randomOffsetMinutes
                                                    .coerceIn(0, pace.intervalMinutes),
                                            ),
                                        )
                                    }
                                    showPacePicker = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = paceTitle(pace),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = paceSubtitle(pace),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (selected) {
                                Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showPacePicker = false },
        )
    }

    // ── P0: 免打扰时段弹窗(起止两个滑块;存储映射到 allowedHourEnd / allowedHourStart) ──
    if (showQuietHoursPicker) {
        var startHour by rememberSaveable { mutableStateOf(proactiveConfig.allowedHourEnd) }
        var endHour by rememberSaveable { mutableStateOf(proactiveConfig.allowedHourStart) }
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        MuseDialog(
            onDismissRequest = { showQuietHoursPicker = false },
            title = stringResource(R.string.settings_agent_quiet_hours_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:00 – %02d:00", s, e),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_quiet_hours_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_quiet_hours_start),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = s.toFloat(),
                        onValueChange = { startHour = it.toInt() },
                        valueRange = 0f..23f,
                        valueFormatter = { String.format(Locale.getDefault(), "%02d:00", it.toInt()) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.settings_agent_quiet_hours_end),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = e.toFloat(),
                        onValueChange = { endHour = it.toInt() },
                        valueRange = 0f..23f,
                        valueFormatter = { String.format(Locale.getDefault(), "%02d:00", it.toInt()) },
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(allowedHourEnd = s, allowedHourStart = e),
                    )
                }
                showQuietHoursPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showQuietHoursPicker = false },
        )
    }

    // ── P0: 主动内容弹窗(每日总结 / 深夜日记 / 朋友圈) ──
    if (showContentPicker) {
        MuseDialog(
            onDismissRequest = { showContentPicker = false },
            title = stringResource(R.string.settings_agent_content_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_content_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SettingsGroup {
                        SettingsSwitchRow(
                            icon = MuseIcons.calendarStats,
                            title = stringResource(R.string.settings_agent_content_daily_summary),
                            subtitle = stringResource(R.string.settings_agent_daily_summary_subtitle),
                            checked = dailySummaryEnabled,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveDailySummaryEnabled(v) }
                            },
                        )
                        if (dailySummaryEnabled) {
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.clock,
                                title = stringResource(R.string.settings_agent_daily_summary_slots_title),
                                subtitle = dailySummarySlots.joinToString(" ") { String.format(Locale.getDefault(), "%02d:00", it) },
                                onClick = {
                                    showContentPicker = false
                                    showSlotsDialog = true
                                },
                            ) {
                                ChevronRight()
                            }
                        }
                        SettingsGroupDivider()
                        SettingsSwitchRow(
                            icon = MuseIcons.moon,
                            title = stringResource(R.string.settings_agent_content_night_diary),
                            subtitle = stringResource(R.string.settings_agent_night_patrol_subtitle),
                            checked = nightPatrolEnabled,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveNightPatrolEnabled(v) }
                            },
                        )
                        SettingsGroupDivider()
                        SettingsSwitchRow(
                            icon = MuseIcons.calendarStats,
                            title = stringResource(R.string.settings_agent_content_moment),
                            subtitle = if (dailyMomentCount > 0) {
                                stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount)
                            } else {
                                stringResource(R.string.settings_agent_moment_count_off)
                            },
                            checked = dailyMomentCount > 0,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveDailyMomentCount(if (v) 2 else 0) }
                            },
                        )
                        if (dailyMomentCount > 0) {
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.clock,
                                title = stringResource(R.string.settings_agent_moment_count_title),
                                subtitle = stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount),
                                onClick = {
                                    showContentPicker = false
                                    showMomentCountPicker = true
                                },
                            ) {
                                ChevronRight()
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showContentPicker = false },
        )
    }

    // ── P0: 高级弹窗(仅 Agent 会话 / 生成温度 / 随机偏移 / 测试发送) ──
    if (showAdvancedDialog) {
        MuseDialog(
            onDismissRequest = { showAdvancedDialog = false },
            title = stringResource(R.string.settings_agent_group_advanced),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_advanced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SettingsGroup {
                        SettingsSwitchRow(
                            icon = MuseIcons.user,
                            title = stringResource(R.string.settings_agent_agent_only),
                            subtitle = stringResource(R.string.settings_agent_agent_only_subtitle),
                            checked = proactiveConfig.agentOnly,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveProactiveMessageConfig(proactiveConfig.copy(agentOnly = v)) }
                            },
                        )
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.switch,
                            title = stringResource(R.string.settings_agent_temperature),
                            subtitle = "%.1f".format(proactiveConfig.temperature),
                            onClick = {
                                showAdvancedDialog = false
                                showTemperaturePicker = true
                            },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.switch,
                            title = stringResource(R.string.settings_agent_random_offset),
                            subtitle = offsetLabel(proactiveConfig.randomOffsetMinutes),
                            onClick = {
                                showAdvancedDialog = false
                                showOffsetPicker = true
                            },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.bell,
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
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAdvancedDialog = false },
        )
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
                                MuseSpinner(
                                    size = 20.dp,
                                )
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
                                        Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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

    // ── 模型选择弹窗(工具模型 / 子代理模型共用) ──
    if (showToolModelPicker) {
        ModelPickerDialog(
            title = stringResource(R.string.settings_agent_select_tool_model),
            clearLabel = stringResource(R.string.settings_agent_clear_tool_model),
            currentId = toolModelId,
            providers = providers,
            onSelect = { id -> scope.launch { settings.saveToolModel(id) } },
            onDismiss = { showToolModelPicker = false },
        )
    }
    if (showSubagentModelPicker) {
        ModelPickerDialog(
            title = stringResource(R.string.settings_agent_select_subagent_model),
            clearLabel = stringResource(R.string.settings_agent_clear_subagent_model),
            currentId = subagentModelId,
            providers = providers,
            onSelect = { id -> scope.launch { settings.saveSubagentModel(id) } },
            onDismiss = { showSubagentModelPicker = false },
        )
    }
}

/**
 * v2.0: 模型选择弹窗 — 工具模型 / 子代理模型共用。
 *
 * 跨 Provider 列出全部模型;选中即回调保存其 id(不切换激活 Provider);
 * 首行"清除"表示沿用主对话模型(currentId = null)。
 */
@Composable
private fun ModelPickerDialog(
    title: String,
    clearLabel: String,
    currentId: String?,
    providers: List<io.zer0.ai.core.ProviderConfig>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val isCleared = currentId == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clearLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCleared) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCleared) {
                        Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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
                            val isSelected = model.id == currentId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(model.id); onDismiss() }
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
                                    Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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
        onDismiss = onDismiss,
    )
}

/** 独立主动消息设置页：复用 Agent 页的完整主动消息设置，不复制状态逻辑。 */
@Composable
fun ProactiveMessageSettingsPage(onBack: () -> Unit) {
    AgentSettingsPage(
        onBack = onBack,
        onOpenMultiAgentSettings = {},
        proactiveOnly = true,
        showProactiveSettings = true,
    )
}

/** 助手选择行:头像 + 名字 + 单选指示。 */
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
            Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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

/** UI-FIX: 主动消息组内的子分组标签，给平铺的开关建立层级。 */
@Composable
private fun ProactiveGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** P0: 主动程度档位标题。 */
@Composable
private fun paceTitle(pace: ProactivePace): String = when (pace) {
    ProactivePace.LIGHT -> stringResource(R.string.settings_agent_pace_light)
    ProactivePace.STANDARD -> stringResource(R.string.settings_agent_pace_standard)
    ProactivePace.HEAVY -> stringResource(R.string.settings_agent_pace_heavy)
}

/** P0: 主动程度档位说明。 */
@Composable
private fun paceSubtitle(pace: ProactivePace): String = when (pace) {
    ProactivePace.LIGHT -> stringResource(R.string.settings_agent_pace_light_desc)
    ProactivePace.STANDARD -> stringResource(R.string.settings_agent_pace_standard_desc)
    ProactivePace.HEAVY -> stringResource(R.string.settings_agent_pace_heavy_desc)
}
