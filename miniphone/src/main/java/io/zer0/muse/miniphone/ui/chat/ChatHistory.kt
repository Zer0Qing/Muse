package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType

/** 群聊历史里发言人名字与正文之间的分隔符。 */
internal const val SPEAKER_SEPARATOR: String = "："

/**
 * 交给宿主的会话历史:只取已落库的文本消息,按时间正序。
 *
 * 系统提示和图片/语音行不参与 —— 前者不是角色说过的话,后者没有文本可发。
 *
 * 群聊里角色消息一律写成「发言人名字：正文」。宿主的轮次只有「用户 / 非用户」两档,
 * 同一个群里几个人说话会落成同一个 assistant 身份:不打前缀,后说话的人根本看不出
 * 前面那句是谁说的,容易重复上一句或者答错人。打完前缀,谁接话都能顺着上下文往下说。
 * 用户消息保持原样(私信里也一样),宿主不需要改任何接口。
 */
internal fun chatHistoryOf(state: ChatUiState): List<MpChatTurn> = state.messages
    .filter { it.type == MpMessageType.TEXT && it.content.isNotBlank() }
    .map { message ->
        val speaker = if (state.isGroup && message.sender == MpActorType.CHARACTER) {
            state.memberNames[message.characterId].orEmpty()
        } else {
            ""
        }
        MpChatTurn(
            sender = message.sender,
            content = if (speaker.isEmpty()) message.content else "$speaker$SPEAKER_SEPARATOR${message.content}",
        )
    }
