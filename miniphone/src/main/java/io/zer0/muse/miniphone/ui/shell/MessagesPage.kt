package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 消息页:会话列表。
 *
 * 行结构固定为「头像 / 名字 / 最后一条消息 / 时间 / 未读」,顺序和位置都来自本模块的尺寸阶梯,
 * 不跟着宿主的列表组件走。排序由 DAO 保证(置顶在前,其余按最后消息倒序),这里只负责画。
 *
 * 点击进会话,长按弹 [ThreadRowMenu]:置顶 / 取消置顶、标记已读(有未读时才列)、删除会话。
 * 菜单与删除确认都只用本页自己的状态,不影响列表数据。
 *
 * @param onGoContacts 一条会话都没有时,空态里的按钮用它把用户送进通讯录
 * @param actions 会话行上的三个动作(置顶 / 标记已读 / 删除),由外壳接到 ViewModel
 */
@Composable
internal fun MessagesPage(
    threads: List<MiniPhoneThreadUi>,
    modifier: Modifier = Modifier,
    onOpenThread: (String) -> Unit = {},
    onGoContacts: () -> Unit = {},
    actions: ThreadMenuActions = ThreadMenuActions(),
) {
    // 正在弹菜单的会话与等待删除确认的会话:一个时间点只有一个
    var menuThreadId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    if (threads.isEmpty()) {
        ThreadsEmptyState(onGoContacts = onGoContacts, modifier = modifier)
        return
    }
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = threads, key = { it.id }) { thread ->
            Box {
                ThreadRow(
                    thread = thread,
                    onOpen = { onOpenThread(thread.id) },
                    onLongPress = { menuThreadId = thread.id },
                )
                if (menuThreadId == thread.id) {
                    ThreadRowMenu(
                        thread = thread,
                        onDismiss = { menuThreadId = null },
                        onAction = { action ->
                            when (action) {
                                ThreadMenuAction.PIN -> actions.onTogglePin(thread.id, true)
                                ThreadMenuAction.UNPIN -> actions.onTogglePin(thread.id, false)
                                ThreadMenuAction.MARK_READ -> actions.onMarkRead(thread.id)
                                ThreadMenuAction.DELETE -> pendingDeleteId = thread.id
                            }
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dimens.pageMargin + dimens.avatarList + ThreadTextGap)
                    .height(dimens.hairline)
                    .background(colors.divider),
            )
        }
    }
    val deletingId = pendingDeleteId
    if (deletingId != null) {
        ThreadDeleteConfirmDialog(
            onDismiss = { pendingDeleteId = null },
            onConfirm = {
                actions.onDelete(deletingId)
                pendingDeleteId = null
            },
        )
    }
}

/** 一行会话。 */
@Composable
private fun ThreadRow(thread: MiniPhoneThreadUi, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.thread(thread.id))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = dimens.pageMargin, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MiniPhoneAvatar(name = thread.name, size = dimens.avatarList)
            if (thread.unreadCount > 0) {
                UnreadBadge(
                    count = thread.unreadCount,
                    threadId = thread.id,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-5).dp),
                )
            }
        }
        Spacer(Modifier.width(ThreadTextGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.name,
                    style = typography.body.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTimeOf(thread.lastMessageAt),
                    style = typography.timestamp.copy(color = colors.textSecondary),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = thread.preview.ifBlank { EMPTY_PREVIEW_TEXT },
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 未读角标。超过两位数的按 99+ 收口,不让它把头像挤变形。 */
@Composable
private fun UnreadBadge(count: Int, threadId: String, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = dimens.unreadBadge, minHeight = dimens.unreadBadge)
            .clip(CircleShape)
            .background(colors.unreadDot)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BADGE_COUNT) FULL_BADGE_TEXT else count.toString(),
            style = typography.timestamp.copy(color = colors.unreadDotText, fontWeight = FontWeight.Medium),
            modifier = Modifier.testTag(MiniPhoneTags.unread(threadId)),
        )
    }
}

/**
 * 空会话列表:一枚自己画的气泡、两行说明,加一个直接去通讯录的按钮。
 *
 * 只写一行灰字等于把「怎么开始」交给用户自己猜 —— 按钮把这一步变成一次点击。
 */
@Composable
private fun ThreadsEmptyState(onGoContacts: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val buttonShape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.EMPTY_THREADS)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyBubble()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有聊天",
            style = typography.title.copy(color = colors.textPrimary),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "去通讯录找个角色开始吧",
            style = typography.caption.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = GO_CONTACTS_LABEL,
            style = typography.caption.copy(color = colors.accent, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .testTag(MiniPhoneTags.EMPTY_THREADS_ACTION)
                .clip(buttonShape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, buttonShape)
                .clickable(onClick = onGoContacts)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** 空态插画:一个空心气泡加三个点,颜色取强调色的低透明度。 */
@Composable
private fun EmptyBubble() {
    val ink = MiniPhoneTheme.colors.accent.copy(alpha = 0.4f)
    Canvas(Modifier.size(88.dp)) {
        val strokeWidth = 2.dp.toPx()
        val bubbleWidth = 60.dp.toPx()
        val bubbleHeight = 40.dp.toPx()
        val left = (size.width - bubbleWidth) / 2f
        val top = size.height * 0.16f
        drawRoundRect(
            color = ink,
            topLeft = Offset(left, top),
            size = Size(bubbleWidth, bubbleHeight),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
        val dotRadius = 2.5.dp.toPx()
        val dotGap = 8.dp.toPx()
        val dotY = top + bubbleHeight / 2f
        val firstDotX = left + bubbleWidth / 2f - dotGap
        for (index in -1..1) {
            drawCircle(color = ink, radius = dotRadius, center = Offset(firstDotX + index * dotGap, dotY))
        }
        val tail = Path().apply {
            moveTo(left + 12.dp.toPx(), top + bubbleHeight)
            lineTo(left + 6.dp.toPx(), top + bubbleHeight + 11.dp.toPx())
            lineTo(left + 26.dp.toPx(), top + bubbleHeight)
            close()
        }
        drawPath(path = tail, color = ink, style = Stroke(width = strokeWidth))
    }
}

/** 头像与文字之间的固定间距。 */
private val ThreadTextGap = 12.dp

private const val MAX_BADGE_COUNT: Int = 99
private const val FULL_BADGE_TEXT: String = "99+"
private const val EMPTY_PREVIEW_TEXT: String = "还没有消息"
private const val GO_CONTACTS_LABEL: String = "去通讯录"
