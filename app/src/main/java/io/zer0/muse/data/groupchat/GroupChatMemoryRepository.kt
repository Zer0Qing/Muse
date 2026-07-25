package io.zer0.muse.data.groupchat

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v2.x: 群聊记忆仓库 — 封装 [GroupChatMemoryDao],暴露 suspend 方法。
 *
 * 职责:
 *  - 写入群聊记忆([saveSummary])——由 GroupChatScheduler 在 agent 完成回复后调用,
 *    把本轮群聊对话摘要写入独立 fact store,**不**写入助手主记忆系统。
 *  - 读取群聊记忆([getByAssistant])——由 SystemPromptAssembler 注入到 system prompt,
 *    用 `<group_chat_memory>` 标签与主记忆 `<long_term_memory>` 区分。
 *  - 清理([cleanupOlderThan])——定期清理过期/陈旧记忆。
 *
 * 与主记忆系统([io.zer0.memory] 模块)的关系:
 *  - 完全独立:不共享表、不共享 LLM 调用、不共享编译管道
 *  - 仅通过 SystemPromptAssembler 在 prompt 层面"叠加",不互相写入
 *
 * 所有 DB 操作包裹在 [Dispatchers.IO] 中,通过 [resultOf] 容错。
 */
class GroupChatMemoryRepository(
    private val dao: GroupChatMemoryDao,
) {

    private val TAG = "GroupChatMemRepo"

    /**
     * 写入一条群聊记忆摘要。
     *
     * @param groupChatId 群聊 id
     * @param assistantId 助手 id
     * @param summary 摘要文本(LLM 生成或简单截取)
     * @param expiresAt 可选过期时间戳,null 表示永不过期
     * @return 新写入的记忆实体
     */
    suspend fun saveSummary(
        groupChatId: String,
        assistantId: String,
        summary: String,
        expiresAt: Long? = null,
    ): GroupChatMemoryEntity = withContext(Dispatchers.IO) {
        val memory = GroupChatMemoryEntity(
            id = UUID.randomUUID().toString(),
            groupChatId = groupChatId,
            assistantId = assistantId,
            summary = summary,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
        )
        resultOf { dao.insert(memory) }
            .onError { msg, t -> Logger.w(TAG, "写入群聊记忆失败: $msg", t) }
        memory
    }

    /**
     * 取指定助手最近 N 条群聊记忆(按 createdAt 降序)。
     * 供 SystemPromptAssembler 注入到 system prompt。
     */
    suspend fun getByAssistant(assistantId: String, limit: Int = 10): List<GroupChatMemoryEntity> =
        withContext(Dispatchers.IO) {
            resultOf { dao.getByAssistant(assistantId, limit) }
                .onError { msg, t -> Logger.w(TAG, "读取群聊记忆失败: $msg", t) }
                .getOrNull() ?: emptyList()
        }

    /** 取指定群聊的全部记忆(供群聊详情页展示)。 */
    suspend fun getByGroupChat(groupChatId: String): List<GroupChatMemoryEntity> =
        withContext(Dispatchers.IO) {
            resultOf { dao.getByGroupChat(groupChatId) }
                .onError { msg, t -> Logger.w(TAG, "读取群聊记忆失败: $msg", t) }
                .getOrNull() ?: emptyList()
        }

    /**
     * 清理 [days] 天前的群聊记忆(默认 30 天)。
     * 供定期清理任务调用,避免记忆无限膨胀。
     */
    suspend fun cleanupOlderThan(days: Int = 30) = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis() - days * 86_400_000L
        resultOf { dao.deleteOlderThan(before) }
            .onError { msg, t -> Logger.w(TAG, "清理群聊记忆失败: $msg", t) }
    }

    /** 删除指定群聊的全部记忆(群聊删除时级联调用)。 */
    suspend fun deleteByGroupChat(groupChatId: String) = withContext(Dispatchers.IO) {
        resultOf { dao.deleteByGroupChat(groupChatId) }
            .onError { msg, t -> Logger.w(TAG, "删除群聊记忆失败: $msg", t) }
    }
}
