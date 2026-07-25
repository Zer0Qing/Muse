package io.zer0.muse.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Circle
import io.zer0.muse.ui.common.IosChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.AmberWarmth
import io.zer0.muse.ui.theme.CoralWhisper
import io.zer0.muse.ui.theme.LavenderDream
import io.zer0.muse.ui.theme.SageCalm
import io.zer0.muse.ui.theme.MuseShapes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 2 2A: 记忆时间轴视图 — 按月分组 + 时间轴节点 + 事件卡片。
 *
 * @param items 记忆条目列表 (MemoryItem from ViewModel)
 * @param modifier 修饰符
 */
@Composable
fun MemoryTimelineView(
    items: List<TimelineItem>,
    modifier: Modifier = Modifier,
    // v1.0.4: 顶部 header(列表/时间轴切换 + 作用域筛选)随列表一起滚动
    headerContent: @Composable () -> Unit = {},
) {
    // 按月份分组
    val grouped = remember(items) {
        items.groupBy { item ->
            try {
                val instant = Instant.parse(item.createdAt)
                val dt = instant.atZone(ZoneId.systemDefault())
                "${dt.year}-${dt.monthValue.toString().padStart(2, '0')}"
            } catch (_: Exception) {
                "Unknown"
            }
        }.toSortedMap(compareByDescending { it })
    }

    var selectedFilter by remember { mutableStateOf("all") }
    val filters = listOf("all" to "全部", "fact" to "事实", "summary" to "摘要", "milestone" to "里程碑")

    // v1.0.3 修复崩溃: 去掉外层 Column,直接用 LazyColumn(modifier)。
    // 原结构 Column(fillMaxSize) { LazyColumn(fillMaxWidth) } 会让 LazyColumn 拿到
    // maxHeight = infinity(Column 允许子组件无限堆叠),触发
    // "Vertically scrollable component was measured with an infinity maximum height constraints" 崩溃。
    // LazyColumn 直接用传入的 modifier(从 MemoryScreen 传入 Modifier.fillMaxSize()),
    // 会拿到 Column 父级分配的有限剩余高度,符合 Compose 嵌套滚动约束。
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // v1.0.4: 顶部 header(外层列表/时间轴切换 + 作用域筛选)
        // 与下方"时间轴内部筛选 + 时间轴列表"一起向上滚动
        item { headerContent() }
        // 时间轴内部 FilterChip row(全部 / 事实 / 摘要 / 里程碑)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { (key, label) ->
                    IosChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = label,
                    )
                }
            }
        }
        grouped.forEach { (month, monthItems) ->
            val filtered = if (selectedFilter == "all") monthItems
            else monthItems.filter { it.source == selectedFilter }

            if (filtered.isNotEmpty()) {
                // 月份标题
                item(key = "month_$month") {
                    MonthHeader(month = month)
                }
                // 时间轴条目
                items(filtered, key = { it.id }) { item ->
                    TimelineEventCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: String) {
    Text(
        text = month,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

@Composable
private fun TimelineEventCard(item: TimelineItem) {
    val nodeColor = when (item.importance) {
        2 -> CoralWhisper
        1 -> LavenderDream
        else -> SageCalm
    }
    val nodeIcon = when (item.importance) {
        2 -> Icons.Filled.Star
        else -> if (item.importance >= 1) Icons.Filled.Circle else Icons.Outlined.Circle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
    ) {
        // 时间轴节点
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp),
        ) {
            Icon(
                imageVector = nodeIcon,
                contentDescription = null,
                tint = nodeColor,
                modifier = Modifier.size(12.dp),
            )
            // 竖向连接线
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(8.dp))
        // 事件卡片
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 类型标签 + 重要性星标
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 来源标签
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
                    // 重要性星标
                    if (item.importance > 0) {
                        Row {
                            repeat(item.importance) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = AmberWarmth,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
                // 内容(最多 3 行)
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // 时间
                val timeStr = remember(item.createdAt) {
                    try {
                        val dt = Instant.parse(item.createdAt).atZone(ZoneId.systemDefault())
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
        }
    }
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
)
