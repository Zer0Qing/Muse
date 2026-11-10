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
    fun `存量数据的图片正文不给复制`() {
        // 老库里图片/语音可能还是 type=text + 正文「[图片]」；归一之后正文是内部记号，
        // 复制它等于把 `[mp:image]` 塞进剪贴板 —— 那不是用户写下的字。
        listOf(MpSentinel.IMAGE, MpSentinel.VOICE, MpSentinel.NO_REPLY).forEach { sentinel ->
            assertEquals(
                "正文是占位记号时不该给复制: $sentinel",
                listOf(ChatMenuAction.DELETE),
                chatMenuActionsFor(message(MpActorType.CHARACTER, MpMessageType.TEXT, content = sentinel)),
            )
        }
    }

    @Test
    fun systemNoticeHasNoMenuAtAll() {
        assertEquals(emptyList<ChatMenuAction>(), chatMenuActionsFor(message(MpActorType.SYSTEM, MpMessageType.SYSTEM)))
    }

    private fun message(sender: MpActorType, type: MpMessageType, content: String = "正文"): ChatMessageUi = ChatMessageUi(
        id = "m1",
        sender = sender,
        type = type,
        content = content,
        createdAt = 1_000L,
    )
}
