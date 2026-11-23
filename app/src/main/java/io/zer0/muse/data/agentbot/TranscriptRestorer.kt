package io.zer0.muse.data.agentbot

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 对话流重建器。
 *
 * ## 为什么抽出来
 *
 * 这段逻辑在 ViewModel 里连续改了六轮，每轮都发现相邻的问题：
 * 内存态 → 生成器不统一 → 常量混用 → 一次读太多 → 抹掉新消息 → 顺序错乱。
 *
 * 根因不是每一轮改错了，而是**这段逻辑的规则太多**：
 *
 *  1. 分两段读（首屏快、补齐全）；
 *  2. 每段都要检查会话有没有切走；
 *  3. 补齐时以库为准、补上内存独有；
 *  4. 合并后按时间稳定排序。
 *
 * 四条规则混在 ViewModel 的协程里，任何一条出错都只能靠读代码发现。
 * 抽成纯函数之后，每条规则都能单独断言。
 */
class TranscriptRestorer(
    /**
     * 消息来源。为 null 时只能调 [merge] —— 那是纯函数，不需要仓储。
     *
     * 允许 null 是为了让合并规则能在纯 JVM 测试里逐条断言，
     * 不必构造一整套 Room + Repository。真要用 [read] 时由 Koin 注入。
     */
    private val sessions: SessionRepository? = null,
) {

    /** 一段读取的结果。 */
    data class Page(
        val messages: List<AgentBotUiState.PublishedMessage>,
        /** 读的时候所属的会话。调用方据此判断是否已经切走。 */
        val sessionId: String,
    )

    /**
     * 读一批。失败降级成空列表 —— 对话流空着比崩溃好。
     *
     * ## 为什么失败要记 error 而不是 warn
     *
     * 这里的返回值与"这个会话确实没有消息"完全一样（都是空列表）。
     * 用 warn 记一笔的话，日志里翻不出来，用户看到空白时也查不到原因 ——
     * 和"仓储为 null"那条是同一类问题，只是路径不同。
     *
     * 降级本身是对的（不能让一次读失败把整个页面打崩），
     * 但**降级必须留痕**，否则故障就变成"功能莫名其妙没反应"。
     */
    suspend fun read(sessionId: String, limit: Int): Page {
        val repo = sessions ?: run {
            // 不能静默返回空列表：那看起来和"这个会话真的没有消息"一模一样，
            // 用户会以为历史丢了，而实际是装配没接上。
            Logger.e("TranscriptRestorer", "没有注入 SessionRepository，对话流无法重建")
            return Page(emptyList(), sessionId)
        }
        val restored = runCatching { repo.getRecentMessages(sessionId, limit) }
            .onFailure {
                Logger.e(
                    "TranscriptRestorer",
                    "读取会话 $sessionId 的消息失败，对话流会显示为空: ${it.message}",
                    it,
                )
            }
            .getOrDefault(emptyList())
        return Page(restored.map(::toPublished), sessionId)
    }

    suspend fun readOlder(sessionId: String, beforeSeq: Long, limit: Int): Page {
        val repo = sessions ?: run {
            Logger.e("TranscriptRestorer", "没有注入 SessionRepository，对话历史无法分页")
            return Page(emptyList(), sessionId)
        }
        val restored = repo.getOlderMessages(sessionId, beforeSeq, limit)
        return Page(restored.map(::toPublished), sessionId)
    }

    /**
     * 持续观察 canonical 会话的消息流。
     *
     * 与 [read] 的降级策略相反：这里**不**把读失败折成空列表。
     * 页面有四态，读取失败必须显式成为错误并保留可重试入口；
     * 折成空列表的话，用户看到的是"对话记录凭空消失"。
     *
     * 没有注入仓储时同样让它失败：那是装配缺陷，不是"这个会话没有消息"。
     */
    fun observe(sessionId: String): Flow<List<AgentBotUiState.PublishedMessage>> {
        val repo = sessions ?: return flow {
            throw IllegalStateException("transcript_source_missing")
        }
        return repo.observeMessages(sessionId).map { messages -> messages.map(::toPublished) }
    }

    /**
     * 合并"库里的全量"与"内存里独有的"。
     *
     * ## 两条规则
     *
     * **一、以库为准**：库里有的是权威。内存里同 id 的丢弃（那份可能已经过时）。
     *
     * **二、补上内存独有**：两段之间用户可能发了新消息，它还没经过发布通道落库。
     * 整体替换会把它抹掉，用户看到自己刚发的话消失。
     *
     * 最后按 `createdAt` 稳定排序：内存独有的一般更晚，但后台任务结果可能插在中间。
     * 同毫秒时用 `messageId` 兜底，否则每次刷新顺序会抖动。
     */
    fun merge(
        persisted: List<AgentBotUiState.PublishedMessage>,
        inMemory: List<AgentBotUiState.PublishedMessage>,
    ): List<AgentBotUiState.PublishedMessage> {
        val persistedIds = persisted.mapTo(mutableSetOf()) { it.messageId }
        val extra = inMemory.filter { it.messageId !in persistedIds }
        return (persisted + extra).sortedWith(compareBy({ it.createdAt }, { it.seq }, { it.messageId }))
    }

    private fun toPublished(message: UIMessage) = AgentBotUiState.PublishedMessage(
        messageId = message.id.toString(),
        role = if (message.role == MessageRole.USER) {
            AgentBotUiState.Role.USER
        } else {
            AgentBotUiState.Role.BOT
        },
        text = message.content,
        createdAt = message.createdAt,
        seq = message.seq,
        imageUrls = message.imageUrls,
        imageBase64List = message.imageBase64List,
        reasoning = message.reasoning.orEmpty(),
        citationUrls = message.citationUrls,
    )
}
