package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.dao.MpInnerVoiceDao
import io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 心声读写门面：会话内伴随独白 + 全局历史 + 删除。
 *
 * 薄薄一层包住 DAO，让生成钩子与历史页/折叠区共用同一入口；id 由调用方给（沿用消息写入的
 * newId 约定，测试可注入固定 id），时间由调用方给，保持与其它写入器一致的可测性。
 */
internal class MpInnerVoiceStore(
    private val dao: MpInnerVoiceDao,
) {
    fun observeByThread(threadId: String): Flow<List<MpInnerVoiceEntity>> = dao.observeByThread(threadId)

    fun observeHistory(limit: Int): Flow<List<MpInnerVoiceEntity>> = dao.observeHistory(limit)

    suspend fun forMessage(messageId: String): MpInnerVoiceEntity? = dao.forMessage(messageId)

    suspend fun put(id: String, threadId: String, characterId: String, messageId: String?, text: String, createdAt: Long) {
        val trimmed = text.trim()
        // 空白心声不落库：模型偶尔只给一个换行，占一条历史反而像脏数据。
        if (trimmed.isEmpty()) return
        dao.insert(MpInnerVoiceEntity(id, threadId, characterId, messageId, trimmed, createdAt))
    }

    suspend fun deleteByThread(threadId: String) = dao.deleteByThread(threadId)
}
