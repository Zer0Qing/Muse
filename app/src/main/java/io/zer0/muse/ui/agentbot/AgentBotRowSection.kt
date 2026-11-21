package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 带操作的行列表区块。
 *
 * ## 为什么抽出来
 *
 * 中枢页面有四类这样的区块：任务看板、待审批、已放行、待处理候选。
 * 它们形状完全一样 —— 标题 + 若干行（主文案 / 副文案 / 尾部动作）。
 * 复制四遍的代价不是"代码多"，是**改一处要改四处**，而且很容易漏。
 *
 * ## 它不做什么
 *
 * 不决定行里显示什么、也不决定动作是什么。那些由调用方给。
 * 这里只统一间距、对齐、文字截断与动作位置 —— 也就是"看起来是一套东西"的那部分。
 */
@Composable
fun <T> AgentBotRowSection(
    title: String,
    rows: List<T>,
    key: (T) -> String,
    primary: (T) -> String,
    secondary: @Composable (T) -> String?,
    actionLabel: String,
    onAction: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否把动作用次要样式（用于"丢弃"这类破坏性较低的操作）。 */
    secondaryAction: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primary(row),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    secondary(row)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                io.zer0.muse.ui.common.form.MuseCapsuleButton(
                    text = actionLabel,
                    onClick = { onAction(row) },
                    variant = if (secondaryAction) {
                        io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Text
                    } else {
                        io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary
                    },
                    fillWidth = false,
                )
            }
        }
    }
}
