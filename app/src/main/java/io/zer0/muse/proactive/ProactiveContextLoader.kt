package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeEntity
import io.zer0.muse.data.proactive.ThoughtDeriver
import kotlinx.coroutines.flow.first

/**
 * 收集一轮巡检需要的外部材料：助手、会话、最近对话、以及账本的新燃料。
 *
 * 单独成类是因为它全是「从别处取东西」，失败方式也一致 ——
 * 每一样都可能因为依赖还没就绪而拿不到，而拿不到时应当降级而不是让整轮崩掉。
 * 把它们跟判定/生成分开，能让「取数据」的容错策略集中在一处。
 */
class ProactiveContextLoader(
    private val stores: ProactiveStores,
    private val runtime: ProactiveRuntime,
    private val sources: ProactiveSources,
) {

    companion object {
        private const val TAG = "ProactiveContext"

        /**
         * 送进生成上下文的消息条数。
         *
         * 按「轮」算：一问一答算一轮，20 轮 = 40 条。原来的 12 条只够看六轮，
         * 昨天的「我去吃饭了」根本进不来，于是主动消息只能从更早的记忆里找话题，
         * 找出来的自然是几天前的陈事。
         */
        const val RECENT_MESSAGE_COUNT = 40

        /** 取最近几条主动消息做「别再说一遍」的提醒。 */
        const val RECENT_PROACTIVE_COUNT = 4
    }

    /** 一轮巡检需要的会话侧材料。 */
    data class SessionContext(
        val sessionId: String,
        val recentMessages: List<UIMessage>,
        val lastUserMessageAt: Long,
        /**
         * 最近几条主动消息的正文。
         *
         * 防重复不能只靠「模型看得见历史」—— 主动消息发出去后如果用户没回，
         * 下次它自己就成了历史里的最后一条，模型反而最容易被它带跑。
         * 单独列出来并标明「这些已经说过了」，比指望它自己发现更可靠。
         */
        val recentProactiveTexts: List<String> = emptyList(),
    )

    /**
     * 取该发消息的助手。
     *
     * 优先用配置里指定的，否则 default，再否则第一个。
     * 与旧实现的区别是不再依赖「当前前台助手」这种易变状态。
     */
    suspend fun loadAssistant(configuredId: String): AssistantEntity? {
        val all = runCatching { runtime.assistants.observeAll.first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return null
        return all.firstOrNull { it.id == configuredId.takeIf { id -> id.isNotBlank() } }
            ?: all.firstOrNull { it.id == "default" }
            ?: all.first()
    }

    /**
     * 取最近对话与用户最后发言时间。
     *
     * 失败返回空上下文而不是 null：没有历史对话时仍然可以主动开口
     * （比如用户刚设置了一条提醒），不该因此整轮跳过。
     */
    suspend fun loadSessionContext(
        sessionId: String,
        assistantId: String = "",
    ): SessionContext {
        val all = runCatching { runtime.sessions.observeMessages(sessionId).first() }
            .getOrDefault(emptyList())
        // 工具消息不进上下文：主动消息要接的是「人说的话」，工具往返只是过程噪音。
        val recent = all
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(RECENT_MESSAGE_COUNT)
        val lastUserAt = recent.lastOrNull { it.role == MessageRole.USER }?.createdAt ?: 0L
        return SessionContext(sessionId, recent, lastUserAt, recentProactiveTexts(all, assistantId))
    }

    /**
     * 通过 outcome 里记的 messageId 反查最近几条主动消息的正文。
     *
     * 全部失败就返回空列表 —— 防重复是锦上添花，拿不到不该让整轮巡检停下。
     */
    private suspend fun recentProactiveTexts(
        allMessages: List<UIMessage>,
        assistantId: String,
    ): List<String> {
        if (assistantId.isBlank()) return emptyList()
        return runCatching {
            val ids = stores.outcomes.getRecent(assistantId, RECENT_PROACTIVE_COUNT)
                .mapNotNull { it.messageId }
                .toSet()
            if (ids.isEmpty()) {
                emptyList()
            } else {
                allMessages.filter { it.id.toString() in ids }.map { it.content }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 定位主动消息要写进哪个会话。
     *
     * 比旧实现的四级回退链简单得多：该助手最近的一个 agent 会话，
     * 没有就新建。不猜「用户当前在看哪个」—— 猜错会串会话，
     * 而串会话的表现是「用 A 的人设说话，写进了 B 的房间」，很难被当成 bug 报上来。
     */
    suspend fun resolveSession(assistant: AssistantEntity): String? {
        val recent = runCatching {
            runtime.sessions.getRecentAgentByAssistant(assistant.id, 1).firstOrNull()
        }.getOrNull()
        if (recent != null) return recent.id
        return runCatching {
            runtime.sessions.createAgentSession(assistant.id)
        }.getOrNull()
    }

    /** 最近一条主动消息的发出时间，用于抑制连发。 */
    suspend fun lastSentAt(assistantId: String): Long = runCatching {
        stores.outcomes.getRecent(assistantId, 1).firstOrNull()?.sentAt ?: 0L
    }.getOrDefault(0L)

    /** 找某会话里还在等反应的主动消息。 */
    suspend fun findPendingOutcome(sessionId: String): ProactiveOutcomeEntity? =
        stores.outcomes.findPendingInSession(sessionId)

    /**
     * 从记忆与里程碑推导新牵挂。
     *
     * 失败不抛：推导只是「补充燃料」，坏了不该让整轮巡检不跑。
     */
    suspend fun deriveThoughts(assistantId: String, now: Long) {
        runCatching {
            val tracked = stores.thoughts.getOpenSourceRefs(assistantId)
            val scope = if (assistantId == "default") "main" else assistantId
            val facts = runCatching { sources.factStore.getByScope(scope) }.getOrDefault(emptyList()) +
                runCatching { sources.factStore.getByScope("main") }.getOrDefault(emptyList())

            val fromFacts = ThoughtDeriver.fromFacts(
                facts = facts.distinctBy { it.id },
                assistantId = assistantId,
                now = now,
                alreadyTracked = tracked,
            )
            fromFacts.forEach { stores.thoughts.note(it) }

            val milestones = runCatching { sources.milestoneDao.getAll() }.getOrDefault(emptyList())
                .map {
                    ThoughtDeriver.MilestoneRecord(
                        id = it.id,
                        title = it.title,
                        message = it.message,
                        createdAt = it.createdAt,
                        dismissedAt = it.dismissedAt,
                    )
                }
            ThoughtDeriver.fromMilestones(milestones, assistantId, now, tracked)
                .forEach { stores.thoughts.note(it) }

            if (fromFacts.isNotEmpty()) {
                Logger.i(TAG, "从记忆推导出 ${fromFacts.size} 条新牵挂")
            }
        }.onFailure { e ->
            Logger.w(TAG, "牵挂推导失败（不影响巡检）: ${e.message}")
        }
    }
}
