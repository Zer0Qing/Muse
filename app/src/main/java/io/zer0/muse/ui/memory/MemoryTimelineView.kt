@file:Suppress("FunctionNaming") // Compose 组件名按惯例大写开头（与项目内其它 UI 文件一致）

package io.zer0.muse.ui.memory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.AmberWarmth
import io.zer0.muse.ui.theme.CoralWhisper
import io.zer0.muse.ui.theme.LavenderDream
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.SageCalm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 2 2A: 记忆时间轴视图组件 — 筛选行、月份标题与事件卡片。
 *
 * v2.1.x 修复「点时间轴即崩溃」: 原 MemoryTimelineView 自带 LazyColumn,
 * 在 MemoryScreen 外层 LazyColumn 的 item 中渲染构成嵌套滚动, 内层 LazyColumn
 * 测得无限高约束, 抛 "Vertically scrollable component was measured with an
 * infinity maximum height constraints"。
 * 现将时间轴内容拍平到外层 LazyListScope(见 MemoryScreen.memoryStreamItems),
 * 本文件只保留无滚动的子组件。
 */

/** 时间轴内部筛选行(全部 / 事实 / 摘要 / 里程碑)。 */
@Composable
internal fun MemoryTimelineFilterRow(selected: String, onSelect: (String) -> Unit) {
    val filters = listOf(
        "all" to stringResource(R.string.memory_timeline_filter_all),
        "fact" to stringResource(R.string.memory_timeline_filter_fact),
        "summary" to stringResource(R.string.memory_timeline_filter_summary),
        "milestone" to stringResource(R.string.memory_timeline_filter_milestone),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (key, label) ->
            MuseChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = label,
            )
        }
    }
}

@Composable
internal fun MonthHeader(month: String) {
    Text(
        text = month,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineEventCard(
    item: TimelineItem,
    // v2.6.10: 时间轴左侧那串圈改成多选圈——用户反馈记忆会乱记/重复记，
    // 逐条删又会被刷回列表顶部，长按选中、批量删才是好用路径。
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
) {
    // v2.7（用户反馈）：时间轴卡片长文可点击展开查看全文。
    //   此前内容硬性 maxLines=3 截断，且无展开入口，长记忆只能进编辑看。
    //   阈值与列表模式 MemoryFactRow 保持一致（含换行即视为可展开）。
    var expanded by remember(item.id) { mutableStateOf(false) }
    val canExpand = item.content.length > TIMELINE_EXPAND_THRESHOLD || item.content.contains('\n')
    val toggleExpand = { if (canExpand) expanded = !expanded }

    // 整卡只装一套手势。正文 Surface 上再挂一层 clickable 会把长按吃掉：
    // 手指落在卡片主体上时长按根本到不了这里，只有左侧那串窄节点能触发。
    // 所以点击(展开/勾选)与长按都收在这一处，覆盖整张卡。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .then(
                if (onLongPress == null) {
                    Modifier.clickable(enabled = canExpand) { toggleExpand() }
                } else {
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect?.invoke() else toggleExpand() },
                        onLongClick = onLongPress,
                    )
                },
            ),
    ) {
        TimelineNode(item, selectionMode, isSelected, onToggleSelect)
        Spacer(Modifier.width(8.dp))
        TimelineEventCardBody(item, expanded, canExpand)
    }
}

/** 时间轴左侧节点（重要性颜色/图标 + 连接线）。 */
@Composable
private fun TimelineNode(
    item: TimelineItem,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    val nodeColor = importanceColor(item.importance)
    val nodeIcon = if (item.importance == 2) MuseIcons.star else MuseIcons.circle
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(24.dp),
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
                contentDescription = null,
                tint =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier =
                Modifier
                    .size(16.dp)
                    .then(if (onToggleSelect != null) Modifier.clickable { onToggleSelect() } else Modifier),
            )
        } else {
            Icon(
                imageVector = nodeIcon,
                // A11Y-05: 状态不只靠颜色传达 — 补重要性文本描述
                contentDescription = importanceLabel(item.importance),
                tint = nodeColor,
                modifier = Modifier.size(12.dp),
            )
        }
        // 竖向连接线
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 时间轴右侧事件卡片主体（标签 + 内容 + 展开 + 时间）。 */
@Composable
private fun TimelineEventCardBody(item: TimelineItem, expanded: Boolean, canExpand: Boolean) {
    val nodeColor = importanceColor(item.importance)
    // 点击手势由 [TimelineEventCard] 统一接管（见那里的注释），这里不再挂 clickable。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimelineEventHeader(item, nodeColor)
            // 内容（v2.7：可展开查看全文，不再硬性 3 行截断）
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            // v2.7: 展开/收起入口（与列表模式同款文案）
            if (canExpand) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.memory_screen_collapse else R.string.memory_screen_expand,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TimelineEventTime(item.createdAt)
        }
    }
}

/** 事件卡片顶部行：来源标签 + 重要性星标。 */
@Composable
private fun TimelineEventHeader(item: TimelineItem, nodeColor: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = MuseShapes.small,
            color = nodeColor.copy(alpha = 0.15f),
        ) {
            Text(
                text = item.source,
                style = MaterialTheme.typography.labelSmall,
                color = nodeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (item.importance > 0) {
            Row {
                repeat(item.importance) {
                    Icon(
                        MuseIcons.star,
                        contentDescription = null,
                        tint = AmberWarmth,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/** 事件时间戳（解析失败时不渲染）。 */
@Composable
private fun TimelineEventTime(createdAt: String?) {
    val timeStr = remember(createdAt) {
        try {
            val dt = Instant.parse(createdAt).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("MM-dd HH:mm").format(dt)
        } catch (_: Exception) {
            ""
        }
    }
    if (timeStr.isNotBlank()) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 重要性 → 节点颜色。 */
private fun importanceColor(importance: Int) = when (importance) {
    2 -> CoralWhisper
    1 -> LavenderDream
    else -> SageCalm
}

/** 重要性 → 无障碍文本描述。 */
@Composable
private fun importanceLabel(importance: Int): String = when (importance) {
    2 -> stringResource(R.string.memory_importance_critical)
    1 -> stringResource(R.string.memory_importance_important)
    else -> stringResource(R.string.memory_importance_normal)
}

/**
 * 简化的时间轴条目数据类(在 ViewModel 中由 MemoryItem 映射而来)。
 */
data class TimelineItem(
    val id: String,
    val content: String,
    val source: String,
    val importance: Int,
    val createdAt: String?,
    val tags: List<String> = emptyList(),
    /**
     * 底层条目的真正 id:Facts 为 fact.id,摘要为其所属 sessionId。
     *
     * [id] 是带 source 前缀的展示/动画键(跨分库会撞号),删不掉东西,删除必须回到这个原始 id。
     */
    val itemId: String = id,
    /** 摘要所属会话 id(事实条目带的是来源会话),删除摘要按它落库。 */
    val sessionId: String? = null,
    /** 所属助手分库;各分库 fact.id 独立自增,删除必须带 scope 才能删对那一条。 */
    val scope: String? = null,
)

/** v2.7: 时间轴卡片"可展开"判定阈值（与列表模式 MemoryFactRow 同口径）。 */
private const val TIMELINE_EXPAND_THRESHOLD = 80
