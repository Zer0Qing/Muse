package io.zer0.muse.miniphone.ui.chat

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
import io.zer0.muse.miniphone.ui.theme.MiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 消息长按菜单。
 *
 * 用自己画的浮层而不是 Material 的默认菜单:底色、圆角、字号都取本模块的 token,
 * 与气泡、卡片同一套质感,深色下也不会突然白一块。
 *
 * 位置以本组件所在的父布局为准(调用方把它放在消息那一行里):
 * 优先贴在那一行上方,上方放不下就翻到下方;左右与消息所在一侧对齐。
 */
@Composable
internal fun ChatMessageMenu(
    message: ChatMessageUi,
    onDismiss: () -> Unit,
    onAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = chatMenuActionsFor(message)
    if (actions.isEmpty()) return
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(message.isMine, gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = message.isMine, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .testTag(MiniPhoneChatTags.MENU)
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
                        .testTag(MiniPhoneChatTags.menuAction(action))
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

/** 删除一条消息前的确认。只确认一次,确认后本机就删掉。 */
@Composable
internal fun ChatDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneChatTags.DELETE_DIALOG)
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
                    tag = MiniPhoneChatTags.DELETE_CANCEL,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogAction(
                    label = "删除",
                    color = colors.unreadDot,
                    tag = MiniPhoneChatTags.DELETE_CONFIRM,
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
private fun ChatMenuAction.colorIn(colors: MiniPhoneColors) = if (this == ChatMenuAction.DELETE) colors.unreadDot else colors.textPrimary

private val MenuWidth = 132.dp
private val MenuGap = 6.dp
private val DialogWidth = 280.dp
private val DialogActionHeight = 36.dp

private const val DELETE_TITLE: String = "删除这条消息?"
private const val DELETE_HINT: String = "只从这台手机的聊天记录里删掉,会话本身不受影响。"
