package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeEntity
import io.zer0.muse.data.proactive.ThoughtDeriver
import io.zer0.muse.data.session.SessionEntity
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

        /**
         * 送进生成上下文的字符总预算。
         *
         * 条数上限和单条截断都管不住总量：40 条 × 单条 600 字能撜到两万多字，
         * 全塞进提示词既费钱又会把真正重要的最后几句淡化。
         * 超出时从**最旧**的一条开始丢，最近几轮对话必须留住 ——
         * 主动消息接的是「刚才那句」，接不上就干脆别说。
         */
        const val CONTEXT_CHAR_BUDGET = 6000

        /** 日志里把「距最后活动」折算成分钟的除数。 */
        private const val MINUTE_MS = 60_000L
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
        val budgeted = applyCharBudget(recent)
        val lastUserAt = budgeted.lastOrNull { it.role == MessageRole.USER }?.createdAt ?: 0L
        return SessionContext(sessionId, budgeted, lastUserAt, recentProactiveTexts(all, assistantId))
    }

    /**
     * 按总字符预算裁掉最旧的素材。
     *
     * 从最新一条往回装，装不下就停：丢的永远是最旧的，不是最新的。
     * 最新一条无条件保留 —— 它一条就超预算时宁可略微超出，
     * 也不能让模型拿到一份「你们还没聊过什么」的空素材。
     */
    private fun applyCharBudget(messages: List<UIMessage>): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var total = 0
        var keepFrom = messages.size
        for (index in messages.indices.reversed()) {
            val next = total + messages[index].content.length
            // keepFrom < messages.size 的意思是「最新一条已经装进去了」：
            // 只有它才算例外，其余消息一律按预算卡。
            if (next > CONTEXT_CHAR_BUDGET && keepFrom < messages.size) break
            total = next
            keepFrom = index
        }
        val kept = messages.subList(keepFrom, messages.size).toList()
        val dropped = messages.size - kept.size
        if (dropped > 0) {
            Logger.i(
                TAG,
                "素材总量超预算 | budget=${CONTEXT_CHAR_BUDGET}字 | 保留=${kept.size}条/${total}字 | " +
                    "丢弃=${dropped}条(最旧)",
            )
        } else {
            Logger.d(TAG, "素材总量 | budget=${CONTEXT_CHAR_BUDGET}字 | 保留=${kept.size}条/${total}字")
        }
        return kept
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

    /** 主动消息会话的来源。排查「消息到底发去了哪个房间」时先看这个字段。 */
    enum class SessionSource(val wireName: String) {
        /** 该助手最近有用户发言的会话。 */
        RECENT_ACTIVE("recent_active"),

        /** 用户当前正在看的会话(属于该助手)。 */
        VIEWED("viewed"),

        /** 兜底新建的 Agent 会话。 */
        NEW_AGENT("new_agent"),
    }

    /** 一次落点选择的结果:写哪个会话、为什么选了它、它的最后活动时间。 */
    data class SessionPick(
        val sessionId: String,
        val source: SessionSource,
        val lastActivityAt: Long,
    )

    /**
     * 定位主动消息要写进哪个会话(只关心会话 id)。
     *
     * 具体选择过程见 [resolveSessionPick]。
     */
    suspend fun resolveSession(assistant: AssistantEntity): String? =
        resolveSessionPick(assistant)?.sessionId

    /**
     * 定位主动消息的落点,并返回选中原因。
     *
     * 目标行为:在哪儿聊完,就由哪儿发。按优先级取第一个可用:
     *  1. 该助手最近有用户发言的会话(排除条件写在 SQL 里,见 SessionDao)
     *  2. 用户当前正在看的会话(需属于该助手,且同样非删除/非归档/非小手机)
     *  3. 兜底新建 Agent 会话
     *
     * 每一步都单独包 runCatching:依赖没就绪时是降级到下一级,而不是让整轮巡检崩掉。
     * 三级全空返回 null,由上层当作「没有可用会话」跳过本轮。
     */
    suspend fun resolveSessionPick(assistant: AssistantEntity): SessionPick? {
        val now = System.currentTimeMillis()

        // 1) 用户最后说话的地方
        val recent = runCatching {
            runtime.sessions.getRecentActiveByAssistant(assistant.id)
        }.getOrNull()
        if (recent != null) {
            return logPick(assistant, SessionPick(recent.id, SessionSource.RECENT_ACTIVE, recent.updatedAt), now)
        }

        // 2) 用户正看着的会话。第 1 级只认「有用户发言」的房间,
        //    刚打开还没来得及说话时会漏到这一级,那时候写进去正是用户想看到的地方。
        val viewed = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        val viewedSession = viewed
            ?.let { id -> runCatching { runtime.sessions.getSessionById(id) }.getOrNull() }
            ?.takeIf { it.isWritableForProactive(assistant.id) }
        if (viewedSession != null) {
            return logPick(assistant, SessionPick(viewedSession.id, SessionSource.VIEWED, viewedSession.updatedAt), now)
        }

        // 3) 兜底:该助手一个可用会话都没有,建一个新的 Agent 会话
        val created = runCatching {
            runtime.sessions.createAgentSession(assistant.id)
        }.getOrNull() ?: return null
        return logPick(assistant, SessionPick(created, SessionSource.NEW_AGENT, now), now)
    }

    /** 主动消息能否写进该会话:属于该助手、未删除、未归档。 */
    private fun SessionEntity.isWritableForProactive(assistantId: String): Boolean =
        this.assistantId == assistantId && deletedAt == null && !archived

    private fun logPick(assistant: AssistantEntity, pick: SessionPick, now: Long): SessionPick {
        val idleMinutes = ((now - pick.lastActivityAt) / MINUTE_MS).coerceAtLeast(0L)
        Logger.i(
            TAG,
            "主动消息会话选择 | assistantId=${assistant.id} | sessionId=${pick.sessionId} | " +
                "source=${pick.source.wireName} | 距最后活动=${idleMinutes}分钟",
        )
        return pick
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
     *
     * v17: 已出场(分数低于衰减阈值)的记忆不参与推导 —— 这里推导出来的牵挂会变成
     * 主动消息的上下文,让出场的记忆从这道口子进去等于出场没做。
     */
    suspend fun deriveThoughts(assistantId: String, now: Long) {
        runCatching {
            val tracked = stores.thoughts.getOpenSourceRefs(assistantId)
            val scope = if (assistantId == "default") "main" else assistantId
            val facts = runCatching { sources.factStore.getByScope(scope, includeSuppressed = false) }.getOrDefault(emptyList()) +
                runCatching { sources.factStore.getByScope("main", includeSuppressed = false) }.getOrDefault(emptyList())

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
