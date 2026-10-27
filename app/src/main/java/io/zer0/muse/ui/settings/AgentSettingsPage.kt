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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactivePace
import io.zer0.muse.data.proactive.QuietHoursWindow
import io.zer0.muse.schedule.DailySummaryWorker
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
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
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    onOpenMultiAgentSettings: () -> Unit,
    onOpenAgentDm: () -> Unit = {},
    /** v2.6.7 fix (AUDIT F9): 用户偏好管理页入口。 */
    onOpenPreferences: () -> Unit = {},
    /** 独立主动消息页复用同一套配置组件时隐藏 Agent/协作区。 */
    proactiveOnly: Boolean = false,
    showProactiveSettings: Boolean = false,
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val proactiveConfig by settings.proactiveMessageConfigFlow.collectAsStateWithLifecycle(
        initialValue = ProactiveMessageConfig(),
    )
    // v1.0.72: 每日总结推送开关
    val dailySummaryEnabled by settings.dailySummaryEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // v1.xxx: 每日总结时段(24 小时制整点小时)
    val dailySummarySlots by settings.dailySummarySlotsFlow.collectAsStateWithLifecycle(initialValue = listOf(0, 9, 12, 21))
    // v1.xxx: AI 朋友圈每日条数(0 = 关闭)
    val dailyMomentCount by settings.dailyMomentCountFlow.collectAsStateWithLifecycle(initialValue = 2)
    // v2.6.7（P0-1）: 朋友圈内容模式（决定动态/评论/日记的生成视角）
    val momentContentMode by settings.momentContentModeFlow
        .collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.moment.MomentContentMode.REAL)
    // v1.xxx: 后台调度总控开关
    val scheduleWorkEnabled by settings.scheduleWorkEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    // v2.x: 保活开关自记忆页归位(系统级后台行为)
    val keepAwake by settings.keepAwakeFlow.collectAsStateWithLifecycle(initialValue = false)
    val autoLaunch by settings.autoLaunchFlow.collectAsStateWithLifecycle(initialValue = false)
    val nightPatrolEnabled by settings.nightPatrolEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val multiAgentConfig by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MultiAgentConfig(),
    )
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.0.72: 主动消息测试发送(避免重复触发)
    var testSending by remember { mutableStateOf(false) }
    // P0: 主动程度 / 免打扰时段 / 主动内容 / 高级 弹窗
    var showPacePicker by remember { mutableStateOf(false) }
    var showQuietHoursPicker by remember { mutableStateOf(false) }
    var showContentPicker by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    // v2.6.9: 主动消息重建版 —— 牵挂账本面板
    val proactiveThoughts: io.zer0.muse.data.proactive.ProactiveThoughtRepository = koinInject()
    val proactiveRelations: io.zer0.muse.data.proactive.ProactiveRelationRepository = koinInject()
    val proactiveScheduler: io.zer0.muse.proactive.ProactiveScheduler = koinInject()
    var showLedger by remember { mutableStateOf(false) }
    var ledgerThoughts by remember {
        mutableStateOf<List<io.zer0.muse.data.proactive.PendingThoughtEntity>>(emptyList())
    }
    var ledgerRelation by remember {
        mutableStateOf<io.zer0.muse.data.proactive.ProactiveRelationEntity?>(null)
    }

    var showAssistantPicker by remember { mutableStateOf(false) }
    var showOffsetPicker by remember { mutableStateOf(false) }
    // v1.xxx: 每日总结时段输入弹窗
    var showSlotsDialog by remember { mutableStateOf(false) }
    // v1.xxx: AI 朋友圈每日条数选择弹窗
    var showMomentCountPicker by remember { mutableStateOf(false) }
    // v2.6.7（P0-1）: 朋友圈内容模式选择（真实系 / 角色系）
    var showMomentContentModePicker by remember { mutableStateOf(false) }
    // v1.x: 保持后台运行引导弹窗(被动入口)
    var showKeepAliveGuide by remember { mutableStateOf(false) }
    var showTemperaturePicker by remember { mutableStateOf(false) }

    // v2.6.x: 全天开放时摘要直接写「全天开放」,避免显示一个无意义的 23:00 – 23:00
    // 免打扰时段与存储字段 allowedHourStart/End 是互补关系,
    // 这里统一走 QuietHoursWindow 换算,不再在本页手写取反。
    val quietHours = QuietHoursWindow.AllowedWindow(
        proactiveConfig.allowedHourStart,
        proactiveConfig.allowedHourEnd,
    ).toQuietHours()
    val quietHoursLabel = if (proactiveConfig.allDayAllowed) {
        stringResource(R.string.settings_agent_all_day_title)
    } else {
        quietHours.label()
    }
    // 允许窗口为空(起止相同)= 永远不会发主动消息。这种配置必须在页面上就看见,
    // 否则用户只能等“一条都不发”自己掘出来。
    val allowedWindow = QuietHoursWindow.AllowedWindow(
        proactiveConfig.allowedHourStart,
        proactiveConfig.allowedHourEnd,
    )
    val allowedWindowIsEmpty = !proactiveConfig.allDayAllowed && allowedWindow.isEmpty
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

    // v2.6.9: 账本数据随面板打开刷新；同时把当日额度、等级、冷却一并取出来，
    // 供入口行与面板共用。放在 LaunchedEffect 里而不是每次重组都读库，
    // 是因为它要跨三张表，不该跟着输入框的每次按键重跑。
    LaunchedEffect(showLedger, proactiveConfig.enabled) {
        if (!proactiveConfig.enabled) return@LaunchedEffect
        runCatching {
            val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
            ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
            ledgerThoughts = proactiveThoughts.getOpen(assistantId)
        }
    }

    if (showLedger) {
        val now = System.currentTimeMillis()
        val relation = ledgerRelation
        ProactiveLedgerSheet(
            state = LedgerUiState(
                thoughts = ledgerThoughts,
                intensity = relation?.intensityEnum()
                    ?: io.zer0.muse.data.proactive.ProactiveIntensity.DEFAULT,
                remainingBudget = relation?.let {
                    io.zer0.muse.data.proactive.ProactiveTimingEngine.remainingBudget(it)
                } ?: 0f,
                cooldownUntil = relation?.cooldownUntil ?: 0L,
                now = now,
            ),
            onIntensityChange = { level ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.setIntensity(assistantId, level)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onResetIntensity = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    val today =
                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    proactiveRelations.resetIntensity(assistantId, today)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.startCooldown(assistantId, System.currentTimeMillis())
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onClearCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.clearCooldown(assistantId)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onDismiss = { showLedger = false },
        )
    }

    SettingsSubPageScaffold(
        title = if (proactiveOnly) {
            stringResource(
                R.string.settings_agent_proactive_title,
            )
        } else {
            stringResource(R.string.settings_agent_page_title)
        },
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
                        subtitle = if (assistants == null) {
                            stringResource(
                                R.string.settings_agent_loading,
                            )
                        } else {
                            currentAgent?.name ?: stringResource(R.string.settings_agent_use_first_assistant)
                        },
                        onClick = { showAssistantPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v2.6.7 fix (AUDIT F9): 用户偏好（助手记住的长期要求）。
                    // 此前 PreferenceStore 的读取/删除/停用全仓零调用，
                    // 用户看不到也删不掉一条记错的偏好。
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.check,
                        title = stringResource(R.string.settings_preference_title),
                        subtitle = stringResource(R.string.settings_preference_subtitle),
                        onClick = onOpenPreferences,
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
                        // P0 收敛: 「间隔 / 偏移 / 概率 / 每日上限」四个实现参数合并为一个
                        // v2.6.9: 账本入口 —— 点开能看到它最近在惦记什么、今天还能说几次、
                        // 以及一键让它安静 7 天。这块 UI 是新机制的兑现方式：
                        // 用户能看见「它凭什么找我」，也能看见「它什么时候闭嘴」。
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.note,
                            title = stringResource(R.string.settings_proactive_v2_ledger),
                            subtitle = if (ledgerThoughts.isEmpty()) {
                                stringResource(R.string.settings_proactive_v2_empty)
                            } else {
                                stringResource(
                                    R.string.settings_proactive_v2_ledger_sub,
                                    ledgerThoughts.size,
                                )
                            },
                            onClick = { showLedger = true },
                        ) {
                            ChevronRight()
                        }
                        // UI-FIX: 子分组标签 —— 原先十几行开关平铺在同一层，看不出哪几个是一组
                        // P0 收敛: 「间隔 / 偏移 / 概率 / 每日上限」四个实现参数合并为一个
                        // 用户能表达的「主动程度」档位(少/标准/多);随机偏移与温度降到「高级」。
                        ProactiveGroupLabel(stringResource(R.string.settings_agent_group_pace))
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.calendarTime,
                            title = stringResource(R.string.settings_proactive_v2_intensity),
                            subtitle = stringResource(
                                when (
                                    ledgerRelation?.intensityEnum()
                                        ?: io.zer0.muse.data.proactive.ProactiveIntensity.DEFAULT
                                ) {
                                    io.zer0.muse.data.proactive.ProactiveIntensity.QUIET ->
                                        R.string.proactive_intensity_quiet
                                    io.zer0.muse.data.proactive.ProactiveIntensity.RESERVED ->
                                        R.string.proactive_intensity_reserved
                                    io.zer0.muse.data.proactive.ProactiveIntensity.STANDARD ->
                                        R.string.proactive_intensity_standard
                                    io.zer0.muse.data.proactive.ProactiveIntensity.ACTIVE ->
                                        R.string.proactive_intensity_active
                                    io.zer0.muse.data.proactive.ProactiveIntensity.CLINGY ->
                                        R.string.proactive_intensity_clingy
                                },
                            ),
                            onClick = { showLedger = true },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        // P0 收敛: 「允许时段开始 / 结束」两个滑块合并为一个「免打扰时段」范围
                        // 允许窗口为空（起止相同）时红色提示:这种配置一条都不会发，
                        // 不能让用户只能等到自己发现“怎么没动静”。
                        SettingsItemRow(
                            icon = MuseIcons.moon,
                            title = stringResource(R.string.settings_agent_quiet_hours_title),
                            subtitle = if (allowedWindowIsEmpty) {
                                quietHoursLabel + " · " +
                                    stringResource(R.string.settings_agent_quiet_hours_empty_warning)
                            } else {
                                quietHoursLabel
                            },
                            onClick = { showQuietHoursPicker = true },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        // v2.x: 原「发送 Agent」行已删除 — 与「Agent 默认助手」同值同弹窗,合并为单一入口
                        val senderAgent = assistants?.find { it.id == proactiveConfig.agentId }
                        SettingsItemRow(
                            icon = MuseIcons.user,
                            title = stringResource(R.string.settings_agent_default_assistant_title),
                            subtitle = senderAgent?.name ?: stringResource(R.string.settings_agent_default_assistant_fallback),
                            onClick = { showAssistantPicker = true },
                        ) {
                            ChevronRight()
                        }
                        // P0 收敛: 温度 / 概率 / 测试发送 / 仅 Agent 会话 收进「高级」
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.robot,
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
                    // v2.x: 保活开关自记忆页归位(系统级后台行为)
                    SettingsSwitchRow(
                        icon = MuseIcons.bolt,
                        title = stringResource(R.string.settings_memory_keep_awake),
                        subtitle = stringResource(R.string.settings_memory_keep_awake_subtitle),
                        checked = keepAwake,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveKeepAwake(v) }
                        },
                    )
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = MuseIcons.power,
                        title = stringResource(R.string.settings_memory_auto_launch),
                        subtitle = stringResource(R.string.settings_memory_auto_launch_subtitle),
                        checked = autoLaunch,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveAutoLaunch(v) }
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
                scope.launch {
                    settings.saveDailySummarySlots(selectedSlots.sorted())
                    // 设置变更后立即同步 WorkManager；否则仅在下次进程启动时重排，
                    // 旧小时会继续执行而新小时要等到下一次冷启动。
                    DailySummaryWorker.scheduleNext(context)
                }
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

    // ── v2.6.7（P0-1）: 朋友圈内容模式选择弹窗（真实系 / 角色系）──
    if (showMomentContentModePicker) {
        MuseDialog(
            onDismissRequest = { showMomentContentModePicker = false },
            title = stringResource(R.string.settings_moment_content_mode_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_moment_content_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    io.zer0.muse.data.moment.MomentContentMode.entries.forEach { mode ->
                        val selected = momentContentMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settings.saveMomentContentMode(mode) }
                                    showMomentContentModePicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (mode) {
                                        io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                            stringResource(R.string.settings_moment_content_mode_real)
                                        io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                            stringResource(R.string.settings_moment_content_mode_character)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = when (mode) {
                                        io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                            stringResource(R.string.settings_moment_content_mode_real_desc)
                                        io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                            stringResource(R.string.settings_moment_content_mode_character_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showMomentContentModePicker = false },
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
                                                // v2.6.2: 「极多」档开启 AI 自主调度（助手自己决定下次时间）。
                                                aiPaced = pace == ProactivePace.EXTREME,
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
                                Icon(
                                    MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showPacePicker = false },
        )
    }

    // ── P0: 免打扰时段弹窗(起止两个滑块) ──
    //
    // 语义说明（重要）:本弹窗编辑的是「免打扰时段」,存储字段是「允许发送窗口」,
    // 两者互补。旧实现把这个换算写成 `startHour = allowedHourEnd` / `allowedHourStart = e`,
    // 方向相反且隔了八十行,读代码时极易误判为“双重取反 bug”（实际往返是恒等的）,
    // 改动时也极易只改一半而真的把窗口弄反。
    // 现在换算全部收在 QuietHoursWindow 里,这里只处理「免打扰」一个语义。
    if (showQuietHoursPicker) {
        val storedQuiet = QuietHoursWindow.AllowedWindow(
            proactiveConfig.allowedHourStart,
            proactiveConfig.allowedHourEnd,
        ).toQuietHours()
        var startHour by rememberSaveable { mutableStateOf(storedQuiet.startHour) }
        var endHour by rememberSaveable { mutableStateOf(storedQuiet.endHour) }
        // v2.6.x: 全天开放开关。allowedHourStart/End 是左闭右开的区间,凑不出完整 24 小时
        // (23-23 = 空区间一条不发,23-0 = 只覆盖 23 点),所以单独给一个总开关。
        var allDay by rememberSaveable { mutableStateOf(proactiveConfig.allDayAllowed) }
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        MuseDialog(
            onDismissRequest = { showQuietHoursPicker = false },
            title = stringResource(R.string.settings_agent_quiet_hours_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 全天开放:打开后不再需要选起止小时
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_agent_all_day_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.settings_agent_all_day_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        MuseSwitch(checked = allDay, onCheckedChange = { allDay = it })
                    }
                    if (allDay) {
                        Text(
                            text = stringResource(R.string.settings_agent_all_day_subtitle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = QuietHoursWindow.QuietHours(s, e).label(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        // 免打扰起止相同 = 允许窗口为空 = 永远不会发主动消息。
                        // 这个组合以前能默默保存下来,用户只会看到“一条都不发”。
                        if (s == e) {
                            Text(
                                text = stringResource(R.string.settings_agent_quiet_hours_empty_warning),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
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
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                // 直接写回免打扰语义;换算交给 QuietHoursWindow,不再在这里手写取反。
                val allowed = QuietHoursWindow.QuietHours(s, e).toAllowed()
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(
                            allowedHourStart = allowed.startHour,
                            allowedHourEnd = allowed.endHour,
                            allDayAllowed = allDay,
                        ),
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
                            // v2.6.7（P0-1）: 朋友圈内容模式 — 真实系 / 角色系双档
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.typography,
                                title = stringResource(R.string.settings_moment_content_mode_title),
                                subtitle = when (momentContentMode) {
                                    io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                        stringResource(R.string.settings_moment_content_mode_real_desc)
                                    io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                        stringResource(R.string.settings_moment_content_mode_character_desc)
                                },
                                onClick = {
                                    showContentPicker = false
                                    showMomentContentModePicker = true
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
                                        // v2.2.1: 如实展示结果(含失败原因),替代固定的"已发送"文案
                                        // v2.6.9: 改走重建版调度器 —— 旧 Runner 已不再启动，
                                        // 若继续指向它，测试按钮会走一条实际不存在的链路。
                                        val result = proactiveScheduler.runCycle(
                                            io.zer0.muse.proactive.ProactiveScheduler.TRIGGER_MANUAL,
                                            force = true,
                                        )
                                        android.widget.Toast.makeText(
                                            context,
                                            result.detail,
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    } catch (e: Exception) {
                                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                                        Logger.w("AgentSettingsPage", "测试主动消息失败: ${e.message}")
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_agent_test_failed, e.message),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
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
                                                proactiveConfig.copy(agentId = assistant.id),
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
                        // LAYOUT-01: 滑块两端刻度标注是定宽短文本(两端对齐交给 SpaceBetween),
                        // 不取宽,仅限 1 行防止放大字号下逐字换行。
                        Text(
                            stringResource(R.string.settings_agent_offset_off_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        Text(
                            intervalLabel(maxOffset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(randomOffsetMinutes = alignedMinutes),
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
                        // LAYOUT-01: 同上,温度滑块两端刻度标注限 1 行。
                        Text(
                            stringResource(R.string.settings_agent_temperature_stable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.settings_agent_temperature_creative),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
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
private fun AgentPickerRow(assistant: AssistantEntity, selected: Boolean, onClick: () -> Unit) {
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
            if (h == 24) {
                stringResource(R.string.settings_agent_interval_daily)
            } else {
                stringResource(R.string.settings_agent_interval_hours, h)
            }
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
    ProactivePace.EXTREME -> stringResource(R.string.settings_agent_pace_extreme)
}

/** P0: 主动程度档位说明。 */
@Composable
private fun paceSubtitle(pace: ProactivePace): String = when (pace) {
    ProactivePace.LIGHT -> stringResource(R.string.settings_agent_pace_light_desc)
    ProactivePace.STANDARD -> stringResource(R.string.settings_agent_pace_standard_desc)
    ProactivePace.HEAVY -> stringResource(R.string.settings_agent_pace_heavy_desc)
    ProactivePace.EXTREME -> stringResource(R.string.settings_agent_pace_extreme_desc)
}
