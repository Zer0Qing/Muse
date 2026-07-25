package io.zer0.muse.data.agentdm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Agent DM 仓库 (openhanako dm-tool.ts 移植)。
 */
class AgentDmRepository(private val dao: AgentMessageDao) {

    suspend fun sendMessage(fromAgentId: String, toAgentId: String, content: String, replyToId: String? = null): AgentMessageEntity = withContext(Dispatchers.IO) {
        val msg = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            content = content,
            replyToId = replyToId,
        )
        dao.upsert(msg)
        msg
    }

    suspend fun getInbox(agentId: String, limit: Int = 50) = dao.getInbox(agentId, limit)
    suspend fun getSent(agentId: String, limit: Int = 50) = dao.getSent(agentId, limit)
    suspend fun getConversation(a: String, b: String, limit: Int = 100) = dao.getConversation(a, b, limit)
    suspend fun getUnreadCount(agentId: String) = dao.getUnreadCount(agentId)
    suspend fun markRead(id: String) = dao.markRead(id)
    suspend fun markAllRead(agentId: String) = dao.markAllRead(agentId)
    suspend fun cleanupOlderThan(days: Int = 30) = dao.deleteOlderThan(System.currentTimeMillis() - days * 86400000L)
    fun observeUnread(agentId: String) = dao.observeUnread(agentId)
}
