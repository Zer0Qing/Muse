package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 牵挂账本面板。
 *
 * 这块 UI 是新设计的兑现方式：用户能看见「它凭什么找我」，
 * 也能看见「它什么时候闭嘴」。
 *
 * 特意不展示 fitness 分数、阈值、intimacy 这些内部参数 ——
 * 那些是给日志看的，不是给用户看的。用户要看的是「它在惦记什么」
 * 和「今天还能不能被打扰」。
 */
@Composable
fun ProactiveLedgerSheet(
    state: LedgerUiState,
    onIntensityChange: (ProactiveIntensity) -> Unit,
    onResetIntensity: () -> Unit,
    onCooldown: () -> Unit,
    onClearCooldown: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_proactive_v2_ledger),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
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
                )
                // 试过「黏人」想回默认时不该自己一格一格往回点，而且今天已经花掉的
                // 额度也得跟着回满 —— 这是用户要的「重置」。
                Text(
                    text = stringResource(R.string.settings_proactive_v2_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onResetIntensity).padding(4.dp),
                )
            }
            Spacer(Modifier.size(6.dp))
            IntensityRow(current = state.intensity, onSelect = onIntensityChange)
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 今日额度 ──
            val unlimited = state.intensity.unlimited
            val remainingTimes = if (state.remainingBudget >= 1f) state.remainingBudget.toInt() else 0
            Text(
                text = when {
                    state.cooldownUntil > state.now -> stringResource(R.string.settings_proactive_v2_cooldown_sub)
                    // 黏人档的额度是 Int.MAX_VALUE，直接渲染会变成「今天还能说 2147483647 次」
                    unlimited -> stringResource(R.string.settings_proactive_v2_budget_unlimited)
                    else -> stringResource(R.string.settings_proactive_v2_budget, remainingTimes)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 账本列表 ──
            if (state.thoughts.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_proactive_v2_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.thoughts, key = { it.id }) { thought ->
                        ThoughtRow(thought = thought, now = state.now)
                    }
                }
            }

            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── 冷却开关 ──
            // 冷却中时这里必须变成「恢复」：只给单向入口的话，点错了就只能等七天。
            val coolingDown = state.cooldownUntil > state.now
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    ).clickable(onClick = if (coolingDown) onClearCooldown else onCooldown)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (coolingDown) {
                            R.string.settings_proactive_v2_resume
                        } else {
                            R.string.settings_proactive_v2_cooldown
                        },
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

/** 面板的全部展示状态。打包成一个类型，是为了让调用处只用一个参数就能对齐。 */
data class LedgerUiState(
    val thoughts: List<PendingThoughtEntity>,
    val intensity: ProactiveIntensity,
    val remainingBudget: Float,
    val cooldownUntil: Long,
    val now: Long,
)

/** 五档等级横向排开。点一下即生效，不需要确认。 */
@Composable
private fun IntensityRow(current: ProactiveIntensity, onSelect: (ProactiveIntensity) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProactiveIntensity.entries.forEach { level ->
            val selected = level == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(10.dp),
                    ).clickable { if (!selected) onSelect(level) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(level.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 单条牵挂。左侧性质标签，右侧事由与时间感。 */
@Composable
private fun ThoughtRow(thought: PendingThoughtEntity, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(thought.kindEnum().labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thought.seed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = describeAge(thought, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 事由的时间感。用户看「三天前想起」比看时间戳有感觉。 */
@Composable
private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
    val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
    return when {
        hours < 1 -> "刚刚"
        hours < 24 -> "${hours} 小时前"
        else -> "${hours / 24} 天前"
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
    ThoughtKind.SHARE -> R.string.proactive_kind_share
    ThoughtKind.MILESTONE -> R.string.proactive_kind_milestone
}
