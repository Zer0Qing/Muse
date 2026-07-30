package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.0.47 P5-3: Token 计数快照。
 *
 * 打开 Token 菜单时由 [io.zer0.muse.ui.ChatViewModel] 计算一次,
 * 避免每次按键都做 BPE 编码(性能开销大)。
 *
 * @param inputTokens 当前输入框文本 token 数
 * @param historyTokens 当前会话历史消息(含 reasoning/mood/reflection/toolCalls)token 数
 * @param contextWindow 当前模型上下文窗口(null 表示未知,菜单降级为只展示分项)
 */
data class TokenCountSnapshot(
    val inputTokens: Int,
    val historyTokens: Int,
    val contextWindow: Int?,
)

/**
 * v1.0.47 P5-3: Token 计数详情面板。
 *
 * 展示当前输入 / 历史消息 / 上下文窗口 / 可用剩余 / 占用进度条。
 * 使用 [MuseBottomSheet] 而非 ModalBottomSheet(真机 scrim 卡死 bug)。
 */
@Composable
fun TokenCountMenu(
    snapshot: TokenCountSnapshot,
    onDismissRequest: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_token_menu_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            TokenRow(
                label = stringResource(R.string.chat_token_input),
                value = snapshot.inputTokens,
            )
            TokenRow(
                label = stringResource(R.string.chat_token_history),
                value = snapshot.historyTokens,
            )

            val window = snapshot.contextWindow
            if (window != null && window > 0) {
                TokenRow(
                    label = stringResource(R.string.chat_token_context_window),
                    value = window,
                )
                val used = (snapshot.inputTokens + snapshot.historyTokens).coerceAtLeast(0)
                val remaining = (window - used).coerceAtLeast(0)
                TokenRow(
                    label = stringResource(R.string.chat_token_remaining),
                    value = remaining,
                )

                Spacer(modifier = Modifier.height(2.dp))
                val ratio = (used.toFloat() / window.toFloat()).coerceIn(0f, 1f)
                Text(
                    text = stringResource(R.string.chat_token_usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        ratio >= 0.9f -> MaterialTheme.colorScheme.error
                        ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.chat_token_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TokenRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatNumber(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 千分位格式化,便于阅读大数字。 */
private fun formatNumber(v: Int): String =
    "%,d".format(v)
