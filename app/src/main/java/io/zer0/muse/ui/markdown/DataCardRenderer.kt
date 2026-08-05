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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    text = card.title.ifBlank { "数据卡片" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onDownload != null) {
                    IconButton(onClick = { onDownload(card) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "下载图表",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 图表区
            when (card.type) {
                "bar" -> BarChart(card, accent)
                "line" -> LineChart(card, accent)
                "donut" -> DonutChart(card, accent)
            }

            // 图例(标签 + 值)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                card.labels.zip(card.values).take(6).forEach { (label, value) ->
                    Text(
                        text = "$label $value",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (card.labels.size > 6) {
                    Text(
                        text = "+${card.labels.size - 6}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.statusColors.neutral,
                    )
                }
            }
        }
    }
}

/** 柱状图。 */
@Composable
private fun BarChart(card: DataCard, accent: Color) {
    val maxValue = card.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val gap = 6.dp.toPx()
        val barWidth = (w - gap * (card.values.size - 1)) / card.values.size
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
            val palette = listOf(
                accent,
                accent.copy(alpha = 0.75f),
                accent.copy(alpha = 0.5f),
                accent.copy(alpha = 0.3f),
                infoColor,
            )
            card.values.forEachIndexed { index, value ->
                val sweep = (value / total) * 360f
                drawArc(
                    color = palette[index % palette.size],
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
