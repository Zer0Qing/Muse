package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长按菜单的可见规则:谁能复制、谁能重发。
 *
 * 规则是纯函数,这里把它钉死;单次长按真的能弹出来、弹出来的是这几项,由 UI 测试负责。
 */
class ChatMenuActionsTest {

    @Test
    fun ownTextOffersCopyDeleteAndResend() {
        assertEquals(
            listOf(ChatMenuAction.COPY, ChatMenuAction.DELETE, ChatMenuAction.RESEND),
            chatMenuActionsFor(message(MpActorType.USER, MpMessageType.TEXT)),
        )
    }

    @Test
    fun characterTextOffersNoResend() {
        assertEquals(
            listOf(ChatMenuAction.COPY, ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.CHARACTER, MpMessageType.TEXT)),
        )
    }

    @Test
    fun imageAndVoiceOfferNoCopy() {
        assertEquals(
            listOf(ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.USER, MpMessageType.IMAGE)),
        )
        assertEquals(
            listOf(ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.CHARACTER, MpMessageType.VOICE)),
        )
    }

    @Test
    fun systemNoticeHasNoMenuAtAll() {
        assertEquals(emptyList<ChatMenuAction>(), chatMenuActionsFor(message(MpActorType.SYSTEM, MpMessageType.SYSTEM)))
    }

    private fun message(sender: MpActorType, type: MpMessageType): ChatMessageUi = ChatMessageUi(
        id = "m1",
        sender = sender,
        type = type,
        content = "内容",
        createdAt = 1_000L,
    )
}
