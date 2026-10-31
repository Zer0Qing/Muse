package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.ui.chat.ChatMenuPositionProvider
import io.zer0.muse.miniphone.ui.theme.MiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 长按一条会话能做的事。
 *
 * 取值只描述「操作」,[label] 是菜单里那一行字;某一项该不该出现由 [threadMenuActionsFor] 决定,
 * 界面只管把返回的项按顺序画出来。
 */
internal enum class ThreadMenuAction(
    val label: String,
) {
    PIN("置顶"),
    UNPIN("取消置顶"),
    MARK_READ("标记已读"),
    DELETE("删除会话"),
}

/**
 * 某条会话的长按菜单项,顺序即展示顺序。
 *
 * - 置顶 / 取消置顶:同一件事的两个方向,只出现当前可执行的那一个
 * - 标记已读:未读数为 0 时没有可清的东西,就不摆一个点了没反应的行
 * - 删除会话:总是有
 */
internal fun threadMenuActionsFor(thread: MiniPhoneThreadUi): List<ThreadMenuAction> = buildList {
    add(if (thread.pinned) ThreadMenuAction.UNPIN else ThreadMenuAction.PIN)
    if (thread.unreadCount > 0) add(ThreadMenuAction.MARK_READ)
    add(ThreadMenuAction.DELETE)
}

/** 会话行上的动作回调。三个都只带会话 id,界面不需要知道具体写哪张表。 */
@Immutable
internal class ThreadMenuActions(
    val onTogglePin: (threadId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onMarkRead: (threadId: String) -> Unit = {},
    val onDelete: (threadId: String) -> Unit = {},
)

/**
 * 会话长按菜单。
 *
 * 与聊天页的消息菜单同一套自绘浮层:底色、圆角、字号都取本模块的 token,
 * 不引 Material 的默认弹窗。位置以调用方放它的那个父布局(会话行)为准:
 * 优先贴在行上方,上方放不下就翻到下方,左右夹在页边距内。
 */
@Composable
internal fun ThreadRowMenu(
    thread: MiniPhoneThreadUi,
    onDismiss: () -> Unit,
    onAction: (ThreadMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = threadMenuActionsFor(thread)
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = false, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .testTag(MiniPhoneTags.THREAD_MENU)
                .width(MenuWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape),
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(dimens.hairline)
                            .background(colors.divider),
                    )
                }
                Text(
                    text = action.label,
                    style = typography.body.copy(color = action.colorIn(colors)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MiniPhoneTags.threadMenuAction(action))
                        .clickable {
                            onAction(action)
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** 删会话前的确认。删了就没了:会话和里面的消息一起消失。 */
@Composable
internal fun ThreadDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneTags.THREAD_DELETE_DIALOG)
                .width(DialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = DELETE_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = DELETE_HINT,
                style = typography.caption.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogAction(
                    label = "取消",
                    color = colors.textSecondary,
                    tag = MiniPhoneTags.THREAD_DELETE_CANCEL,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogAction(
                    label = "删除",
                    color = colors.unreadDot,
                    tag = MiniPhoneTags.THREAD_DELETE_CONFIRM,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** 确认框里的一个文字按钮:无底、留足按压面积。 */
@Composable
private fun DialogAction(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiniPhoneTheme.typography.body.copy(color = color),
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = DialogActionHeight)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 危险动作(删除)用砖红,其余用主字色。 */
private fun ThreadMenuAction.colorIn(colors: MiniPhoneColors): Color =
    if (this == ThreadMenuAction.DELETE) colors.unreadDot else colors.textPrimary

private val MenuWidth = 132.dp
private val MenuGap = 6.dp
private val DialogWidth = 280.dp

private val DialogActionHeight = 36.dp

private const val DELETE_TITLE: String = "删除这个会话?"
private const val DELETE_HINT: String = "会话和里面的全部消息都会从这台手机上删掉,无法恢复。"
