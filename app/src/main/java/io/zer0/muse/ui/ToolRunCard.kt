package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 操作组卡 —— 把**连续的工具调用**聚合为一张卡。
 *
 * - 折叠态(默认):一行摘要(执行了 N 个操作 + 工具名链);
 * - 展开态:逐个复用 [ToolCallCard],与单条工具调用的细节展示保持一致。
 *
 * 分组由 [io.zer0.muse.ui.chat.ChatDisplayGrouper] 决定;渲染接入见 ChatScreen ——
 * 组首条渲染本卡,组内其余条不渲染(消息索引保持不变,滚动/高亮无需调整)。
 */
@Composable
internal fun ToolRunCard(
    msgs: List<UIMessage>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toolInfos = msgs.mapNotNull { it.toolCallInfo }
    if (toolInfos.isEmpty()) return

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = TablerIcons.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_tool_run_summary, toolInfos.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 有失败步骤时给个警告标志,折叠态也能一眼看到
                    if (toolInfos.any { !it.isSuccess && it.result.isNotBlank() }) {
                        Icon(
                            imageVector = TablerIcons.AlertTriangle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // 工具名链(去重后展示前 4 个)
                val names = toolInfos.map { it.toolName }.distinct()
                if (names.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = buildString {
                            append(names.take(4).joinToString(" -> "))
                            if (names.size > 4) append(" ...")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    toolInfos.forEach { info ->
                        ToolCallCard(
                            toolName = info.toolName,
                            arguments = info.arguments,
                            result = info.result,
                            isSuccess = info.isSuccess,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
