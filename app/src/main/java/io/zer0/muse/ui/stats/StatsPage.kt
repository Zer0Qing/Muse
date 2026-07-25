package io.zer0.muse.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.quota.QuotaManager
import io.zer0.muse.data.quota.QuotaState
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * v0.46: 统计页 — 以活跃热力图为主体,辅以精简的概览/Top/趋势小图。
 *
 * v2.1 重构: 砍掉冗余的图表/统计卡片(原 9 卡片网格 + 5 图表),
 * 改为"热力图为主"的单页结构:
 *  - TopAppBar(标题"统计" + 返回按钮)
 *  - LazyColumn:
 *    1. 活跃热力图卡片(53 周 × 7 天,主体最显眼位置)
 *    2. 时间范围筛选行(仅影响下方概览/趋势卡片)
 *    3. 本周活跃概览(简洁的 4 个数字 + 图标)
 *    4. 最常使用的功能 Top 3(模型使用排行)
 *    5. 每周趋势小图(sparkline,7 日折线)
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    viewModel: StatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            // iOS 风格 Large Title 顶部栏
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.chipInnerTight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.stats_back),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // L-ST2 修复: 各 item 加 key,避免重组时 LazyColumn 误判项目移动而重建组件树

                // 1. 热力图主体(顶部最显眼位置,展示全年活跃度全景)
                item(key = "heatmap") {
                    HeatmapCard(
                        messagesPerDay = state.messagesPerDay,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 2. 时间范围筛选行(影响下方概览/趋势卡片)
                item(key = "time_range_filter") {
                    TimeRangeFilterRow(
                        currentRange = state.timeRange,
                        onRangeChange = { viewModel.setTimeRange(it) },
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 3. 本周活跃概览(简洁的 4 数字 + 图标)
                item(key = "weekly_overview") {
                    WeeklyActiveOverviewCard(
                        state = state,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 4. 最常使用的功能 Top 3(模型排行)
                item(key = "top_functions") {
                    TopFunctionsCard(
                        modelCounts = state.modelCounts,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // 5. 每周趋势小图(sparkline,7 日折线)
                item(key = "trend_sparkline") {
                    TrendSparklineCard(
                        weeklyTrend = state.weeklyTrend,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }
            }
        }
    }
}

/**
 * v1.104 U8: 时间范围筛选行 — 横向滚动的 FilterChip(全部 / 本月 / 本周 / 今天)。
 * 仅概览卡片响应筛选,图表卡片保持全量以保证趋势完整性。
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

/**
 * 本周活跃概览卡片: 4 个核心指标横向排列(图标 + 数值 + 标签)。
 *
 * v2.1 重构: 替代原 9 张统计卡片网格,只保留最关键的 4 个指标:
 *  - 总消息数(全部消息,响应 timeRange 筛选)
 *  - AI 回复数(ASSISTANT 角色,响应 timeRange)
 *  - 连续活跃天数(从今天往回数,历史指标不响应筛选)
 *  - 本周消息数(本周一 00:00 起,固定窗口)
 */
@Composable
private fun WeeklyActiveOverviewCard(
    state: StatsViewModel.StatsUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.screen),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewMetric(
                icon = Icons.AutoMirrored.Filled.Chat,
                value = formatCount(state.totalMessages),
                label = stringResource(R.string.stats_total_messages),
                modifier = Modifier.weight(1f),
            )
            OverviewMetric(
                icon = Icons.Filled.SmartToy,
                value = formatCount(state.totalAiMessages),
                label = stringResource(R.string.stats_ai_replies),
                modifier = Modifier.weight(1f),
            )
            OverviewMetric(
                icon = Icons.Filled.LocalFireDepartment,
                value = stringResource(R.string.stats_streak_days, state.streakDays),
                label = stringResource(R.string.stats_streak),
                modifier = Modifier.weight(1f),
            )
            OverviewMetric(
                icon = Icons.Filled.ViewWeek,
                value = formatCount(state.messagesThisWeek),
                label = stringResource(R.string.stats_this_week),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 单个概览指标: 图标 + 数值 + 标签(纵向排列,等宽权重)。 */
@Composable
private fun OverviewMetric(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 最常使用的功能 Top 3 卡片: 取模型使用排行前 3,带条形进度条。
 *
 * - 每行: 排名 + 模型名 + 数量 + 百分比 + 进度条
 * - 进度条按 count / maxCount 等比缩放,Top 1 高亮 primary,其余 primary 45% 透明
 * - 空数据显示"暂无数据"
 */
@Composable
private fun TopFunctionsCard(
    modelCounts: List<StatsViewModel.ModelUsage>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_top_functions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            val top3 = modelCounts.take(3)
            if (top3.isEmpty()) {
                EmptyStatsHint()
            } else {
                val maxCount = (top3.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                top3.forEachIndexed { idx, usage ->
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
                                    color = if (isTop) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = usage.modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 数量 · 百分比
                            Text(
                                text = "${formatCount(usage.count)} · ${(usage.percentage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        // 条形轨道 + 填充
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(
                                        if (isTop) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 每周趋势小图(sparkline): 最近 7 天每日消息数的折线图。
 *
 * - Canvas + Path 绘制单条折线,7 个数据点之间用直线连接
 * - Y 轴按 7 日最大值等比缩放,最小值预留底部空白避免贴底
 * - 末端点(今日)用 primary 实心圆高亮
 * - 全 0 数据显示"暂无数据"
 */
@Composable
private fun TrendSparklineCard(
    weeklyTrend: List<Pair<LocalDate, Int>>,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val counts = weeklyTrend.map { it.second }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 1
    val hasData = counts.any { it > 0 }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_weekly_trend),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // 末尾值高亮(今日消息数)
                if (hasData) {
                    Text(
                        text = formatCount(counts.lastOrNull() ?: 0),
                        style = MaterialTheme.typography.titleSmall,
                        color = primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (!hasData) {
                EmptyStatsHint()
            } else {
                // sparkline 折线区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (counts.isEmpty()) return@Canvas
                        val stepX = if (counts.size > 1) w / (counts.size - 1) else w
                        val padding = 4.dp.toPx()
                        val usableH = h - padding * 2

                        // 折线路径
                        val path = androidx.compose.ui.graphics.Path()
                        counts.forEachIndexed { idx, count ->
                            val x = idx * stepX
                            val ratio = count.toFloat() / maxCount
                            val y = padding + (1f - ratio) * usableH
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = primary,
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
                            center = androidx.compose.ui.geometry.Offset(lastX, lastY),
                        )
                    }
                }
                // 横轴: 首日 ~ 今日(中文窄格式周几)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    weeklyTrend.forEach { (date, _) ->
                        val isToday = date == today
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINESE),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) primary else outline,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 热力图卡片: 标题 + 53 周 × 7 天网格 + 图例(少 ↔ 多)。
 */
@Composable
private fun HeatmapCard(
    messagesPerDay: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), shape = MuseShapes.extraLarge) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_heatmap),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ChatHeatmap(messagesPerDay = messagesPerDay)

            // 图例: 少 ↔ 多
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 聊天热力图: 53 周 × 7 天网格。
 *
 * - 起始日: today.with(previousOrSame(SUNDAY)).minusWeeks(52)
 * - 4 级 alpha: 0 / 0.25 / 0.5 / 0.75 / 1.0(基于四分位数 q1/q2/q3)
 * - 横向滚动,初始滚到最右(用 rememberScrollState(initial = Int.MAX_VALUE),无闪烁)
 * - 左侧周几标签(Mon/Wed/Fri)+ 顶部月份标签行
 *
 * M-ST1 说明: 当前用嵌套 Row/Column + Box 渲染 53×7=371 个单元格,每个单元格是轻量 Box,
 * 仅做 clip + background,无子组件测量开销。371 个 Box 在现代设备上性能可接受(单次测量 pass)。
 * 若后续需进一步优化(如缩减滚动惯性帧),可改用 Canvas + drawRect 绘制整张热力图,
 * 将 371 个 Box 降为 1 个 Canvas 节点,但需自行处理触摸命中检测。
 */
@Composable
private fun ChatHeatmap(messagesPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    // 四分位数(只在有消息的日子中算,避免 0 拉低阈值)
    val activeCounts = messagesPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }

    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    // 周几标签: 只显示 Mon/Wed/Fri 节省空间(Sun=0 索引)
    val dowLabels = listOf("", "Mon", "", "Wed", "", "Fri", "")

    // 初始滚到最右(最新一周),用 Int.MAX_VALUE 避免 LaunchedEffect 闪烁
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // 左侧固定列: 顶部留白(对齐月份标签行高度)+ 7 个周几标签
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
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // 右侧滚动区: 月份标签行 + 热力图网格(共享同一 scrollState)
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 月份标签行: 遍历 53 周,每周取该周内第一个 dayOfMonth==1 的日期作为月份标签
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
                                color = MaterialTheme.colorScheme.outline,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 热力图主体: 53 列(周)× 7 行(天)
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (messagesPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

/**
 * 热力图单元格: Box + clip(extraSmall) + background。
 *
 * - alpha < 0: 未来日期,surfaceVariant 30% 透明
 * - alpha == 0: 当天无消息,surfaceVariant
 * - alpha > 0: 有消息,primary 按 alpha 叠加(越深越活跃)
 */
@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

/**
 * 大数格式化:>= 1M 用 "M" 后缀,>= 1K 用 "K" 后缀(保留 1 位小数,整数则省略小数)。
 * - 1234 → "1.2K"
 * - 1000000 → "1M"
 * - 999 → "999"
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

/** 带后缀格式化:四舍五入到 1 位小数,整数部分省略小数点(避免 "1.0K")。 */
private fun formatWithSuffix(value: Double, suffix: String): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}$suffix"
    } else {
        String.format(Locale.US, "%.1f", rounded) + suffix
    }
}

// ── v0.47: 4 个新统计卡片 ──────────────────────────────────────────────

/** 空数据提示文本。 */
@Composable
private fun EmptyStatsHint() {
    Text(
        text = stringResource(R.string.stats_no_data),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}
