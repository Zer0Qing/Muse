package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.TokenEstimator

/**
 * P1 UI: 助手消息快捷按钮下方的紧凑 Token 统计条。
 *
 * v2.0: 从"只有一条消息估算"改为区分输入/输出:
 *  - 输入:优先用 provider 上报的真实 promptTokens;拿不到时退回上下文估算值(前缀 ~ 表示估算)
 *  - 输出:优先用真实 completionTokens;拿不到时用本消息正文的 BPE 估算
 * 单行布局:输入 · 输出 · 上下文占用百分比 + 细进度条。
 */
@Composable
fun TokenStatsBar(
    message: UIMessage,
    historyTokens: Int,
    contextWindow: Int,
    promptTokens: Int? = null,
    completionTokens: Int? = null,
    modifier: Modifier = Modifier,
) {
    val estimatedOutput = (TokenEstimator.estimate(listOf(message)) - 4).coerceAtLeast(0)
    val outputTokens = completionTokens ?: estimatedOutput
    // contextTokenCount 在收尾时已按完整消息列表(含本条助手回复)重估;
    // 无 provider usage 时扣掉输出估算,得到输入上下文的近似值,避免把输出重复算进输入。
    val inputTokens = promptTokens ?: (historyTokens - estimatedOutput).coerceAtLeast(0)
    val inputEstimated = promptTokens == null
    val outputEstimated = completionTokens == null

    // 实测两项相加;估算态直接用完整上下文计数(其中已经包含输出),避免重复加总。
    val used = if (promptTokens != null && completionTokens != null) {
        (inputTokens + outputTokens).coerceAtLeast(0)
    } else {
        historyTokens.coerceAtLeast(0)
    }
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
                append(stringResource(R.string.chat_token_prompt))
                append(" ")
                if (inputEstimated) append("~")
                append(formatTokenCount(inputTokens))
                append(" · ")
                append(stringResource(R.string.chat_token_output))
                append(" ")
                if (outputEstimated) append("~")
                append(formatTokenCount(outputTokens))
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
            MuseProgressBar(
                progress = ratio,
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

/** 紧凑 token 数字:1 万以上用 12.3k,避免长数字挤占单行空间。 */
internal fun formatTokenCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "%.1fk".format(value / 1000.0)
    else -> value.toString()
}
