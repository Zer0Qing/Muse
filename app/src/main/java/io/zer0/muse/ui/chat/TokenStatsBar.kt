package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.TokenEstimator

/**
 * P1 UI: 助手消息快捷按钮下方的紧凑 Token 统计条。
 *
 * 单行布局:当前消息 token + 上下文占用百分比 + 细进度条。
 * 不再使用纵向 Column,避免在窄屏把分支切换器顶出屏幕。
 */
@Composable
fun TokenStatsBar(
    messageText: String,
    historyTokens: Int,
    contextWindow: Int,
    modifier: Modifier = Modifier,
) {
    val messageTokens = TokenEstimator.estimate(messageText)
    val used = (messageTokens + historyTokens).coerceAtLeast(0)
    val ratio = if (contextWindow > 0) {
        (used.toFloat() / contextWindow).coerceIn(0f, 1f)
    } else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append(stringResource(R.string.chat_token_message))
                append(" ")
                append(messageTokens)
                if (contextWindow > 0) {
                    append(" · ")
                    append(stringResource(R.string.chat_token_usage))
                    append(" ")
                    append((ratio * 100).toInt())
                    append("%")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (contextWindow > 0) {
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .width(56.dp)
                    .height(2.dp)
                    .clip(MuseShapes.medium),
                color = when {
                    ratio >= 0.9f -> MaterialTheme.colorScheme.error
                    ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
