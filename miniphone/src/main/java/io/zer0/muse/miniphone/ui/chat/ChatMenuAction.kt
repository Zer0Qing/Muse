package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.MpMessageType

/**
 * 长按一条消息能做的事。
 *
 * 取值只描述「操作」,[label] 是菜单里那一行字 —— 判定规则集中在 [chatMenuActionsFor],
 * 界面只管把返回的项按顺序画出来。
 */
internal enum class ChatMenuAction(
    val labelRes: Int,
) {
    COPY(R.string.mp_chat_menu_copy),
    DELETE(R.string.mp_delete),
    RESEND(R.string.mp_chat_menu_resend),
}

/**
 * 某条消息的长按菜单项,顺序即展示顺序。
 *
 * - 复制:只有文本有可复制的正文,图片与语音的正文只是预览文案。
 *   存量数据里图片/语音可能仍是 `type=text` + 正文「[图片]」的旧形状，归一之后正文是内部记号
 *   `[mp:image]` —— 那种也没有可复制的东西，同样不给这一项。
 * - 删除:每一方发的消息都能删
 * - 重发:只有自己发出的文本,重发走的是「再要一次回复」那条链
 *
 * 系统提示不是任何一方说的话,整套菜单都不给它。
 */
internal fun chatMenuActionsFor(message: ChatMessageUi): List<ChatMenuAction> {
    if (message.isSystem) return emptyList()
    return buildList {
        if (message.type == MpMessageType.TEXT && !message.content.isSentinel()) add(ChatMenuAction.COPY)
        add(ChatMenuAction.DELETE)
        if (message.canResend) add(ChatMenuAction.RESEND)
    }
}

/**
 * 菜单落点:贴着消息那一行的上方,左右与消息所在一侧对齐。
 *
 * 上方空间不足时翻到行下方;两侧都夹进页边距内,不会贴着屏幕边缘。
 */
internal class ChatMenuPositionProvider(
    private val alignEnd: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val rawX = if (alignEnd) {
            anchorBounds.right - popupContentSize.width - marginPx
        } else {
            anchorBounds.left + marginPx
        }
        val aboveY = anchorBounds.top - popupContentSize.height - gapPx
        val rawY = if (aboveY >= marginPx) aboveY else anchorBounds.bottom + gapPx
        return IntOffset(rawX.coerceIn(marginPx, maxX), rawY.coerceIn(marginPx, maxY))
    }
}

/**
 * 正文是不是「这条消息是某种事情」的占位记号（图片/语音/没人接话）。
 *
 * 这些记号只给列表预览用，不是用户写下的字：复制它们等于把 `[mp:image]` 塞进剪贴板。
 */
private fun String.isSentinel(): Boolean = this == MpSentinel.IMAGE || this == MpSentinel.VOICE || this == MpSentinel.NO_REPLY
