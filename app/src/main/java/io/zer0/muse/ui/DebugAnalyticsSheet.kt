@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.analytics.AnalyticsSnapshot
import io.zer0.muse.data.analytics.LocalAnalyticsTracker
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

// ════════════════════════════════════════════════════════════════════════════
// P3-2:本地数据分析面板 — 把 LocalAnalyticsTracker 已采集但未 UI 化的指标透出
//
//  数据源:LocalAnalyticsTracker.getSnapshot() → AnalyticsSnapshot
//         LocalAnalyticsTracker.getFeatureUsage() → List<Pair<String, Int>>(已按 count 降序)
//  隐私:所有数据仅本地 DataStore,无任何上报
// ════════════════════════════════════════════════════════════════════════════

/**
 * 本地数据分析底部面板。
 *
 * 展示内容:
 *  - 核心指标卡片:DAU 今日 / MAU 本月 / 总会话 / 总消息 / 启动次数 / 崩溃次数(含崩溃率)
 *  - 留存卡片:D1 / D7 / D30 留存标记
 *  - 最近活跃日期
 *  - 功能使用 Top 10(按 count 降序,空时提示)
 */
@Composable
internal fun AnalyticsSheet(onDismiss: () -> Unit) {
    val tracker: LocalAnalyticsTracker = koinInject()

    var snapshot by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var featureUsage by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        snapshot = tracker.getSnapshot()
        featureUsage = tracker.getFeatureUsage()
        loading = false
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_analytics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_analytics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            snapshot == null -> {
                Text(
                    text = stringResource(R.string.debug_analytics_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                val s = snapshot!!
                // ── 核心指标:2 列网格 ─────────────────────────────────────────
                MetricGrid(
                    metrics = listOf(
                        stringResource(R.string.debug_metric_dau_today) to s.dauToday.toString(),
                        stringResource(R.string.debug_metric_mau_month) to s.mauMonth.toString(),
                        stringResource(R.string.debug_metric_total_sessions) to s.totalSessions.toString(),
                        stringResource(R.string.debug_metric_total_messages) to s.totalMessages.toString(),
                        stringResource(R.string.debug_metric_launch_count) to s.launchCount.toString(),
                        stringResource(R.string.debug_metric_crash_count) to
                            stringResource(
                                R.string.debug_metric_crash_count_value,
                                s.crashCount,
                                formatPercent(s.crashRate),
                            ),
                    ),
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 留存卡片 ─────────────────────────────────────────────────
                RetentionCard(
                    d1 = s.d1Retention,
                    d7 = s.d7Retention,
                    d30 = s.d30Retention,
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 其他状态:首次对话 / 记忆系统 / 最近活跃 ──────────────────
                StatusRow(
                    label = stringResource(R.string.debug_status_first_chat),
                    value = if (s.firstChatCompleted) {
                        stringResource(R.string.debug_value_yes)
                    } else {
                        stringResource(R.string.debug_value_no)
                    },
                )
                StatusRow(
                    label = stringResource(R.string.debug_status_memory_adopted),
                    value = if (s.memoryAdopted) {
                        stringResource(R.string.debug_value_yes)
                    } else {
                        stringResource(R.string.debug_value_no)
                    },
                )
                if (s.lastActiveDate.isNotBlank()) {
                    StatusRow(label = stringResource(R.string.debug_status_last_active), value = s.lastActiveDate)
                }

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── 功能使用 Top 10 ──────────────────────────────────────────
                FeatureUsageSection(usage = featureUsage)
            }
        }
    }
}

/**
 * 指标网格 — 2 列展示键值对,简洁卡片样式。
 */
@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    // 用 columnCount=2 的简化网格:每两个一组渲染一行
    val rows = metrics.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                rowItems.forEach { (label, value) ->
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // 若最后一行只有 1 个,补一个占位以保持网格对齐
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 留存卡片:D1 / D7 / D30 三段进度。
 */
@Composable
private fun RetentionCard(d1: Int, d7: Int, d30: Int) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
            Text(
                text = stringResource(R.string.debug_retention_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RetentionItem(label = stringResource(R.string.debug_retention_d1), retained = d1)
                RetentionItem(label = stringResource(R.string.debug_retention_d7), retained = d7)
                RetentionItem(label = stringResource(R.string.debug_retention_d30), retained = d30)
            }
        }
    }
}

@Composable
private fun RetentionItem(label: String, retained: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (retained > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (retained > 0) {
                        Icons.Filled.Check
                    } else {
                        Icons.Filled.Close
                    },
                    contentDescription = null,
                    tint = if (retained > 0) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 键值对行(标签 + 值)。 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 功能使用 Top 10 列表。
 *
 * 空列表显示提示;非空时显示条形图风格的进度条(相对最大值的比例)。
 */
@Composable
private fun FeatureUsageSection(usage: List<Pair<String, Int>>) {
    Text(
        text = stringResource(R.string.debug_feature_usage_top),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (usage.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_no_feature_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MusePaddings.contentGap),
        )
    } else {
        val maxCount = usage.maxOf { it.second }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            usage.take(10).forEach { (name, count) ->
                FeatureUsageRow(
                    name = name,
                    count = count,
                    fraction = count.toFloat() / maxCount,
                )
            }
        }
    }
}

@Composable
private fun FeatureUsageRow(name: String, count: Int, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        // 简化条形图:用 Surface 高度表示比例
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(fraction),
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
        }
    }
}

/** 把 0..1 的浮点数格式化为百分比字符串(保留 1 位小数)。 */
private fun formatPercent(value: Float): String = "%.1f%%".format(value * 100)
