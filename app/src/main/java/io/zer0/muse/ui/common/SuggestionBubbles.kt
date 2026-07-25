package io.zer0.muse.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * AI 回复后的建议气泡 — Kelivo 风格。
 *
 * 在 AI 回复消息下方展示最多 3 个建议回复,用户点击即可快速发送。
 * 背景使用 `primaryContainer@42% alpha` (light) / `white@8% alpha` (dark),
 * 圆角 16dp, 内边距 12×8dp。
 *
 * 用法:
 * ```
 * SuggestionBubbles(
 *     suggestions = listOf("继续", "详细解释", "换个方式"),
 *     onSuggestionClick = { text -> onSend(text) },
 * )
 * ```
 *
 * @param suggestions 建议文本列表 (最多取前 3 个)
 * @param onSuggestionClick 点击建议回调
 * @param modifier 修饰符
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionBubbles(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return

    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val bubbleColor = if (isLight) {
        colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val textColor = colorScheme.onSurface.copy(alpha = 0.85f)
    val shape = RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp) // 16dp

    FlowRow(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 3,
    ) {
        suggestions.take(3).forEach { suggestion ->
            Surface(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onSuggestionClick(suggestion) },
                ),
                shape = shape,
                color = bubbleColor,
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
