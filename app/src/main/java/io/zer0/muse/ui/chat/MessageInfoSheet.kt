package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A5: 消息信息弹层 — 即点即看 模型/时间/耗时/Token 用量。
 *
 * 数据来源(全部来自 [UIMessage],由 ChatViewModel 生成结束时回填,见 finalizeResponse):
 *  - [UIMessage.modelId] / [UIMessage.createdAt]:基础信息
 *  - [UIMessage.durationMs]:生成总耗时(含工具循环;中断路径也回填)
 *  - [UIMessage.promptTokens] / [UIMessage.completionTokens] / [UIMessage.reasoningTokens] /
 *    [UIMessage.cachedTokens]:provider 实测值(流式 UsageDelta 末值)
 *
 * Token 展示状态:
 *  - 有实测(任一分项非 null)→ 展示全部分项 + 「实测」标签
 *  - 无实测但为助手消息 → 本地估算占位(约 2 字符/token,与 StatsPage 同一启发式)+ 「估算」标签。
 *    业务原因:provider 未返回 usage 时(旧数据/不支持的网关/中断流)仍给用户一个数量级参考,
 *    用「估算」标签明示非官方值,不冒充实测。
 *  - 用户消息 → 「无生成数据」
 *
 * 入口:MessageBubble 长按扩展菜单 / 桌面右键菜单的「消息信息」项。
 */
@Composable
fun MessageInfoSheet(
    msg: UIMessage,
    onDismiss: () -> Unit,
) {
    val modelLabel = stringResource(R.string.msg_info_model)
    val timeLabel = stringResource(R.string.msg_info_time)
    val durationLabel = stringResource(R.string.msg_info_duration)
    val tokensTitle = stringResource(R.string.msg_info_tokens_title)
    val promptLabel = stringResource(R.string.msg_info_prompt)
    val completionLabel = stringResource(R.string.msg_info_completion)
    val reasoningLabel = stringResource(R.string.msg_info_reasoning)
    val cachedLabel = stringResource(R.string.msg_info_cached)
    val totalLabel = stringResource(R.string.msg_info_total)
    val measuredTag = stringResource(R.string.msg_info_measured)
    val estimatedTag = stringResource(R.string.msg_info_estimated)
    val unknown = stringResource(R.string.msg_info_unknown)
    val noData = stringResource(R.string.msg_info_no_data)

    val timeText = SimpleDateFormat(
        MuseDateFormats.DATE_TIME_SHORT,
        Locale.getDefault(),
    ).format(Date(msg.createdAt))

    val hasMeasured = msg.promptTokens != null || msg.completionTokens != null

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.msg_info_title),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoRow(modelLabel, msg.modelId?.takeIf { it.isNotBlank() } ?: unknown)
                InfoRow(timeLabel, timeText)
                // 耗时:用户消息/旧数据为 null 时不展示该行
                msg.durationMs?.let { InfoRow(durationLabel, formatDuration(it)) }

                Text(
                    text = tokensTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (hasMeasured) {
                    InfoRow(promptLabel, "${msg.promptTokens ?: 0}", measuredTag)
                    InfoRow(completionLabel, "${msg.completionTokens ?: 0}", measuredTag)
                    InfoRow(reasoningLabel, "${msg.reasoningTokens ?: 0}", measuredTag)
                    InfoRow(cachedLabel, "${msg.cachedTokens ?: 0}", measuredTag)
                    InfoRow(
                        totalLabel,
                        "${(msg.promptTokens ?: 0) + (msg.completionTokens ?: 0)}",
                        measuredTag,
                    )
                } else if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank()) {
                    // 估算占位:provider 未返回 usage 时按正文长度粗估(约 2 字符/token)
                    val estimated = (msg.content.length / 2).coerceAtLeast(1)
                    InfoRow(totalLabel, "$estimated", estimatedTag)
                } else {
                    InfoRow(noData, "")
                }
            }
        },
    )
}

/** 标签-值 行;有 [tag](实测/估算)时在值后追加小字标签。 */
@Composable
private fun InfoRow(label: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (tag != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 生成耗时格式化: <1s 用 ms;<60s 用秒(1 位小数);否则 分+秒。 */
private fun formatDuration(ms: Long): String = when {
    ms < 1_000L -> "${ms} ms"
    ms < 60_000L -> String.format(Locale.US, "%.1f s", ms / 1000f)
    else -> "${ms / 60_000L} min ${(ms % 60_000L) / 1000L} s"
}
