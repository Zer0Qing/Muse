package io.zer0.muse.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.statusColors

/**
 * v1.0.53: 数据卡片渲染(既有实现 show_card)。
 *
 * 三种图表:bar(柱状)/ line(折线)/ donut(环形)。
 * 卡片右上角带下载 PNG 按钮(经 [onDownload] 回调,由调用方实现 Canvas→Bitmap→相册)。
 */
@Composable
fun DataCardRenderer(
    card: DataCard,
    onDownload: ((DataCard) -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            // 标题行 + 下载按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.title.ifBlank { stringResource(R.string.data_card_default_title) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onDownload != null) {
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = { onDownload(card) },
                        contentDescription = stringResource(R.string.data_card_download),
                        tint = colorScheme.onSurfaceVariant,
                        size = 48.dp,
                        iconSize = 16.dp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 图表区
            when (card.type) {
                "bar" -> BarChart(card, accent)
                "line" -> LineChart(card, accent)
                "donut" -> DonutChart(card, accent)
            }

            // 图例(颜色点 + 标签 + 值)。
            // donut 图例与扇区共用同一调色板,同序取色,保证「第 i 个扇区 = 第 i 条图例」。
            val infoColor = MaterialTheme.statusColors.info
            val donutPalette = remember(accent, infoColor) { donutSectorPalette(accent, infoColor) }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                card.labels.zip(card.values).take(MAX_LEGEND_ITEMS).forEachIndexed { index, (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(legendSwatchColor(card.type, index, accent, donutPalette)),
                        )
                        Text(
                            text = "$label $value",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (card.labels.size > MAX_LEGEND_ITEMS) {
                    Text(
                        text = "+${card.labels.size - MAX_LEGEND_ITEMS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.statusColors.neutral,
                    )
                }
            }
        }
    }
}

/** 图例最多展示的条目数(超出显示 +N)。 */
private const val MAX_LEGEND_ITEMS = 6

/**
 * 单根柱宽计算。
 *
 * 空数据(barCount<=0)返回 0,调用方不绘制,避免 `x / 0` 抛 ArithmeticException;
 * 画布过窄导致可用宽度为负时收缩到 0,避免负宽度传给 Canvas。
 */
internal fun barWidthFor(canvasWidth: Float, barCount: Int, gap: Float): Float {
    if (barCount <= 0) return 0f
    val available = canvasWidth - gap * (barCount - 1)
    return (available / barCount).coerceAtLeast(0f)
}

/** 环形图扇区调色板(图例与扇区共用,索引同序)。 */
internal fun donutSectorPalette(accent: Color, infoColor: Color): List<Color> = listOf(
    accent,
    accent.copy(alpha = 0.75f),
    accent.copy(alpha = 0.5f),
    accent.copy(alpha = 0.3f),
    infoColor,
)

/** 第 [index] 个扇区/图例的颜色(超出调色板长度时循环取色)。 */
internal fun sectorColor(palette: List<Color>, index: Int): Color =
    if (palette.isEmpty()) Color.Unspecified else palette[index % palette.size]

/** 图例色点:donut 与扇区同序同色,其余图表用主题强调色。 */
internal fun legendSwatchColor(
    cardType: String,
    index: Int,
    accent: Color,
    donutPalette: List<Color>,
): Color = if (cardType == "donut") sectorColor(donutPalette, index) else accent

/** 柱状图。 */
@Composable
private fun BarChart(card: DataCard, accent: Color) {
    val maxValue = card.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val barCount = card.values.size
        // 空数据直接不绘制:旧实现 (w - gap * (n - 1)) / n 在 n == 0 时抛 ArithmeticException
        if (barCount == 0) return@Canvas
        val w = size.width
        val h = size.height
        val gap = 6.dp.toPx()
        val barWidth = barWidthFor(w, barCount, gap)
        card.values.forEachIndexed { index, value ->
            val barHeight = (value / maxValue) * h
            drawRoundRect(
                color = if (index == card.values.lastIndex) accent else accent.copy(alpha = 0.55f),
                topLeft = Offset(x = index * (barWidth + gap), y = h - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

/** 折线图。 */
@Composable
private fun LineChart(card: DataCard, accent: Color) {
    val maxValue = card.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val minValue = card.values.minOrNull()?.coerceAtMost(0f) ?: 0f
    val range = (maxValue - minValue).coerceAtLeast(1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val padding = 8.dp.toPx()
        val stepX = if (card.values.size > 1) (w - padding * 2) / (card.values.size - 1) else 0f
        val points = card.values.mapIndexed { index, value ->
            Offset(
                x = padding + index * stepX,
                y = h - padding - ((value - minValue) / range) * (h - padding * 2),
            )
        }
        // 连线
        for (i in 1 until points.size) {
            drawLine(
                color = accent.copy(alpha = 0.6f),
                start = points[i - 1],
                end = points[i],
                strokeWidth = 2.dp.toPx(),
            )
        }
        // 数据点
        points.forEachIndexed { index, point ->
            drawCircle(
                color = if (index == points.lastIndex) accent else accent.copy(alpha = 0.8f),
                radius = 3.dp.toPx(),
                center = point,
            )
        }
    }
}

/** 环形图。 */
@Composable
private fun DonutChart(card: DataCard, accent: Color) {
    val total = card.values.sum().coerceAtLeast(1f)
    // 主题色在 Composable 上下文读取,Canvas 绘制 lambda 内不可调用
    val infoColor = MaterialTheme.statusColors.info
    val palette = remember(accent, infoColor) { donutSectorPalette(accent, infoColor) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .padding(4.dp),
        ) {
            val strokeWidth = 16.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)
            var startAngle = -90f
            card.values.forEachIndexed { index, value ->
                val sweep = (value / total) * 360f
                drawArc(
                    color = sectorColor(palette, index),
                    startAngle = startAngle,
                    sweepAngle = sweep - 1f, // 小间隙
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                startAngle += sweep
            }
        }
    }
}
