package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.delay

/**
 * v1.0.53: 输入栏底部 Token 统计条。
 *
 * 实时显示当前输入 / 历史消息 / 上下文窗口占用,并带一条细进度条。
 * 输入 token 数采用 400ms 防抖,避免每次按键都执行 BPE 编码。
 *
 * @param inputText 当前输入框文本
 * @param historyTokens 当前会话历史消息(含 system prompt)的 token 估算
 * @param contextWindow 当前模型上下文窗口;<=0 时隐藏进度条与窗口信息
 */
@Composable
fun TokenStatsBar(
    inputText: String,
    historyTokens: Int,
    contextWindow: Int,
    modifier: Modifier = Modifier,
) {
    var inputTokens by remember { mutableIntStateOf(0) }
    LaunchedEffect(inputText) {
        delay(400)
        inputTokens = TokenEstimator.estimate(inputText)
    }

    val used = (inputTokens + historyTokens).coerceAtLeast(0)
    val ratio = if (contextWindow > 0) {
        (used.toFloat() / contextWindow).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(stringResource(R.string.chat_token_input))
                    append(" ")
                    append(inputTokens)
                    append(" · ")
                    append(stringResource(R.string.chat_token_history))
                    append(" ")
                    append(historyTokens)
                    if (contextWindow > 0) {
                        append(" · ")
                        append(stringResource(R.string.chat_token_context_window))
                        append(" ")
                        append("%,d".format(contextWindow))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (contextWindow > 0) {
                Text(
                    text = stringResource(R.string.chat_token_usage) + " ${(ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        ratio >= 0.9f -> MaterialTheme.colorScheme.error
                        ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (contextWindow > 0) {
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
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
