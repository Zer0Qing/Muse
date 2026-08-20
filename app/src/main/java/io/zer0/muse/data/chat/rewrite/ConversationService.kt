package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.SessionRepository

/**
 * 对话重构适配层。
 *
 * 当前只负责 shadow 事件和回合状态，旧 ChatViewModel 仍然是发送主入口；
 * 后续灰度打开后可逐步把提交与 projection 接入此服务。
 */
class ConversationService(
    private val sessionRepository: SessionRepository,
) {
    suspend fun startTurn(turn: ConversationTurnEntity) {
        sessionRepository.upsertConversationTurn(turn)
    }

    suspend fun record(event: ConversationEventDraft): Boolean =
        sessionRepository.recordConversationEvent(event)

    suspend fun finishTurn(turnId: String, phase: String = "COMPLETED") {
        sessionRepository.finishConversationTurn(turnId, phase)
    }
}
