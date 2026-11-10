package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.dao.MpOfflineMessageDao
import io.zer0.muse.miniphone.data.db.entity.MpOfflineMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 待应答队列门面：发出一条消息就记一行，应答到达或明确失败就清掉。
 * 空白内容不入队；同一 id 重复写入覆盖，不会攒出两份。
 */
internal class MpOfflineQueueStore(
    private val dao: MpOfflineMessageDao,
) {
    fun observeByThread(threadId: String): Flow<List<MpOfflineMessageEntity>> = dao.observeByThread(threadId)

    fun observeAll(): Flow<List<MpOfflineMessageEntity>> = dao.observeAll()

    /** 一次性读某会话的待应答行；恢复路径用，不等 Flow 首次发射。 */
    suspend fun pendingInThread(threadId: String): List<MpOfflineMessageEntity> = dao.listByThread(threadId)

    suspend fun enqueue(id: String, threadId: String, characterId: String, content: String, createdAt: Long): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        dao.insert(
            MpOfflineMessageEntity(
                id = id,
                threadId = threadId,
                characterId = characterId,
                content = trimmed,
                createdAt = createdAt,
            ),
        )
        return true
    }

    suspend fun settle(id: String) = dao.delete(id)

    suspend fun clearThread(threadId: String) = dao.deleteByThread(threadId)
}
