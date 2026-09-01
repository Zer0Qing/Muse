@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)


package io.zer0.muse.ui

import io.zer0.muse.ui.theme.MuseMotion
import kotlinx.serialization.json.JsonArray
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.common.surface.CardGroup
import java.time.Instant

/**
 * P2: 通用内容编辑对话框。
 */
@Composable
internal fun FactEditDialog(
    title: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initialContent) { mutableStateOf(initialContent) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            MuseTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
                placeholder = { Text(stringResource(R.string.memory_screen_input_content)) },
            )
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (text.isNotBlank()) {
                onConfirm(text)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 新增元事实对话框。
 */
@Composable
internal fun AddFactDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    FactEditDialog(
        title = stringResource(R.string.memory_add_fact_dialog_title),
        initialContent = "",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * v4: 重要程度选择对话框。
 */
@Composable
internal fun ImportanceSelectDialog(
    currentImportance: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_importance_set_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_normal),
                    desc = stringResource(R.string.memory_importance_normal_desc),
                    selected = currentImportance == 0,
                    onClick = { onSelect(0) },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_important),
                    desc = stringResource(R.string.memory_importance_important_desc),
                    selected = currentImportance == 1,
                    onClick = { onSelect(1) },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_critical),
                    desc = stringResource(R.string.memory_importance_critical_desc),
                    selected = currentImportance == 2,
                    onClick = { onSelect(2) },
                )
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * v4: 重要程度选项行。
 */
@Composable
internal fun ImportanceOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * iOS/MANUS 概览统计卡片 — 3 列大数字 + 细竖线分隔。
 */
@Composable
internal fun OverviewStatCard(state: MemoryUiState) {
    val highImportanceCount = remember(state.factItems) {
        state.factItems.count { it.importance >= 1 }
    }

    CardGroup {
        item(
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverviewStatColumn(
                        value = state.totalFactCount.toString(),
                        label = stringResource(R.string.memory_overview_total),
                        modifier = Modifier.weight(1f),
                    )
                    VerticalHairline()
                    OverviewStatColumn(
                        value = highImportanceCount.toString(),
                        label = stringResource(R.string.memory_overview_important),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    VerticalHairline()
                    OverviewStatColumn(
                        value = "4",
                        label = stringResource(R.string.memory_overview_layers),
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )
    }
}

@Composable
internal fun OverviewStatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = MuseMonoFontFamily,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
internal fun VerticalHairline() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

/**
 * v0.51: 记忆 dashboard 概览卡片。
 *
 * 使用 [CardGroup] 作为容器,保持统计图表与健康详情。
 */
@Composable
internal fun MemoryDashboardCard(state: MemoryUiState) {
    val hasAnyError = state.healthMap.values.any { it.failCount > 0 }
    val healthText = if (state.healthMap.isEmpty()) {
        stringResource(R.string.memory_screen_not_running)
    } else if (hasAnyError) {
        stringResource(R.string.memory_screen_has_error)
    } else {
        stringResource(R.string.memory_screen_normal)
    }
    val healthColor = if (hasAnyError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    var healthExpanded by rememberSaveable { mutableStateOf(false) }
    var statsExpanded by rememberSaveable { mutableStateOf(true) }

    CardGroup {
        item(
            headlineContent = {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.memory_screen_overview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(12.dp))
                    DashboardMetricRow(label = stringResource(R.string.memory_screen_fact_total_label), value = state.factCount.toString())
                    DashboardMetricRow(
                        label = stringResource(R.string.memory_screen_last_compile),
                        value = state.lastCompileTime ?: stringResource(R.string.memory_screen_no_compile_time),
                    )
                    DashboardMetricRow(label = stringResource(R.string.memory_screen_health), value = healthText, valueColor = healthColor)

                    if (state.syncStatus.isNotBlank()) {
                        val isStale = state.syncStale
                        Spacer(Modifier.size(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = state.syncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Spacer(Modifier.size(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { statsExpanded = !statsExpanded }
                            .semantics { role = Role.Button }
                            .padding(vertical = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TrendingUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.memory_stats_section_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (statsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (statsExpanded) {
                        Spacer(Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatChip(label = stringResource(R.string.memory_stats_total), value = state.totalFactCount.toString())
                            StatChip(label = stringResource(R.string.memory_filter_week), value = state.weekNewCount.toString())
                            StatChip(label = stringResource(R.string.memory_filter_month), value = state.monthNewCount.toString())
                        }
                        Spacer(Modifier.size(12.dp))
                        if (state.importanceDistribution.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_stats_importance_distribution),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            ImportancePieChart(distribution = state.importanceDistribution)
                        }
                        if (state.topSessions.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.memory_stats_top_sessions),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            state.topSessions.take(3).forEach { (sid, count) ->
                                DashboardMetricRow(label = sid.take(12), value = stringResource(R.string.memory_stats_session_count, count))
                            }
                        }
                        if (state.dailyTrend.size >= 2) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.memory_stats_trend_30_days),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            TrendLineChart(dailyData = state.dailyTrend)
                        }
                    }

                    if (state.healthMap.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { healthExpanded = !healthExpanded }
                                .padding(vertical = MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (healthExpanded) stringResource(R.string.memory_screen_collapse_steps) else stringResource(R.string.memory_screen_view_steps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (healthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (healthExpanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = healthExpanded,
                            enter = MuseMotion.expandEnter(),
                            exit = MuseMotion.expandExit(),
                        ) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                state.healthMap.forEach { (step, health) ->
                                    HealthStepRow(stepKey = step, health = health)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * v5: 统计小标签。
 */
@Composable
internal fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(MusePaddings.cardInnerTight),
            )
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * v5: 重要度分布饼图。
 */
@Composable
internal fun ImportancePieChart(distribution: Map<Int, Int>) {
    val total = distribution.values.sum().coerceAtLeast(1)
    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val importantColor = MaterialTheme.colorScheme.tertiary
    val criticalColor = MaterialTheme.colorScheme.error
    val segments = listOf(
        0 to normalColor,
        1 to importantColor,
        2 to criticalColor,
    ).map { (key, color) ->
        val count = distribution[key] ?: 0
        Triple(key, color, count.toFloat() / total)
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            val typeChartCd = stringResource(R.string.memory_chart_type_distribution_cd)
            Canvas(modifier = Modifier.size(80.dp).semantics { contentDescription = typeChartCd }) {
            var startAngle = -90f
            segments.forEach { (key, color, ratio) ->
                if (ratio > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = 360f * ratio,
                        useCenter = true,
                    )
                    startAngle += 360f * ratio
                }
            }
            drawCircle(color = surfaceColor, radius = size.minDimension * 0.3f)
        }
        Column {
            segments.forEach { (key, color, ratio) ->
                val label = when (key) {
                    2 -> stringResource(R.string.memory_importance_critical)
                    1 -> stringResource(R.string.memory_importance_important)
                    else -> stringResource(R.string.memory_importance_normal)
                }
                val percent = (ratio * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(8.dp).drawBehind { drawCircle(color = color) })
                    Text(
                        text = "$label $percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v5: 记忆增长趋势折线图。
 */
@Composable
internal fun TrendLineChart(dailyData: List<Pair<String, Int>>) {
    val maxVal = dailyData.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val baselineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        val trendChartCd = stringResource(R.string.memory_chart_daily_trend_cd)
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = trendChartCd }) {
            val stepX = size.width / (dailyData.size - 1).coerceAtLeast(1)
            val points = dailyData.mapIndexed { index, (_, count) ->
                Offset(
                    x = index * stepX,
                    y = size.height - (count.toFloat() / maxVal) * size.height * 0.85f - size.height * 0.05f,
                )
            }
            if (points.size >= 2) {
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = lineColor,
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                points.forEach { point ->
                    drawCircle(color = lineColor, radius = 2.dp.toPx(), center = point)
                }
            }
            drawLine(
                color = baselineColor,
                start = Offset(0f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/**
 * dashboard 单行指标。
 */
@Composable
internal fun DashboardMetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 单个记忆步骤的健康行。
 */
@Composable
internal fun HealthStepRow(
    stepKey: String,
    health: io.zer0.memory.ticker.MemoryTicker.StepHealth,
) {
    Column(modifier = Modifier.padding(vertical = MusePaddings.labelVerticalGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stepKey,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (health.failCount > 0) stringResource(R.string.memory_screen_failed_times, health.failCount) else stringResource(R.string.memory_screen_normal),
                style = MaterialTheme.typography.labelSmall,
                color = if (health.failCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        val lastSuccessAt = health.lastSuccessAt
        if (lastSuccessAt != null) {
            Text(
                text = stringResource(R.string.memory_screen_last_success, lastSuccessAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val lastErrorMsg = health.lastErrorMsg
        if (lastErrorMsg != null) {
            Text(
                text = stringResource(R.string.memory_screen_error, lastErrorMsg),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * v0.51: "AI 对你的理解" 摘要卡片。
 */
@Composable
internal fun MemorySummaryCard(markdown: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasSummary = markdown.isNotBlank()

    CardGroup {
        item(
            onClick = { expanded = !expanded },
            headlineContent = {
                Text(
                    text = stringResource(R.string.memory_screen_ai_understanding),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        if (expanded || !hasSummary) {
            item(
                headlineContent = {
                    if (!hasSummary) {
                        Text(
                            text = stringResource(R.string.memory_screen_no_summary_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.memory_screen_click_collapse),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(8.dp))
                            MarkdownText(
                                text = markdown,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * v1.98: 经验库卡片。
 */
@Composable
internal fun ExperienceLibraryCard(
    items: List<MemoryItem>,
    onAdd: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CardGroup {
        item(
            onClick = { expanded = !expanded },
            headlineContent = {
                Column {
                    Text(
                        text = stringResource(R.string.memory_screen_experience_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = stringResource(R.string.memory_screen_experience_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_screen_experience_count, items.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        if (expanded) {
            if (items.isEmpty()) {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.memory_screen_experience_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                )
            } else {
                items.forEach { item ->
                    item(
                        key = item.id,
                        headlineContent = { MemoryRowHeadline(item) },
                        supportingContent = { MemoryRowSupporting(item) },
                        trailingContent = {
                            MemoryRowTrailing(
                                item = item,
                                onDelete = { onDelete(item) },
                                onEdit = { onEdit(item) },
                                onSetImportance = null,
                            )
                        },
                    )
                }
            }
            item(
                headlineContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onAdd,
                            shape = MuseShapes.large,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_experience_add_cd), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.memory_screen_experience_add_dialog_title))
                        }
                    }
                },
            )
        }
    }
}

/**
 * v1.98: 经验库新增/编辑对话框。
 */
@Composable
internal fun ExperienceEditDialog(
    title: String,
    initialTitle: String,
    initialContent: String,
    initialCategory: String,
    initialTags: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String, category: String, tags: List<String>) -> Unit,
) {
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle) }
    var contentText by remember(initialContent) { mutableStateOf(initialContent) }
    var categoryText by remember(initialCategory) { mutableStateOf(initialCategory) }
    var tagsText by remember(initialTags) { mutableStateOf(initialTags) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MuseTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_title_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_title_hint)) },
                    singleLine = true,
                )
                MuseTextField(
                    value = contentText,
                    onValueChange = { contentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    label = { Text(stringResource(R.string.memory_screen_experience_content_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_content_hint)) },
                )
                MuseTextField(
                    value = categoryText,
                    onValueChange = { categoryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_category_label)) },
                    singleLine = true,
                )
                MuseTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_tags_label)) },
                    singleLine = true,
                )
            }
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (titleText.isNotBlank() && contentText.isNotBlank()) {
                val tags = tagsText.split(",", "，", ";", "；")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onConfirm(titleText, contentText, categoryText, tags)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

// ── Phase 2 2D: Export helpers ──────────────────────────────────────────

internal fun buildMemoryMarkdown(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    val sb = StringBuilder()
    sb.appendLine("# Muse Memory Export")
    sb.appendLine()
    sb.appendLine("Exported at: ${java.time.Instant.now()}")
    sb.appendLine()
    sb.appendLine("## Facts (${facts.size})")
    sb.appendLine()
    facts.forEach { f ->
        val stars = "⭐".repeat(f.importance)
        sb.appendLine("- $stars ${f.content}")
        if (f.tags.isNotEmpty()) sb.appendLine("  Tags: ${f.tags.joinToString(", ")}")
        f.createdAt?.let { sb.appendLine("  Created: $it") }
        sb.appendLine()
    }
    sb.appendLine("## Summaries (${summaries.size})")
    sb.appendLine()
    summaries.forEach { s ->
        sb.appendLine("### ${s.title}")
        sb.appendLine(s.content)
        sb.appendLine()
    }
    return sb.toString()
}

internal fun buildMemoryJson(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    // 审计修复 (8.9): 完整 JSON 转义 — 原实现只处理 " 和 \n,
    // 反斜杠/\r/\t/控制字符不转义,内容含 C:\Users 之类即产出非法 JSON,
    // tags 也不转义直接拼接。改用 kotlinx.serialization 编码保证合法。
    val factsJson = facts.map { f ->
        buildJsonObject {
            put("id", JsonPrimitive(f.id))
            put("content", JsonPrimitive(f.content))
            put("importance", JsonPrimitive(f.importance))
            put("tags", JsonArray(f.tags.map { JsonPrimitive(it) }))
            put("createdAt", JsonPrimitive(f.createdAt ?: ""))
        }
    }
    val summariesJson = summaries.map { s ->
        buildJsonObject {
            put("id", JsonPrimitive(s.id))
            put("title", JsonPrimitive(s.title))
            put("content", JsonPrimitive(s.content))
        }
    }
    return buildJsonObject {
        put("exportedAt", JsonPrimitive(java.time.Instant.now().toString()))
        put("facts", JsonArray(factsJson))
        put("summaries", JsonArray(summariesJson))
    }.toString() + "\n"
}

internal object MemoryExportHelpers {
    fun shareText(context: android.content.Context, text: String, fileName: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (fileName.endsWith(".json")) "application/json" else "text/markdown"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
        }
        ShareIntentHelper.startChooserSafely(context, intent, "Share Memory Export")
    }
}
