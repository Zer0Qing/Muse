package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
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
                style = MaterialTheme.typography.titleLarge,
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

/**
 * 填充比例：第 [index] 档占轨道的多少。整条轨道代表"额度全部用完"，
 * 所以是 `(index + 1) / count` 而不是 `index / (count - 1)` —— 最左一档也该有可见的填充，
 * 否则用户会以为"安静"= 一格都没有（等于关掉），语义就糊了。
 */
internal fun intensityFillFraction(index: Int, count: Int): Float {
    if (count <= 0) return 0f
    return (index.coerceIn(0, count - 1) + 1f) / count.toFloat()
}

/**
 * 把手中心落在哪：第 [index] 档那一格的**中点**，不是右边界。
 *
 * 用右边界的话最右档的把手会贴边被裁，最左档则跑到起点外面 ——
 * 参照平台是把把手停在格子中间，读起来才像"当前在这格"。
 */
internal fun intensityHandleFraction(index: Int, count: Int): Float {
    if (count <= 0) return 0f
    return (index.coerceIn(0, count - 1) + 0.5f) / count.toFloat()
}

/**
 * 主动程度选择器（整改计划 第 4 条差异①）。
 *
 * 参照形态是一条**连续轨道 + 已填充段 + 带当前档位名的把手**，而不是一排各自独立的按钮：
 * 五档是同一根"热情度"刻度上的位置，用分离按钮表达会读成五个互不相干的开关。
 *
 * 实现上刻意**不用 Material3 的 `Slider`**：
 *  - `check_component_convergence` 禁止业务页直接写裸 M3 `Slider`（必须先有 Muse 封装）；
 *  - M3 Slider 的把手只能拖动、不能"点某档直达"，而这里五档是离散选择，点才是主交互。
 * 所以是自建轨道 + 五个 `weight(1f)`、高 56dp 的点击格（点击区 ≥48dp，满足 touch target 门禁）。
 */
@Composable
private fun IntensityRow(current: ProactiveIntensity, onSelect: (ProactiveIntensity) -> Unit) {
    val levels = ProactiveIntensity.entries
    val selectedIndex = levels.indexOf(current).coerceAtLeast(0)

    // 轨道 + 把手 + 点击格叠在同一块里：把手要压在轨道上，只能同层定位。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        // 轨道底：一整条 surfaceVariant
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp),
                ),
        )
        // 已填充段：primary，长度由纯函数算出（可测）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(intensityFillFraction(selectedIndex, levels.size))
                .height(6.dp)
                .padding(top = 12.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(3.dp),
                ),
        )
        // 五个等宽点击格：档位名只在选中格上显示（就是参照里那颗把手）
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
                        // 把手胶囊：档位名写在里面，用户不用猜"这一格是什么"
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(14.dp),
                                ).padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(level.labelRes()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    } else {
                        // 非选中档留一个刻度点：让人知道这里还有别的选择，而不是只有一格能按
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(top = 12.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
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
    ThoughtKind.SHARE -> R.string.proactive_kind_share
    ThoughtKind.MILESTONE -> R.string.proactive_kind_milestone
}
