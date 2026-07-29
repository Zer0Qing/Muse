package io.zer0.muse.ui.stats

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.MuseScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.IosChip
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.CalendarStats
import compose.icons.tablericons.CalendarTime
import compose.icons.tablericons.ChartLine
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.Flame
import compose.icons.tablericons.MessageCircle
import compose.icons.tablericons.Messages
import compose.icons.tablericons.MessagesOff
import compose.icons.tablericons.Share
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * v2.2: 统计页 — 仪表盘风格重构。
 *
 * 重构要点:
 *  - 仪表盘头部:大数字(总消息数 36sp Bold)+ 副标题 + 导出按钮
 *  - 关键指标网格:2 行 × 3 列展示 6 项指标(总对话/总消息/平均每日/最活跃/本月/连续天数)
 *  - 时间范围筛选:仅影响关键指标网格(明确语义,其他卡片保持全量)
 *  - 热力图:单 Canvas + drawRoundRect 绘制(替代 371 个 Box),支持点击 tooltip
 *  - 趋势图:7/30/90 天窗口切换,折线图 + 平均线(虚线) + 末端高亮
 *  - 小时分布:Canvas 24 根柱状图,峰值用 primary 色
 *  - 助手占比:Canvas 环形图(donut),灰阶色系
 *  - Top 模型:Top 5 + "查看全部"展开,进度条用灰阶
 *  - 空数据状态:图标 + 提示 + 行动按钮
 *  - 视觉统一:所有卡片 MuseShapes.extraLarge,文字 onSurface/onSurfaceVariant,
 *    图表灰阶(surfaceVariant/onSurfaceVariant/outline),峰值用 primary
 *  - 所有图标用 TablerIcons,图标 tint 用 onSurface/onSurfaceVariant
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    viewModel: StatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    MuseScaffold(
        topBar = {
            DashboardHeader(
                state = state,
                onBack = onBack,
                onExport = { shareStatsSummary(context, state) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.totalMessages == 0 && state.totalSessions == 0) {
            EmptyStatsState(
                onAction = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
            ) {
                // 1. 时间范围筛选(仅影响下方指标网格)
                item(key = "time_range_filter") {
                    TimeRangeFilterRow(
                        currentRange = state.timeRange,
                        onRangeChange = { viewModel.setTimeRange(it) },
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 2. 关键指标网格 2×3(响应 timeRange)
                item(key = "metrics_grid") {
                    KeyMetricsGrid(
                        state = state,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 3. 活跃热力图(单 Canvas,全量历史)
                item(key = "heatmap") {
                    HeatmapCard(
                        messagesPerDay = state.messagesPerDay,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 4. 活跃趋势(7/30/90 天窗口,折线 + 平均线)
                item(key = "trend") {
                    TrendCard(
                        messagesPerDay = state.messagesPerDay,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 5. 小时活跃分布(24 根柱状图)
                item(key = "hourly") {
                    HourlyDistributionCard(
                        hourlyDistribution = state.hourlyDistribution,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 6. 助手使用占比(环形图)
                item(key = "assistant_donut") {
                    AssistantDonutCard(
                        assistantCounts = state.assistantCounts,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 7. Top 模型(Top 5 + 展开)
                item(key = "top_models") {
                    TopModelsCard(
                        modelCounts = state.modelCounts,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }
            }
        }
    }
}

// ── 1. 仪表盘头部 ──────────────────────────────────────────────────────

/**
 * 仪表盘头部:返回按钮 + 导出按钮 + 大数字(总消息数) + 副标题。
 *
 * - 大数字:36sp Bold,onSurface 色
 * - 副标题:"共 X 个对话 · Y 天活跃"
 * - 右上角:TablerIcons.Share 导出按钮,点击调起系统分享
 */
@Composable
private fun DashboardHeader(
    state: StatsViewModel.StatsUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tinyGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = TablerIcons.ArrowLeft,
                    contentDescription = stringResource(R.string.stats_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = TablerIcons.Share,
                    contentDescription = stringResource(R.string.stats_export_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // 大数字:总消息数
        Text(
            text = formatCount(state.totalMessages),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        // 副标题:共 X 个对话 · Y 天活跃
        val activeDays = state.messagesPerDay.keys.size
        Text(
            text = stringResource(R.string.stats_dashboard_subtitle, state.totalSessions, activeDays),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ── 2. 空数据状态 ──────────────────────────────────────────────────────

/**
 * 空数据状态:ChartBar 图标 + 提示文本 + "去发起对话"按钮。
 */
@Composable
private fun EmptyStatsState(
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(MusePaddings.largeGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = TablerIcons.MessagesOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            onClick = onAction,
        ) {
            Text(
                text = stringResource(R.string.stats_empty_action),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

// ── 3. 关键指标网格 ────────────────────────────────────────────────────

/**
 * 关键指标网格:2 行 × 3 列,6 个指标(总对话/总消息/平均每日/最活跃/本月/连续天数)。
 *
 * 每个指标:大数字 + 小标签 + 图标(灰阶 tint)。
 */
@Composable
private fun KeyMetricsGrid(
    state: StatsViewModel.StatsUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = TablerIcons.MessageCircle,
                    value = formatCount(state.totalSessions),
                    label = stringResource(R.string.stats_total_sessions),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = TablerIcons.Messages,
                    value = formatCount(state.totalMessages),
                    label = stringResource(R.string.stats_total_messages),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = TablerIcons.ChartLine,
                    value = formatAvg(state.avgMessagesPerDay),
                    label = stringResource(R.string.stats_avg_daily),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = TablerIcons.CalendarStats,
                    value = state.mostActiveDay?.second?.let { formatCount(it) } ?: "—",
                    label = stringResource(R.string.stats_most_active_day),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = TablerIcons.CalendarTime,
                    value = formatCount(state.messagesThisMonth),
                    label = stringResource(R.string.stats_this_month),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = TablerIcons.Flame,
                    value = state.streakDays.toString(),
                    label = stringResource(R.string.stats_streak),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = TablerIcons.MessageCircle,
                    value = formatTokenCount(state.totalCharsReceived),
                    label = "累计 Token(~)",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 单个指标单元:图标(灰阶)+ 大数字 + 小标签。 */
@Composable
private fun MetricCell(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 4. 时间范围筛选行 ──────────────────────────────────────────────────

/**
 * 时间范围筛选行:横向滚动的 IosChip(全部 / 本月 / 本周 / 今天)。
 * 仅关键指标网格响应筛选;图表卡片保持全量。
 */
@Composable
private fun TimeRangeFilterRow(
    currentRange: StatsTimeRange,
    onRangeChange: (StatsTimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = listOf(
        StatsTimeRange.ALL_TIME to R.string.stats_range_all,
        StatsTimeRange.THIS_MONTH to R.string.stats_range_month,
        StatsTimeRange.THIS_WEEK to R.string.stats_range_week,
        StatsTimeRange.TODAY to R.string.stats_range_today,
    )
    val allCd = stringResource(R.string.stats_range_cd)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ranges.size) { index ->
            val (range, labelRes) = ranges[index]
            IosChip(
                selected = currentRange == range,
                onClick = { onRangeChange(range) },
                label = stringResource(labelRes),
                modifier = Modifier.semantics {
                    contentDescription = "$allCd: ${if (currentRange == range) "已选 " else ""}${ranges[index].first.name}"
                },
            )
        }
    }
}

// ── 5. 热力图卡片(单 Canvas) ─────────────────────────────────────────

/**
 * 热力图卡片:标题 + Canvas 网格 + 图例。
 *
 * - 单 Canvas + drawRoundRect 绘制 53 周 × 7 天(替代 371 个 Box)
 * - 配色:5 级灰阶(透明度 0/0.2/0.4/0.7/1.0)用 onSurface,不用 primary
 * - 点击单元格显示当天消息数 tooltip
 */
@Composable
private fun HeatmapCard(
    messagesPerDay: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
) {
    var selectedCell by remember { mutableStateOf<Pair<LocalDate, Int>?>(null) }

    Card(modifier = modifier.fillMaxWidth(), shape = MuseShapes.extraLarge) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_heatmap),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Tooltip 行:显示选中日期的消息数,或提示
            Text(
                text = selectedCell?.let { (date, count) ->
                    stringResource(
                        R.string.stats_tooltip_messages,
                        date.toString(),
                        count,
                    )
                } ?: stringResource(R.string.stats_tooltip_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CanvasHeatmap(
                messagesPerDay = messagesPerDay,
                onCellTap = { date, count -> selectedCell = date to count },
            )
            // 图例:少 ↔ 多
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                val onSurface = MaterialTheme.colorScheme.onSurface
                val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
                Canvas(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp),
                ) {
                    val cellSize = 10.dp.toPx()
                    val spacing = 2.dp.toPx()
                    val steps = listOf(0f, 0.2f, 0.4f, 0.7f, 1f)
                    steps.forEachIndexed { idx, alpha ->
                        val color = if (alpha == 0f) surfaceVariant else onSurface.copy(alpha = alpha)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x = idx * (cellSize + spacing), y = 0f),
                            size = Size(cellSize, cellSize),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Canvas 热力图:53 周 × 7 天,单节点绘制。
 *
 * - 左侧固定列:周几标签(Mon/Wed/Fri)
 * - 右侧滚动区:月份标签行 + Canvas 网格
 * - 点击命中:根据 offset 计算 weekIdx/dow,反推日期
 * - 配色:5 级灰阶(0/0.2/0.4/0.7/1.0),用 onSurface,不用 primary
 */
@Composable
private fun CanvasHeatmap(
    messagesPerDay: Map<LocalDate, Int>,
    onCellTap: (LocalDate, Int) -> Unit,
) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    // 四分位数(只在有消息的日子中算)
    val activeCounts = messagesPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }

    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val spacingPx = with(density) { cellSpacing.toPx() }
    val stepPx = cellPx + spacingPx
    val cornerPx = with(density) { 2.dp.toPx() }
    val gridWidthDp = (numWeeks * (cellSize.value + cellSpacing.value) - cellSpacing.value).dp
    val gridHeightDp = (7 * (cellSize.value + cellSpacing.value) - cellSpacing.value).dp

    // 周几标签:只显示 Mon/Wed/Fri 节省空间(Sun=0 索引)
    val dowLabels = listOf("", "Mon", "", "Wed", "", "Fri", "")

    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    // 初始滚到最右(最新一周)
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // 左侧固定列:顶部留白 + 7 个周几标签
        Column(
            modifier = Modifier.width(16.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = outline,
                        )
                    }
                }
            }
        }

        // 右侧滚动区:月份标签行 + Canvas 网格
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 月份标签行:遍历 53 周,每周取该周内第一个 dayOfMonth==1 的日期
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = outline,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Canvas 热力图主体:53 列(周)× 7 行(天),单节点
            Canvas(
                modifier = Modifier
                    .width(gridWidthDp)
                    .height(gridHeightDp)
                    .pointerInput(messagesPerDay) {
                        detectTapGestures { offset ->
                            val weekIdx = (offset.x / stepPx).toInt()
                            val dow = (offset.y / stepPx).toInt()
                            if (weekIdx in 0 until numWeeks && dow in 0..6) {
                                val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                                if (!date.isAfter(today)) {
                                    val count = messagesPerDay[date] ?: 0
                                    onCellTap(date, count)
                                }
                            }
                        }
                    },
            ) {
                for (weekIdx in 0 until numWeeks) {
                    for (dow in 0..6) {
                        val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                        val isFuture = date.isAfter(today)
                        val count = if (isFuture) 0 else (messagesPerDay[date] ?: 0)
                        val alpha = when {
                            isFuture -> -1f
                            count == 0 -> 0f
                            count <= q1 -> 0.2f
                            count <= q2 -> 0.4f
                            count <= q3 -> 0.7f
                            else -> 1f
                        }
                        val color = when {
                            alpha < 0f -> surfaceVariant.copy(alpha = 0.3f)
                            alpha == 0f -> surfaceVariant
                            else -> onSurface.copy(alpha = alpha)
                        }
                        val x = weekIdx * stepPx
                        val y = dow * stepPx
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                        )
                    }
                }
            }
        }
    }
}

// ── 6. 趋势卡片(7/30/90 天 + 平均线) ────────────────────────────────

/**
 * 趋势卡片:时间窗口切换(7/30/90 天)+ 折线图 + 平均线(虚线)+ 末端高亮。
 *
 * - 数据源:messagesPerDay(全量历史,本地按窗口截取)
 * - Canvas 绘制折线 + 末端圆点(primary)+ 平均线(outline 虚线)
 * - 横轴:7 天显示周几,30/90 天显示 M/d
 */
@Composable
private fun TrendCard(
    messagesPerDay: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
) {
    var windowDays by remember { mutableStateOf(7) }
    val today = LocalDate.now()

    // 按窗口截取趋势数据(oldest → today)
    val trendData = remember(messagesPerDay, windowDays) {
        (0 until windowDays).map { offset ->
            val date = today.minusDays((windowDays - 1 - offset).toLong())
            date to (messagesPerDay[date] ?: 0)
        }
    }
    val counts = trendData.map { it.second }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 0
    val avg = if (counts.isNotEmpty()) counts.average() else 0.0
    val hasData = counts.any { it > 0 }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val windowOptions = listOf(
        7 to R.string.stats_trend_window_7d,
        30 to R.string.stats_trend_window_30d,
        90 to R.string.stats_trend_window_90d,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_trend_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 末端值(今日消息数)
                if (hasData) {
                    Text(
                        text = formatCount(counts.lastOrNull() ?: 0),
                        style = MaterialTheme.typography.titleSmall,
                        color = onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 时间窗口切换
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(windowOptions.size) { idx ->
                    val (days, labelRes) = windowOptions[idx]
                    IosChip(
                        selected = windowDays == days,
                        onClick = { windowDays = days },
                        label = stringResource(labelRes),
                    )
                }
            }

            if (!hasData) {
                EmptyStatsHint()
            } else {
                // 折线图
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (counts.isEmpty() || maxCount == 0) return@Canvas
                        val stepX = if (counts.size > 1) w / (counts.size - 1) else w
                        val padding = 6.dp.toPx()
                        val usableH = h - padding * 2

                        // 平均线(虚线)
                        val avgRatio = (avg.toFloat() / maxCount).coerceIn(0f, 1f)
                        val avgY = padding + (1f - avgRatio) * usableH
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        drawLine(
                            color = outline,
                            start = Offset(0f, avgY),
                            end = Offset(w, avgY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashEffect,
                        )

                        // 折线路径
                        val path = Path()
                        counts.forEachIndexed { idx, count ->
                            val x = idx * stepX
                            val ratio = count.toFloat() / maxCount
                            val y = padding + (1f - ratio) * usableH
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = onSurface,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )

                        // 末端点(今日)实心圆高亮
                        val lastIdx = counts.lastIndex
                        val lastX = lastIdx * stepX
                        val lastRatio = counts[lastIdx].toFloat() / maxCount
                        val lastY = padding + (1f - lastRatio) * usableH
                        drawCircle(
                            color = primary,
                            radius = 4.dp.toPx(),
                            center = Offset(lastX, lastY),
                        )
                    }
                }
                // 平均值标签
                Text(
                    text = stringResource(R.string.stats_avg_label, avg),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                )
                // 横轴标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val labelPositions = if (windowDays == 7) {
                        (0 until windowDays).toList()
                    } else {
                        listOf(0, windowDays / 3, windowDays * 2 / 3, windowDays - 1)
                    }
                    labelPositions.forEach { pos ->
                        val (date, _) = trendData[pos]
                        val label = if (windowDays == 7) {
                            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINESE)
                        } else {
                            "${date.monthValue}/${date.dayOfMonth}"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pos == windowDays - 1) onSurface else outline,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// ── 7. 小时分布卡片(24 根柱状图) ────────────────────────────────────

/**
 * 小时活跃分布卡片:Canvas 24 根柱状图,峰值用 primary,其余用 onSurfaceVariant。
 */
@Composable
private fun HourlyDistributionCard(
    hourlyDistribution: List<Int>,
    modifier: Modifier = Modifier,
) {
    val hours = if (hourlyDistribution.size == 24) hourlyDistribution else IntArray(24) { idx ->
        hourlyDistribution.getOrElse(idx) { 0 }
    }.toList()
    val maxCount = hours.maxOrNull()?.takeIf { it > 0 } ?: 0
    val peakHour = hours.indexOfFirst { it == maxCount && maxCount > 0 }
    val hasData = maxCount > 0

    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_hourly_dist),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
            )

            if (!hasData) {
                EmptyStatsHint()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val barSpacing = 2.dp.toPx()
                        val totalSpacing = barSpacing * 23
                        val barWidth = (w - totalSpacing) / 24
                        val padding = 4.dp.toPx()
                        val usableH = h - padding

                        hours.forEachIndexed { idx, count ->
                            val ratio = if (maxCount > 0) count.toFloat() / maxCount else 0f
                            val barHeight = ratio * usableH
                            val x = idx * (barWidth + barSpacing)
                            val y = h - barHeight
                            val color = if (idx == peakHour) primary else onSurfaceVariant.copy(alpha = 0.6f)
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                            )
                        }
                    }
                }
                // 横轴:0, 6, 12, 18, 23
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(0, 6, 12, 18, 23).forEach { hour ->
                        Text(
                            text = "${hour}h",
                            style = MaterialTheme.typography.labelSmall,
                            color = outline,
                        )
                    }
                }
            }
        }
    }
}

// ── 8. 助手使用占比卡片(环形图) ──────────────────────────────────────

/**
 * 助手使用占比卡片:Canvas 环形图(donut),灰阶色系。
 *
 * - 环形分段:按数量降序,前 5 段用不同灰阶,其余归入"其他"
 * - 中心显示总消息数
 * - 下方图例:助手名 + 数量
 */
@Composable
private fun AssistantDonutCard(
    assistantCounts: List<StatsViewModel.AssistantUsage>,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // 灰阶色系(从深到浅)
    val grayScale = listOf(
        onSurface,
        onSurface.copy(alpha = 0.7f),
        onSurface.copy(alpha = 0.5f),
        onSurface.copy(alpha = 0.35f),
        onSurface.copy(alpha = 0.2f),
    )

    // 按数量降序,取前 5,其余归入"其他"
    val sortedCounts = assistantCounts.sortedByDescending { it.count }
    val totalCount = sortedCounts.sumOf { it.count }
    val displayed = sortedCounts.take(5)
    val otherCount = sortedCounts.drop(5).sumOf { it.count }
    val segments = displayed.mapIndexed { idx, usage ->
        Triple(usage.assistantName, usage.count, grayScale.getOrElse(idx) { grayScale.last() })
    }.toMutableList()
    if (otherCount > 0) {
        segments.add(Triple("其他", otherCount, grayScale.last().copy(alpha = 0.1f)))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_assistant_usage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
            )

            if (totalCount == 0) {
                EmptyStatsHint()
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 环形图
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val cx = w / 2
                            val cy = h / 2
                            val radius = minOf(w, h) / 2 - 4.dp.toPx()
                            val strokeWidth = 16.dp.toPx()
                            val topLeft = Offset(cx - radius, cy - radius)
                            val arcSize = Size(radius * 2, radius * 2)

                            // 背景环(surfaceVariant)
                            drawArc(
                                color = surfaceVariant,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth),
                            )

                            // 分段
                            var startAngle = -90f // 从顶部开始
                            segments.forEach { (_, count, color) ->
                                val sweep = if (totalCount > 0) 360f * count / totalCount else 0f
                                if (sweep > 0f) {
                                    drawArc(
                                        color = color,
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                                    )
                                    startAngle += sweep
                                }
                            }
                        }
                        // 中心:总消息数
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = formatCount(totalCount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onSurface,
                            )
                            Text(
                                text = stringResource(R.string.stats_total_messages),
                                style = MaterialTheme.typography.labelSmall,
                                color = onSurfaceVariant,
                            )
                        }
                    }

                    // 图例
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        segments.forEach { (name, count, color) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(color),
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatCount(count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 9. Top 模型卡片(Top 5 + 展开) ───────────────────────────────────

/**
 * Top 模型卡片:默认显示 Top 5,"查看全部"展开后显示全部。
 *
 * - 每行:排名 + 模型名 + 数量 + 百分比 + 进度条(灰阶)
 * - 进度条用 onSurface(深)填充 + surfaceVariant 轨道,Top 1 用 primary 高亮
 */
@Composable
private fun TopModelsCard(
    modelCounts: List<StatsViewModel.ModelUsage>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_top_functions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface,
                )
                if (modelCounts.size > 5) {
                    Row(
                        modifier = Modifier.clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(if (expanded) R.string.stats_collapse else R.string.stats_view_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                            contentDescription = null,
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            val displayed = if (expanded) modelCounts else modelCounts.take(5)
            if (displayed.isEmpty()) {
                EmptyStatsHint()
            } else {
                val maxCount = (displayed.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                displayed.forEachIndexed { idx, usage ->
                    val ratio = usage.count.toFloat() / maxCount
                    val isTop = idx == 0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 排名 + 模型名
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isTop) onSurface else outline,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = usage.modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 数量 · 百分比
                            Text(
                                text = "${formatCount(usage.count)} · ${(usage.percentage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = outline,
                            )
                        }
                        // 进度条:轨道 + 填充(灰阶,Top 1 用 primary)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(if (isTop) primary else onSurface.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 辅助函数 ──────────────────────────────────────────────────────────

/** 空数据提示文本。 */
@Composable
private fun EmptyStatsHint() {
    Text(
        text = stringResource(R.string.stats_no_data),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 大数格式化:>= 1M 用 "M" 后缀,>= 1K 用 "K" 后缀(保留 1 位小数,整数则省略小数)。
 */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> formatWithSuffix(count / 1_000_000.0, "M")
    count >= 1_000 -> formatWithSuffix(count / 1_000.0, "K")
    else -> count.toString()
}

/** 平均值格式化:大数走 K/M 后缀,小数保留 1 位。 */
private fun formatAvg(avg: Double): String = when {
    avg >= 1_000_000 -> formatWithSuffix(avg / 1_000_000.0, "M")
    avg >= 1_000 -> formatWithSuffix(avg / 1_000.0, "K")
    else -> String.format(Locale.US, "%.1f", avg)
}

private fun formatTokenCount(chars: Long): String {
    val estimatedTokens = chars / 2  // 粗略估算:中文约 2 字符/token
    return formatCount(estimatedTokens.toInt())
}

/** 带后缀格式化:四舍五入到 1 位小数,整数部分省略小数点。 */
private fun formatWithSuffix(value: Double, suffix: String): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}$suffix"
    } else {
        String.format(Locale.US, "%.1f", rounded) + suffix
    }
}

/**
 * 构建统计摘要文本(用于导出分享)。
 */
private fun buildStatsSummaryText(state: StatsViewModel.StatsUiState): String = buildString {
    appendLine("===== Muse 统计摘要 =====")
    appendLine()
    appendLine("总对话数: ${state.totalSessions}")
    appendLine("总消息数: ${state.totalMessages}")
    appendLine("AI 回复数: ${state.totalAiMessages}")
    appendLine("用户消息数: ${state.totalUserMessages}")
    appendLine("连续活跃: ${state.streakDays} 天")
    appendLine("本周消息: ${state.messagesThisWeek}")
    appendLine("本月消息: ${state.messagesThisMonth}")
    appendLine("平均每日: ${String.format(Locale.US, "%.1f", state.avgMessagesPerDay)}")
    state.mostActiveDay?.let { (date, count) ->
        appendLine("最活跃一天: $date ($count 条)")
    }
    appendLine()
    if (state.modelCounts.isNotEmpty()) {
        appendLine("最常使用的模型:")
        state.modelCounts.take(10).forEachIndexed { i, m ->
            appendLine("  ${i + 1}. ${m.modelName} - ${m.count} 条 (${(m.percentage * 100).toInt()}%)")
        }
    }
    appendLine()
    if (state.assistantCounts.isNotEmpty()) {
        appendLine("助手使用占比:")
        state.assistantCounts.forEach { a ->
            appendLine("  - ${a.assistantName}: ${a.count} 条")
        }
    }
}

/**
 * 调起系统分享,导出统计摘要文本。
 */
private fun shareStatsSummary(context: Context, state: StatsViewModel.StatsUiState) {
    val summary = buildStatsSummaryText(state)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.stats_export_subject))
        putExtra(Intent.EXTRA_TEXT, summary)
    }
    val chooser = Intent.createChooser(sendIntent, context.getString(R.string.stats_export_chooser))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
