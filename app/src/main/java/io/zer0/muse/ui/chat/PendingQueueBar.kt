package io.zer0.muse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Send
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import io.zer0.muse.R
import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.205 B2: 待发送消息队列条 — 生成期间排队的消息逐条预览。
 *
 * 每条 chip:点击文本=编辑回填输入框,左侧小发送按钮=单独发送,右侧 X=移除;
 * 队列头部显示标题+计数,尾部清空按钮。仅在 [queue] 非空时由 ChatScreen 渲染。
 */
@Composable
internal fun PendingQueueBar(
    queue: List<PendingMessage>,
    onSend: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.compactChipVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = stringResource(R.string.chat_pending_queue_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.chat_pending_count, queue.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                itemsIndexed(queue, key = { index, _ -> index }) { index, item ->
                    PendingQueueChip(
                        item = item,
                        onSend = { onSend(index) },
                        onEdit = { onEdit(index) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = TablerIcons.Trash,
                    contentDescription = stringResource(R.string.chat_pending_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        }
    }
}

/**
 * v1.205 B2: 队列单条 chip。
 *
 * 布局:发送按钮(单独发送) | 文本预览(点击=编辑回填) | 移除按钮。
 * 纯图片条目文本为空,预览用占位文案 [R.string.chat_pending_image_only]。
 */
@Composable
private fun PendingQueueChip(
    item: PendingMessage,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = MusePaddings.tightGap),
        ) {
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = TablerIcons.Send,
                    contentDescription = stringResource(R.string.chat_pending_send_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
            ) {
                Text(
                    text = item.text.ifBlank { stringResource(R.string.chat_pending_image_only) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = TablerIcons.X,
                    contentDescription = stringResource(R.string.chat_pending_remove_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}
