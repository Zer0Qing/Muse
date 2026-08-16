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
 *  - 读取群聊记忆([getByAssistantAndChat])——由 SystemPromptAssembler 按"当前群聊"注入到
 *    system prompt,用 `<group_chat_memory>` 标签与主记忆 `<long_term_memory>` 区分。
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

    /** B-18: 同 (群聊, agent) 摘要去重窗口(30 分钟)。 */
    private companion object {
        const val DEDUP_WINDOW_MS = 30L * 60 * 1000
    }

    /**
     * 写入一条群聊记忆摘要。
     *
     * 审查修复 (2.0 B-18): 写入前按时间窗口去重 — 群聊轮转中每个 agent 每轮发言都触发
     * 一次 saveSummary,短时间窗口内累积近 10 条高度相似摘要,注入侧线性膨胀;
     * 同一 (群聊, agent) 在 [dedupWindowMs] 内已有摘要则跳过(返回既有摘要)。
     *
     * @param groupChatId 群聊 id
     * @param assistantId 助手 id
     * @param summary 摘要文本(LLM 生成或简单截取)
     * @param expiresAt 可选过期时间戳,null 表示永不过期
     * @param dedupWindowMs 去重窗口(毫秒),默认 30 分钟
     * @return 新写入的记忆实体(去重命中时返回既有实体)
     */
    suspend fun saveSummary(
        groupChatId: String,
        assistantId: String,
        summary: String,
        expiresAt: Long? = null,
        dedupWindowMs: Long = DEDUP_WINDOW_MS,
    ): GroupChatMemoryEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val latest = resultOf { dao.getByAssistantAndChat(assistantId, groupChatId, 1) }
            .onError { msg, t -> Logger.w(TAG, "群聊记忆去重查询失败: $msg", t) }
            .getOrNull()?.firstOrNull()
        if (latest != null && now - latest.createdAt < dedupWindowMs) {
            // B-18: 窗口内已有摘要,跳过重复写入(轮转各成员发言产生的摘要高度相似)
            return@withContext latest
        }
        val memory = GroupChatMemoryEntity(
            id = UUID.randomUUID().toString(),
            groupChatId = groupChatId,
            assistantId = assistantId,
            summary = summary,
            createdAt = now,
            expiresAt = expiresAt,
        )
        resultOf { dao.insert(memory) }
            .onError { msg, t -> Logger.w(TAG, "写入群聊记忆失败: $msg", t) }
        memory
    }

    /**
     * 取指定助手最近 N 条群聊记忆(按 createdAt 降序)。
     *
     * **A-09 修复后仅供兼容**:不限定群聊,会把该助手在**所有**群聊的摘要混回一起,
     * 是"跨群记忆串台"的读取根因之一。SystemPromptAssembler 的 prompt 注入一律改走
     * [getByAssistantAndChat](限定当前群聊),本方法不再用于注入。
     */
    suspend fun getByAssistant(assistantId: String, limit: Int = 10): List<GroupChatMemoryEntity> =
        withContext(Dispatchers.IO) {
            resultOf { dao.getByAssistant(assistantId, limit) }
                .onError { msg, t -> Logger.w(TAG, "读取群聊记忆失败: $msg", t) }
                .getOrNull() ?: emptyList()
        }

    /**
     * 取指定助手在**指定群聊**最近 N 条群聊记忆(按 createdAt 降序)。
     *
     * **A-09 修复**:带群聊隔离的注入读取,供 SystemPromptAssembler 只注入当前群聊的记忆。
     */
    suspend fun getByAssistantAndChat(assistantId: String, groupChatId: String, limit: Int = 10): List<GroupChatMemoryEntity> =
        withContext(Dispatchers.IO) {
            resultOf { dao.getByAssistantAndChat(assistantId, groupChatId, limit) }
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

    /** v1.0.72: 查询全部群聊记忆(记忆中心展示)。 */
    suspend fun getAll(): List<GroupChatMemoryEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getAll() }
            .onError { msg, t -> Logger.w(TAG, "读取全部群聊记忆失败: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /** v1.0.72: 删除单条群聊记忆(记忆中心单条删除)。 */
    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        resultOf { dao.deleteById(id) }
            .onError { msg, t -> Logger.w(TAG, "删除单条群聊记忆失败: $msg", t) }
    }

    /** v1.0.72: 清空全部群聊记忆(记忆中心一键清空)。 */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        resultOf { dao.deleteAll() }
            .onError { msg, t -> Logger.w(TAG, "清空全部群聊记忆失败: $msg", t) }
    }
}
