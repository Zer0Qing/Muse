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
import io.zer0.muse.data.proactive.QuietHoursWindow
import io.zer0.muse.schedule.DailySummaryWorker
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
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
    /** 跳转「给它的约定与规矩」页。 */
    onOpenDirectives: () -> Unit = {},
    /** 跳转「常驻后台与保活」设置页。 */
    onOpenKeepAlive: () -> Unit = {},
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
    // v1.xxx: 后台调度总控开关（用于顶部状态诊断展示）
    val scheduleWorkEnabled by settings.scheduleWorkEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nightPatrolEnabled by settings.nightPatrolEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val multiAgentConfig by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MultiAgentConfig(),
    )
    val chatPrefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.ChatPreferences(),
    )
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.0.72: 主动消息测试发送(避免重复触发)
    var testSending by remember { mutableStateOf(false) }
    // 测试按钮被「上一轮还在生成」拦下时的提示。在组合期取好，
    // 点击回调里只有协程作用域，取不到 stringResource。
    val testAlreadyRunningText = stringResource(R.string.settings_agent_test_already_running)
    var showQuietHoursPicker by remember { mutableStateOf(false) }
    var showAdvancedSection by rememberSaveable { mutableStateOf(false) }
    // v2.6.9: 主动消息重建版 —— 牵挂账本面板
    val proactiveThoughts: io.zer0.muse.data.proactive.ProactiveThoughtRepository = koinInject()
    val proactiveRelations: io.zer0.muse.data.proactive.ProactiveRelationRepository = koinInject()
    val proactiveScheduler: io.zer0.muse.proactive.ProactiveScheduler = koinInject()
    val notificationManager: io.zer0.muse.notification.MuseNotificationManager = koinInject()
    var showLedger by remember { mutableStateOf(false) }
    var ledgerThoughts by remember {
        mutableStateOf<List<io.zer0.muse.data.proactive.PendingThoughtEntity>>(emptyList())
    }
    var historyThoughts by remember {
        mutableStateOf<List<io.zer0.muse.data.proactive.PendingThoughtEntity>>(emptyList())
    }
    var ledgerRelation by remember {
        mutableStateOf<io.zer0.muse.data.proactive.ProactiveRelationEntity?>(null)
    }
    var notificationBlocker by remember { mutableStateOf<String?>(null) }
    var testResultDetail by remember { mutableStateOf<String?>(null) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    var showProbabilityPicker by remember { mutableStateOf(false) }

    var showAssistantPicker by remember { mutableStateOf(false) }
    var showOffsetPicker by remember { mutableStateOf(false) }
    var showGuaranteePicker by remember { mutableStateOf(false) }
    // v1.xxx: 每日总结时段输入弹窗
    var showSlotsDialog by remember { mutableStateOf(false) }
    // v1.xxx: AI 朋友圈每日条数选择弹窗
    var showMomentCountPicker by remember { mutableStateOf(false) }
    // v2.6.7（P0-1）: 朋友圈内容模式选择（真实系 / 角色系）
    var showMomentContentModePicker by remember { mutableStateOf(false) }
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

    // v2.6.9: 账本数据随面板打开刷新；同时把当日额度、等级、冷却一并取出来，
    // 供入口行与面板共用。放在 LaunchedEffect 里而不是每次重组都读库，
    // 是因为它要跨三张表，不该跟着输入框的每次按键重跑。
    val addedToast = stringResource(R.string.proactive_ledger_added_toast)
    suspend fun reloadLedger(assistantId: String) {
        val now = System.currentTimeMillis()
        ledgerRelation = proactiveRelations.getOrCreate(assistantId, now)
        ledgerThoughts = proactiveThoughts.getOpen(assistantId)
        historyThoughts = proactiveThoughts.getRecentByAssistant(assistantId, 40)
            .filter { it.status != io.zer0.muse.data.proactive.ThoughtStatus.OPEN.name }
        notificationBlocker = runCatching { notificationManager.proactiveNotificationBlocker() }.getOrNull()
    }

    LaunchedEffect(showLedger, proactiveConfig.enabled, proactiveConfig.agentId) {
        runCatching {
            val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
            reloadLedger(assistantId)
        }
    }

    if (testResultDetail != null) {
        MuseDialog(
            onDismissRequest = { testResultDetail = null },
            title = stringResource(R.string.proactive_status_test_result_title),
            content = {
                Text(
                    text = testResultDetail.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { testResultDetail = null },
        )
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
                historyThoughts = historyThoughts,
            ),
            onIntensityChange = { level ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.setIntensity(assistantId, level)
                    reloadLedger(assistantId)
                }
            },
            onResetIntensity = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    val today =
                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    proactiveRelations.resetIntensity(assistantId, today)
                    reloadLedger(assistantId)
                }
            },
            onCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.startCooldown(assistantId, System.currentTimeMillis())
                    reloadLedger(assistantId)
                }
            },
            onClearCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.clearCooldown(assistantId)
                    reloadLedger(assistantId)
                }
            },
            onCompleteThought = { id ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveThoughts.complete(id)
                    reloadLedger(assistantId)
                }
            },
            onDropThought = { id ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveThoughts.drop(id)
                    reloadLedger(assistantId)
                }
            },
            onAddThought = { seed, kind, triggerAt ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    val nowAdd = System.currentTimeMillis()
                    val defaultExpiry = nowAdd + 14L * 86_400_000L
                    val expiresAt = if (triggerAt != null) {
                        maxOf(defaultExpiry, triggerAt + 6L * 3_600_000L)
                    } else {
                        defaultExpiry
                    }
                    proactiveThoughts.note(
                        io.zer0.muse.data.proactive.PendingThoughtEntity(
                            id = "manual_${nowAdd}_${seed.hashCode().toUInt()}",
                            assistantId = assistantId,
                            kind = kind.name,
                            seed = seed,
                            source = io.zer0.muse.data.proactive.ThoughtSource.USER_DEFINED.name,
                            createdAt = nowAdd,
                            expiresAt = expiresAt,
                            triggerAt = triggerAt,
                            urgency = if (kind == io.zer0.muse.data.proactive.ThoughtKind.REMINDER || triggerAt != null) {
                                io.zer0.muse.data.proactive.ThoughtUrgency.IMMEDIATE.name
                            } else {
                                io.zer0.muse.data.proactive.ThoughtUrgency.WINDOW.name
                            },
                            weight = if (triggerAt != null) 0.92f else 0.85f,
                        ),
                    )
                    if (triggerAt != null) {
                        org.koin.core.context.GlobalContext.getOrNull()
                            ?.getOrNull<io.zer0.muse.proactive.ProactiveScheduler>()
                            ?.scheduleWakeUpForThought(triggerAt)
                    }
                    MuseToast.show(addedToast)
                    reloadLedger(assistantId)
                }
            },
            onDismiss = { showLedger = false },
        )
    }

    SettingsSubPageScaffold(
        title = if (proactiveOnly) {
            stringResource(R.string.settings_agent_proactive_title)
        } else {
            stringResource(R.string.settings_agent_page_title)
        },
        onBack = onBack,
    ) {
        if (!proactiveOnly) {
            item(key = "agent_default_group") {
                val currentAgent = assistants?.find { it.id == proactiveConfig.agentId }
                    ?: assistants?.firstOrNull { it.id == "default" }
                    ?: assistants?.firstOrNull()
                val agentSub = if (assistants == null) {
                    stringResource(R.string.settings_agent_loading)
                } else {
                    currentAgent?.name ?: stringResource(R.string.settings_agent_use_first_assistant)
                }
                CardGroup(title = { Text(stringResource(R.string.settings_agent_section_assistant)) }) {
                    navigationItem(
                        key = "default_assistant",
                        title = stringResource(R.string.settings_agent_default_assistant_title),
                        subtitle = agentSub,
                        leadingContent = {
                            Icon(
                                MuseIcons.user,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = { showAssistantPicker = true },
                    )
                    navigationItem(
                        key = "user_preferences",
                        title = stringResource(R.string.settings_preference_title),
                        leadingContent = {
                            Icon(
                                MuseIcons.check,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = onOpenPreferences,
                    )
                }
            }

            item(key = "agent_display_group") {
                CardGroup(title = { Text(stringResource(R.string.settings_agent_display_section)) }) {
                    switchItem(
                        key = "agent_show_thinking",
                        title = stringResource(R.string.settings_agent_show_thinking_title),
                        checked = chatPrefs.agentShowThinking,
                        onCheckedChange = { v ->
                            scope.launch { settings.updateChatPreferences { it.copy(agentShowThinking = v) } }
                        },
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
                        key = "agent_show_mood",
                        title = stringResource(R.string.settings_agent_show_mood_title),
                        checked = chatPrefs.agentShowMood,
                        onCheckedChange = { v ->
                            scope.launch { settings.updateChatPreferences { it.copy(agentShowMood = v) } }
                        },
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
                        key = "agent_show_plan_cards",
                        title = stringResource(R.string.settings_agent_show_plan_cards_title),
                        subtitle = stringResource(R.string.settings_agent_show_plan_cards_subtitle),
                        checked = chatPrefs.agentShowPlanCards,
                        onCheckedChange = { v ->
                            scope.launch { settings.updateChatPreferences { it.copy(agentShowPlanCards = v) } }
                        },
                        leadingContent = {
                            Icon(
                                MuseIcons.computer,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                    )
                }
            }

            item(key = "agent_collab_group") {
                val collabSub = if (multiAgentConfig.enabled) {
                    stringResource(R.string.settings_agent_multi_agent_enabled, multiAgentConfig.teams.size)
                } else {
                    stringResource(R.string.settings_agent_manage_collab_team)
                }
                CardGroup(title = { Text(stringResource(R.string.settings_agent_multi_agent_section)) }) {
                    navigationItem(
                        key = "collab_team",
                        title = stringResource(R.string.settings_agent_collab_team),
                        subtitle = collabSub,
                        leadingContent = {
                            Icon(
                                MuseIcons.users,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = onOpenMultiAgentSettings,
                    )
                    navigationItem(
                        key = "agent_dm",
                        title = stringResource(R.string.agent_dm_title),
                        leadingContent = {
                            Icon(
                                MuseIcons.mail,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = onOpenAgentDm,
                    )
                }
            }
        }

        if (showProactiveSettings) {
            item(key = "proactive_diagnostics_card") {
                val now = System.currentTimeMillis()
                val blocker = notificationBlocker
                val inCooldown = (ledgerRelation?.cooldownUntil ?: 0L) > now
                val inQuiet = !proactiveConfig.allDayAllowed &&
                    io.zer0.muse.data.proactive.ProactiveTimingEngine.isInQuietHours(
                        io.zer0.muse.data.proactive.ProactiveTimingEngine.hourOfDay(now),
                        proactiveConfig.allowedHourStart,
                        proactiveConfig.allowedHourEnd,
                    )
                val statusText = when {
                    !proactiveConfig.enabled -> stringResource(R.string.proactive_status_paused_master)
                    !scheduleWorkEnabled -> stringResource(R.string.proactive_status_paused_bg)
                    blocker != null -> stringResource(R.string.proactive_status_blocked_notif, blocker)
                    inCooldown -> stringResource(R.string.proactive_status_cooldown)
                    inQuiet -> stringResource(R.string.proactive_status_quiet)
                    else -> stringResource(R.string.proactive_status_ok)
                }
                val isHealthy = proactiveConfig.enabled && scheduleWorkEnabled && blocker == null && !inCooldown
                val relationNow = ledgerRelation
                val budgetStr = when {
                    relationNow == null -> "--"
                    relationNow.intensityEnum().unlimited -> "∞"
                    else -> io.zer0.muse.data.proactive.ProactiveTimingEngine.remainingBudget(relationNow).toInt().toString()
                }
                val lastOutcomeText = proactiveScheduler.lastOutcome
                val lastPatrolLabel = if (lastOutcomeText.isNullOrBlank()) {
                    stringResource(R.string.proactive_status_last_patrol_none)
                } else {
                    stringResource(R.string.proactive_status_last_patrol, lastOutcomeText)
                }
                CardGroup(title = { Text(stringResource(R.string.proactive_status_card_title)) }) {
                    content(key = "proactive_status_body") {
                        Column(
                            modifier = Modifier.padding(MusePaddings.cardInner),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                Icon(
                                    imageVector = if (isHealthy) MuseIcons.shieldCheck else MuseIcons.bell,
                                    contentDescription = null,
                                    tint = if (isHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isHealthy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.proactive_status_summary,
                                    budgetStr,
                                    ledgerThoughts.size,
                                    intervalLabel(proactiveConfig.intervalMinutes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = lastPatrolLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                io.zer0.muse.ui.common.form.MuseCapsuleButton(
                                    text = if (testSending) {
                                        stringResource(R.string.settings_agent_test_generating)
                                    } else {
                                        stringResource(R.string.proactive_status_test_now)
                                    },
                                    onClick = {
                                        if (!testSending) {
                                            testSending = true
                                            scope.launch {
                                                try {
                                                    val result = proactiveScheduler.runCycle(
                                                        io.zer0.muse.proactive.ProactiveScheduler.TRIGGER_MANUAL,
                                                        force = true,
                                                    )
                                                    testResultDetail = if (result.alreadyRunning) {
                                                        testAlreadyRunningText
                                                    } else {
                                                        result.detail
                                                    }
                                                } catch (e: Exception) {
                                                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                                                    testResultDetail = context.getString(R.string.settings_agent_test_failed, e.message)
                                                } finally {
                                                    testSending = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !testSending,
                                    loading = testSending,
                                    size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                                    fillWidth = false,
                                )
                                io.zer0.muse.ui.common.form.MuseCapsuleButton(
                                    text = stringResource(R.string.settings_proactive_v2_ledger),
                                    onClick = { showLedger = true },
                                    variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary,
                                    size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "proactive_core_group") {
                val ledgerSub = if (ledgerThoughts.isEmpty()) {
                    stringResource(R.string.settings_proactive_v2_empty)
                } else {
                    stringResource(R.string.settings_proactive_v2_ledger_sub, ledgerThoughts.size)
                }
                val intensityLabel = stringResource(
                    when (
                        ledgerRelation?.intensityEnum()
                            ?: io.zer0.muse.data.proactive.ProactiveIntensity.DEFAULT
                    ) {
                        io.zer0.muse.data.proactive.ProactiveIntensity.QUIET -> R.string.proactive_intensity_quiet
                        io.zer0.muse.data.proactive.ProactiveIntensity.RESERVED -> R.string.proactive_intensity_reserved
                        io.zer0.muse.data.proactive.ProactiveIntensity.STANDARD -> R.string.proactive_intensity_standard
                        io.zer0.muse.data.proactive.ProactiveIntensity.ACTIVE -> R.string.proactive_intensity_active
                        io.zer0.muse.data.proactive.ProactiveIntensity.CLINGY -> R.string.proactive_intensity_clingy
                    },
                )
                val quietSub = if (allowedWindowIsEmpty) {
                    quietHoursLabel + " · " + stringResource(R.string.settings_agent_quiet_hours_empty_warning)
                } else {
                    quietHoursLabel
                }
                val senderAgent = assistants?.find { it.id == proactiveConfig.agentId }
                    ?: assistants?.firstOrNull { it.id == "default" }
                    ?: assistants?.firstOrNull()
                val senderName = senderAgent?.name ?: stringResource(R.string.settings_agent_default_assistant_fallback)
                CardGroup(title = { Text(stringResource(R.string.settings_agent_proactive_section)) }) {
                    switchItem(
                        key = "proactive_enabled",
                        title = stringResource(R.string.settings_agent_proactive_title),
                        checked = proactiveConfig.enabled,
                        onCheckedChange = { v ->
                            scope.launch {
                                settings.saveProactiveMessageConfig(proactiveConfig.copy(enabled = v))
                                if (v) proactiveScheduler.refreshWakeUpForOpenThoughts()
                            }
                        },
                        leadingContent = {
                            Icon(
                                MuseIcons.bell,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                    )
                    if (proactiveConfig.enabled) {
                        navigationItem(
                            key = "proactive_ledger",
                            title = stringResource(R.string.settings_proactive_v2_ledger),
                            subtitle = ledgerSub,
                            leadingContent = {
                                Icon(
                                    MuseIcons.note,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showLedger = true },
                        )
                        navigationItem(
                            key = "proactive_intensity",
                            title = stringResource(R.string.settings_proactive_v2_intensity),
                            value = intensityLabel,
                            leadingContent = {
                                Icon(
                                    MuseIcons.calendarTime,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showLedger = true },
                        )
                        navigationItem(
                            key = "proactive_interval",
                            title = stringResource(R.string.proactive_interval_title),
                            value = intervalLabel(proactiveConfig.intervalMinutes),
                            leadingContent = {
                                Icon(
                                    MuseIcons.clock,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showIntervalPicker = true },
                        )
                        navigationItem(
                            key = "proactive_prob",
                            title = stringResource(R.string.proactive_prob_title),
                            value = "${proactiveConfig.sendProbability}%",
                            leadingContent = {
                                Icon(
                                    MuseIcons.bolt,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showProbabilityPicker = true },
                        )
                        switchItem(
                            key = "proactive_timing_scoring",
                            title = stringResource(R.string.settings_agent_timing_scoring_title),
                            checked = proactiveConfig.sendTimingScoringEnabled,
                            onCheckedChange = { v ->
                                scope.launch {
                                    settings.saveProactiveMessageConfig(
                                        proactiveConfig.copy(sendTimingScoringEnabled = v),
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(
                                    MuseIcons.clock,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                        navigationItem(
                            key = "proactive_quiet_hours",
                            title = stringResource(R.string.settings_agent_quiet_hours_title),
                            subtitle = quietSub,
                            leadingContent = {
                                Icon(
                                    MuseIcons.moon,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showQuietHoursPicker = true },
                        )
                        navigationItem(
                            key = "proactive_sender",
                            title = stringResource(R.string.settings_agent_default_assistant_title),
                            value = senderName,
                            leadingContent = {
                                Icon(
                                    MuseIcons.user,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = { showAssistantPicker = true },
                        )
                        navigationItem(
                            key = "proactive_directives",
                            title = stringResource(R.string.proactive_directives_entry),
                            subtitle = stringResource(R.string.proactive_directives_entry_sub),
                            leadingContent = {
                                Icon(
                                    MuseIcons.shield,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            onClick = onOpenDirectives,
                        )
                    }
                }
            }

            if (proactiveConfig.enabled) {
                item(key = "proactive_content_group") {
                    val slotsText = dailySummarySlots.joinToString(" ") { String.format(Locale.getDefault(), "%02d:00", it) }
                    val momentCountSub = if (dailyMomentCount > 0) {
                        stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount)
                    } else {
                        stringResource(R.string.settings_agent_moment_count_off)
                    }
                    val momentModeSub = when (momentContentMode) {
                        io.zer0.muse.data.moment.MomentContentMode.REAL -> stringResource(R.string.settings_moment_content_mode_real)
                        io.zer0.muse.data.moment.MomentContentMode.CHARACTER -> stringResource(
                            R.string.settings_moment_content_mode_character,
                        )
                    }
                    CardGroup(title = { Text(stringResource(R.string.settings_agent_content_title)) }) {
                        switchItem(
                            key = "daily_summary_toggle",
                            title = stringResource(R.string.settings_agent_content_daily_summary),
                            checked = dailySummaryEnabled,
                            onCheckedChange = { v -> scope.launch { settings.saveDailySummaryEnabled(v) } },
                            leadingContent = {
                                Icon(
                                    MuseIcons.calendarStats,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                        if (dailySummaryEnabled) {
                            navigationItem(
                                key = "daily_summary_slots",
                                title = stringResource(R.string.settings_agent_daily_summary_slots_title),
                                subtitle = slotsText,
                                leadingContent = {
                                    Icon(
                                        MuseIcons.clock,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                                onClick = { showSlotsDialog = true },
                            )
                        }
                        switchItem(
                            key = "night_diary_toggle",
                            title = stringResource(R.string.settings_agent_content_night_diary),
                            checked = nightPatrolEnabled,
                            onCheckedChange = { v -> scope.launch { settings.saveNightPatrolEnabled(v) } },
                            leadingContent = {
                                Icon(
                                    MuseIcons.moon,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                        if (io.zer0.muse.feature.FeatureFlags.MOMENTS) {
                            switchItem(
                                key = "moment_toggle",
                                title = stringResource(R.string.settings_agent_content_moment),
                                subtitle = momentCountSub,
                                checked = dailyMomentCount > 0,
                                onCheckedChange = { v -> scope.launch { settings.saveDailyMomentCount(if (v) 2 else 0) } },
                                leadingContent = {
                                    Icon(
                                        MuseIcons.calendarStats,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                            )
                            if (dailyMomentCount > 0) {
                                navigationItem(
                                    key = "moment_count",
                                    title = stringResource(R.string.settings_agent_moment_count_title),
                                    value = stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount),
                                    leadingContent = {
                                        Icon(
                                            MuseIcons.clock,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                                        )
                                    },
                                    onClick = { showMomentCountPicker = true },
                                )
                                navigationItem(
                                    key = "moment_mode",
                                    title = stringResource(R.string.settings_moment_content_mode_title),
                                    value = momentModeSub,
                                    leadingContent = {
                                        Icon(
                                            MuseIcons.typography,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                                        )
                                    },
                                    onClick = { showMomentContentModePicker = true },
                                )
                            }
                        }
                    }
                }

                item(key = "proactive_advanced_group") {
                    val guaranteeText = guaranteeLabel(proactiveConfig.dailyGuaranteeHours)
                    val offsetText = offsetLabel(proactiveConfig.randomOffsetMinutes)
                    CardGroup(title = { Text(stringResource(R.string.settings_agent_group_advanced)) }) {
                        item(
                            key = "advanced_expand_toggle",
                            onClick = { showAdvancedSection = !showAdvancedSection },
                            leadingContent = {
                                Icon(
                                    MuseIcons.robot,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_agent_group_advanced)) },
                            supportingContent = { Text(stringResource(R.string.settings_agent_advanced_hint)) },
                            trailingContent = {
                                Icon(
                                    imageVector = if (showAdvancedSection) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                        if (showAdvancedSection) {
                            switchItem(
                                key = "adv_agent_session",
                                title = stringResource(R.string.settings_agent_agent_session_enabled),
                                checked = proactiveConfig.agentSessionEnabled,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        settings.saveProactiveMessageConfig(proactiveConfig.copy(agentSessionEnabled = v))
                                    }
                                },
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
                                key = "adv_plain_chat",
                                title = stringResource(R.string.settings_agent_plain_chat_enabled),
                                checked = proactiveConfig.allowPlainChatSession,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        settings.saveProactiveMessageConfig(proactiveConfig.copy(allowPlainChatSession = v))
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        MuseIcons.chat,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                            )
                            navigationItem(
                                key = "adv_guarantee",
                                title = stringResource(R.string.settings_agent_daily_guarantee),
                                value = guaranteeText,
                                leadingContent = {
                                    Icon(
                                        MuseIcons.clock,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                                onClick = { showGuaranteePicker = true },
                            )
                            navigationItem(
                                key = "adv_temperature",
                                title = stringResource(R.string.settings_agent_temperature),
                                value = "%.1f".format(proactiveConfig.temperature),
                                leadingContent = {
                                    Icon(
                                        MuseIcons.switch,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                                onClick = { showTemperaturePicker = true },
                            )
                            navigationItem(
                                key = "adv_offset",
                                title = stringResource(R.string.settings_agent_random_offset),
                                value = offsetText,
                                leadingContent = {
                                    Icon(
                                        MuseIcons.switch,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                },
                                onClick = { showOffsetPicker = true },
                            )
                            actionItem(
                                key = "adv_test_send",
                                title = if (testSending) {
                                    stringResource(R.string.settings_agent_test_generating)
                                } else {
                                    stringResource(R.string.settings_agent_test_message)
                                },
                                loading = testSending,
                                enabled = !testSending,
                                onClick = {
                                    testSending = true
                                    scope.launch {
                                        try {
                                            val result = proactiveScheduler.runCycle(
                                                io.zer0.muse.proactive.ProactiveScheduler.TRIGGER_MANUAL,
                                                force = true,
                                            )
                                            MuseToast.show(
                                                if (result.alreadyRunning) testAlreadyRunningText else result.detail,
                                                3500,
                                            )
                                        } catch (e: Exception) {
                                            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                                            Logger.w("AgentSettingsPage", "测试主动消息失败: ${e.message}")
                                            MuseToast.show(
                                                context.getString(R.string.settings_agent_test_failed, e.message),
                                            )
                                        } finally {
                                            testSending = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item(key = "proactive_background_group") {
                CardGroup(title = { Text(stringResource(R.string.settings_agent_group_background)) }) {
                    navigationItem(
                        key = "proactive_keep_alive_nav",
                        title = stringResource(R.string.settings_keep_alive_title),
                        subtitle = stringResource(R.string.settings_agent_background_redirect_hint),
                        leadingContent = {
                            Icon(
                                MuseIcons.battery,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = onOpenKeepAlive,
                    )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            confirmText = stringResource(R.string.common_save),
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
            dismissText = stringResource(R.string.common_cancel),
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
                        valueFormatter = {
                            if (it.toInt() <= 0) {
                                context.getString(R.string.settings_agent_moment_count_off)
                            } else {
                                context.getString(R.string.settings_agent_moment_count_per_day, it.toInt())
                            }
                        },
                    )
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch { settings.saveDailyMomentCount(alignedValue) }
                showMomentCountPicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            confirmText = stringResource(R.string.common_close),
            onConfirm = { showMomentContentModePicker = false },
        )
    }

    // ── 免打扰时段弹窗（起止两个滑块）──
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_agent_quiet_hours_start),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            confirmText = stringResource(R.string.common_save),
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
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showQuietHoursPicker = false },
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showAssistantPicker = false },
        )
    }

    if (showIntervalPicker) {
        var minutes by rememberSaveable { mutableStateOf(proactiveConfig.intervalMinutes.coerceIn(30, 1440)) }
        MuseDialog(
            onDismissRequest = { showIntervalPicker = false },
            title = stringResource(R.string.proactive_interval_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = intervalLabel(minutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = MuseSpacing.sm),
                    )
                    Text(
                        text = stringResource(R.string.proactive_interval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = MuseSpacing.md),
                    )
                    MuseSlider(
                        value = minutes.toFloat(),
                        onValueChange = { minutes = (it.toInt() / 15) * 15 },
                        valueRange = 30f..1440f,
                        valueFormatter = { "${it.toInt()}m" },
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = MuseSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        io.zer0.muse.data.proactive.ProactivePace.entries.forEach { pace ->
                            MuseChip(
                                selected = minutes == pace.intervalMinutes &&
                                    proactiveConfig.sendProbability == pace.sendProbability,
                                onClick = {
                                    minutes = pace.intervalMinutes
                                    scope.launch {
                                        settings.saveProactiveMessageConfig(
                                            proactiveConfig.copy(
                                                intervalMinutes = pace.intervalMinutes,
                                                sendProbability = pace.sendProbability,
                                            ),
                                        )
                                    }
                                },
                                label = intervalLabel(pace.intervalMinutes),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(intervalMinutes = minutes),
                    )
                }
                showIntervalPicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showIntervalPicker = false },
        )
    }

    if (showProbabilityPicker) {
        var pct by rememberSaveable { mutableStateOf(proactiveConfig.sendProbability.coerceIn(10, 100)) }
        MuseDialog(
            onDismissRequest = { showProbabilityPicker = false },
            title = stringResource(R.string.proactive_prob_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = MuseSpacing.sm),
                    )
                    Text(
                        text = stringResource(R.string.proactive_prob_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = MuseSpacing.md),
                    )
                    MuseSlider(
                        value = pct.toFloat(),
                        onValueChange = { pct = (it.toInt() / 5) * 5 },
                        valueRange = 10f..100f,
                        valueFormatter = { "${it.toInt()}%" },
                    )
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(sendProbability = pct),
                    )
                }
                showProbabilityPicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showProbabilityPicker = false },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            intervalLabel(maxOffset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(randomOffsetMinutes = alignedMinutes),
                    )
                }
                showOffsetPicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showOffsetPicker = false },
        )
    }

    if (showGuaranteePicker) {
        var hours by rememberSaveable { mutableStateOf(proactiveConfig.dailyGuaranteeHours) }
        MuseDialog(
            onDismissRequest = { showGuaranteePicker = false },
            title = stringResource(R.string.settings_agent_daily_guarantee),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = guaranteeLabel(hours),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_daily_guarantee_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = hours.toFloat(),
                        onValueChange = { hours = it.toInt() },
                        valueRange = 0f..48f,
                        // 滑块内的格式化器不是 composable 上下文，只能给纯数字；
                        // 带单位的文案由上方标题展示。
                        valueFormatter = { it.toInt().toString() },
                    )
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(dailyGuaranteeHours = hours),
                    )
                }
                showGuaranteePicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showGuaranteePicker = false },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.settings_agent_temperature_creative),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(temperature = alignedValue))
                }
                showTemperaturePicker = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showTemperaturePicker = false },
        )
    }
}

/** 独立主动消息设置页：复用 Agent 页的完整主动消息设置，不复制状态逻辑。 */
@Composable
fun ProactiveMessageSettingsPage(onBack: () -> Unit, onOpenDirectives: () -> Unit = {}, onOpenKeepAlive: () -> Unit = {}) {
    AgentSettingsPage(
        onBack = onBack,
        onOpenMultiAgentSettings = {},
        onOpenDirectives = onOpenDirectives,
        onOpenKeepAlive = onOpenKeepAlive,
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

/** 日保底的显示文案：0 表示关闭，其余显示小时数。 */
@Composable
private fun guaranteeLabel(hours: Int): String {
    if (hours <= 0) return stringResource(R.string.settings_agent_daily_guarantee_off)
    return stringResource(R.string.settings_agent_daily_guarantee_hours, hours)
}
