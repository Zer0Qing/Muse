@file:Suppress("FunctionNaming") // Compose 组件名按惯例大写开头（与项目内其它 UI 文件一致）

package io.zer0.muse.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.MemoryItem
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog

/**
 * 时间轴卡片长按后的动作面板与删除二次确认。
 *
 * 时间轴卡片此前只有「点击展开全文」一个入口,记忆删不掉 —— 列表模式早就有了
 * 「更多 → 删除 → 二次确认」,时间轴这边长按还被多选占用,用户只能切回列表视图逐条删。
 *
 * 面板本身只有两个动作:进多选(仅事实)、删除。删除不直接落库,而是先换成
 * [ConfirmDeleteDialog] 的二次确认 —— 删完不可撤销,点错一下就没法找回来。
 *
 * @param target 长按的那张卡片
 * @param onDelete 用户在确认框里确认后回调(传入已还原成可路由形态的条目)
 * @param onSelectMultiple 仅事实卡片提供;回调后由调用方关闭面板并进入多选态
 * @param onDismiss 取消/点外部关闭
 */
@Composable
internal fun TimelineItemActions(
    target: TimelineItem,
    onDelete: (MemoryItem) -> Unit,
    onDismiss: () -> Unit,
    onSelectMultiple: ((MemoryItem) -> Unit)? = null,
) {
    var confirming by remember(target.id) { mutableStateOf(false) }
    // 同一次长按里的删除目标固定不变,确认框与面板共用同一份还原结果
    val routable = remember(target) { target.toMemoryItem() }
    if (confirming) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_delete_confirm_title),
            itemName = routable.title.ifBlank { routable.content },
            consequence = stringResource(R.string.memory_delete_consequence),
            onConfirm = {
                confirming = false
                onDelete(routable)
            },
            onDismiss = onDismiss,
        )
        return
    }
    MuseBottomSheet(onDismissRequest = onDismiss, bottomContentSpacing = 0.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = memoryItemNameHint(target.content),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            onSelectMultiple?.let { select ->
                MuseActionSheetRow(
                    icon = MuseIcons.circleCheck,
                    text = stringResource(R.string.memory_menu_multi_select),
                    onClick = { select(routable) },
                )
            }
            MuseActionSheetRow(
                icon = MuseIcons.trash,
                text = stringResource(R.string.memory_menu_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { confirming = true },
            )
        }
    }
}

/**
 * 时间轴卡片 → 可被记忆条目动作分派消费的条目。
 *
 * 卡片为了布局只留了展示字段,删除要回到 [TimelineItem.itemId] + [TimelineItem.scope];
 * 摘要条目的 itemId 就是会话 id,所以删的是长按的那一条而不是别的会话。
 * 卡片不展示标题,确认框点名用正文首行截断后的短名。
 */
internal fun TimelineItem.toMemoryItem(): MemoryItem = MemoryItem(
    id = itemId,
    title = memoryItemNameHint(content),
    content = content,
    tags = tags,
    time = createdAt,
    source = source,
    importance = importance,
    sessionId = sessionId,
    createdAt = createdAt,
    scope = scope,
)

/** 长记忆的正文首行截断成一句可读的名字(面板标题 / 确认框点名共用)。 */
internal fun memoryItemNameHint(content: String): String =
    content.lineSequence().firstOrNull { it.isNotBlank() }?.take(TIMELINE_ACTION_NAME_MAX).orEmpty()

private const val TIMELINE_ACTION_NAME_MAX = 40
