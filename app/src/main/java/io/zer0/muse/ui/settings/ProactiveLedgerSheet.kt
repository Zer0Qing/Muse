package io.zer0.muse.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtSource
import io.zer0.muse.data.proactive.ThoughtStatus
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing

/**
 * 牵挂账本面板（用户可见、可管理）。
 *
 * 除展示待发送牵挂与今日额度外，支持：
 *  - 标记已完成 / 移除误记牵挂；
 *  - 手动「记一条」主动提醒或关心；
 *  - 查看历史已送达/过期/放弃记录。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProactiveLedgerSheet(
    state: LedgerUiState,
    onIntensityChange: (ProactiveIntensity) -> Unit,
    onResetIntensity: () -> Unit,
    onCooldown: () -> Unit,
    onClearCooldown: () -> Unit,
    onDismiss: () -> Unit,
    onCompleteThought: (String) -> Unit = {},
    onDropThought: (String) -> Unit = {},
    onAddThought: (seed: String, kind: ThoughtKind, triggerAt: Long?) -> Unit = { _, _, _ -> },
) {
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    if (showAddDialog) {
        AddThoughtDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { seed, kind, triggerAt ->
                onAddThought(seed, kind, triggerAt)
                showAddDialog = false
            },
        )
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_proactive_v2_ledger),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.proactive_ledger_add_btn),
                    onClick = { showAddDialog = true },
                    variant = IosCapsuleButtonVariant.Secondary,
                    size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 主动等级 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_proactive_v2_intensity),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_proactive_v2_reset),
                    onClick = onResetIntensity,
                    variant = IosCapsuleButtonVariant.Text,
                    size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
            Spacer(Modifier.size(MuseSpacing.xs))
            IntensityRow(current = state.intensity, onSelect = onIntensityChange)
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 今日额度 ──
            val unlimited = state.intensity.unlimited
            val remainingTimes = if (state.remainingBudget >= 1f) state.remainingBudget.toInt() else 0
            Text(
                text = when {
                    state.cooldownUntil > state.now -> stringResource(R.string.settings_proactive_v2_cooldown_sub)
                    unlimited -> stringResource(R.string.settings_proactive_v2_budget_unlimited)
                    else -> stringResource(R.string.settings_proactive_v2_budget, remainingTimes)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 双页签切换：待发送 / 历史与记录 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                MuseChip(
                    label = stringResource(R.string.proactive_ledger_tab_open, state.thoughts.size),
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                )
                MuseChip(
                    label = stringResource(R.string.proactive_ledger_tab_history, state.historyThoughts.size),
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                )
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            val displayed = if (activeTab == 0) state.thoughts else state.historyThoughts
            if (displayed.isEmpty()) {
                Text(
                    text = stringResource(
                        if (activeTab == 0) R.string.settings_proactive_v2_empty else R.string.proactive_ledger_history_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MuseSpacing.md),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    items(displayed, key = { it.id }) { thought ->
                        ThoughtCardRow(
                            thought = thought,
                            now = state.now,
                            isOpenTab = activeTab == 0,
                            onComplete = { onCompleteThought(thought.id) },
                            onDrop = { onDropThought(thought.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 冷却开关 ──
            val coolingDown = state.cooldownUntil > state.now
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MuseIconSizes.touchTarget)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
                    )
                    .clickable(onClick = if (coolingDown) onClearCooldown else onCooldown)
                    .padding(vertical = MuseSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (coolingDown) R.string.settings_proactive_v2_resume else R.string.settings_proactive_v2_cooldown,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (coolingDown) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 面板的全部展示状态。新增字段均带默认值以保持已有调用兼容。 */
data class LedgerUiState(
    val thoughts: List<PendingThoughtEntity>,
    val intensity: ProactiveIntensity,
    val remainingBudget: Float,
    val cooldownUntil: Long,
    val now: Long,
    val historyThoughts: List<PendingThoughtEntity> = emptyList(),
)

/**
 * 填充比例：第 [index] 档占轨道的多少。
 */
internal fun intensityFillFraction(index: Int, count: Int): Float {
    if (count <= 0) return 0f
    return (index.coerceIn(0, count - 1) + 1f) / count.toFloat()
}

/**
 * 把手中心落点比例。
 */
internal fun intensityHandleFraction(index: Int, count: Int): Float {
    if (count <= 0) return 0f
    return (index.coerceIn(0, count - 1) + 0.5f) / count.toFloat()
}

/**
 * 主动程度选择器。保留 5 个等宽点击格（每格均独立呈现名称，点击热区 >= 48dp）。
 */
@Composable
internal fun IntensityRow(current: ProactiveIntensity, onSelect: (ProactiveIntensity) -> Unit) {
    val levels = ProactiveIntensity.entries
    val selectedIndex = levels.indexOf(current).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp)
                .fillMaxWidth(intensityFillFraction(selectedIndex, levels.size))
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(3.dp),
                ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, level ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clickable { if (!selected) onSelect(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp),
                                )
                                .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
                        ) {
                            Text(
                                text = stringResource(level.labelRes()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(level.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtCardRow(thought: PendingThoughtEntity, now: Long, isOpenTab: Boolean, onComplete: () -> Unit, onDrop: () -> Unit) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    BadgePill(
                        label = stringResource(thought.kindEnum().labelRes()),
                        accent = true,
                    )
                    BadgePill(
                        label = stringResource(thought.sourceEnum().labelRes()),
                        accent = false,
                    )
                    if (!isOpenTab) {
                        BadgePill(
                            label = stringResource(thought.statusEnum().labelRes()),
                            accent = false,
                        )
                    } else if (thought.surfaceCount > 0) {
                        BadgePill(
                            label = stringResource(R.string.proactive_ledger_surfaced_count, thought.surfaceCount),
                            accent = false,
                        )
                    }
                    thought.triggerAt?.let { scheduledAt ->
                        val formatted = remember(scheduledAt) {
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(scheduledAt))
                        }
                        val isDue = scheduledAt <= now
                        BadgePill(
                            label = if (isDue) {
                                stringResource(R.string.proactive_ledger_scheduled_due, formatted)
                            } else {
                                stringResource(R.string.proactive_ledger_scheduled_at, formatted)
                            },
                            accent = isDue,
                        )
                    }
                }
                Spacer(Modifier.size(MuseSpacing.xs))
                Text(
                    text = thought.seed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(MuseSpacing.xxs))
                Text(
                    text = describeAge(thought, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isOpenTab) {
                Spacer(Modifier.width(MuseSpacing.xs))
                MuseTactileButton(
                    icon = MuseIcons.check,
                    onClick = onComplete,
                    contentDescription = stringResource(R.string.proactive_ledger_mark_done),
                    tint = MaterialTheme.colorScheme.primary,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                MuseTactileButton(
                    icon = MuseIcons.trash,
                    onClick = onDrop,
                    contentDescription = stringResource(R.string.proactive_ledger_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
        }
    }
}

@Composable
private fun BadgePill(label: String, accent: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(MuseCornerRadius.BUBBLE_TAIL.dp),
            )
            .padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddThoughtDialog(onDismiss: () -> Unit, onConfirm: (seed: String, kind: ThoughtKind, triggerAt: Long?) -> Unit) {
    var seed by rememberSaveable { mutableStateOf("") }
    var selectedKind by rememberSaveable { mutableStateOf(ThoughtKind.REMINDER) }
    var selectedScheduleIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectableKinds = listOf(ThoughtKind.REMINDER, ThoughtKind.FOLLOW_UP, ThoughtKind.CARE, ThoughtKind.SHARE)
    val scheduleLabels = listOf(
        R.string.proactive_ledger_schedule_none,
        R.string.proactive_ledger_schedule_30m,
        R.string.proactive_ledger_schedule_2h,
        R.string.proactive_ledger_schedule_tomorrow_morning,
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.proactive_ledger_add_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MuseTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text(stringResource(R.string.proactive_ledger_add_seed_label)) },
                    placeholder = { Text(stringResource(R.string.proactive_ledger_add_seed_hint)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(MuseSpacing.md))
                Text(
                    text = stringResource(R.string.proactive_ledger_add_kind_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(MuseSpacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    selectableKinds.forEach { kind ->
                        MuseChip(
                            label = stringResource(kind.labelRes()),
                            selected = kind == selectedKind,
                            onClick = { selectedKind = kind },
                        )
                    }
                }
                Spacer(Modifier.size(MuseSpacing.md))
                Text(
                    text = stringResource(R.string.proactive_ledger_add_schedule_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(MuseSpacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    scheduleLabels.forEachIndexed { idx, labelRes ->
                        MuseChip(
                            label = stringResource(labelRes),
                            selected = idx == selectedScheduleIndex,
                            onClick = { selectedScheduleIndex = idx },
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            val trimmed = seed.trim()
            if (trimmed.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val triggerAt = when (selectedScheduleIndex) {
                    1 -> now + 30L * 60_000L
                    2 -> now + 2L * 3_600_000L
                    3 -> java.util.Calendar.getInstance().apply {
                        timeInMillis = now
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 9)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    else -> null
                }
                onConfirm(trimmed, selectedKind, triggerAt)
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
    val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
    return when {
        hours < 1 -> stringResource(R.string.proactive_ledger_age_now)
        hours < 24 -> stringResource(R.string.proactive_ledger_age_hours, hours)
        else -> stringResource(R.string.proactive_ledger_age_days, hours / 24)
    }
}

private fun ProactiveIntensity.labelRes(): Int = when (this) {
    ProactiveIntensity.QUIET -> R.string.proactive_intensity_quiet
    ProactiveIntensity.RESERVED -> R.string.proactive_intensity_reserved
    ProactiveIntensity.STANDARD -> R.string.proactive_intensity_standard
    ProactiveIntensity.ACTIVE -> R.string.proactive_intensity_active
    ProactiveIntensity.CLINGY -> R.string.proactive_intensity_clingy
}

private fun ThoughtKind.labelRes(): Int = when (this) {
    ThoughtKind.REMINDER -> R.string.proactive_kind_reminder
    ThoughtKind.FOLLOW_UP -> R.string.proactive_kind_follow_up
    ThoughtKind.CARE -> R.string.proactive_kind_care
    ThoughtKind.SHARE -> R.string.common_share
    ThoughtKind.MILESTONE -> R.string.proactive_kind_milestone
}

private fun ThoughtSource.labelRes(): Int = when (this) {
    ThoughtSource.CONVERSATION -> R.string.proactive_ledger_source_conversation
    ThoughtSource.MEMORY -> R.string.proactive_ledger_source_memory
    ThoughtSource.MILESTONE -> R.string.proactive_ledger_source_milestone
    ThoughtSource.USER_DEFINED -> R.string.proactive_ledger_source_manual
}

private fun ThoughtStatus.labelRes(): Int = when (this) {
    ThoughtStatus.OPEN -> R.string.proactive_ledger_status_open
    ThoughtStatus.DONE -> R.string.proactive_ledger_status_done
    ThoughtStatus.EXPIRED -> R.string.proactive_ledger_status_expired
    ThoughtStatus.DROPPED -> R.string.proactive_ledger_status_dropped
}
