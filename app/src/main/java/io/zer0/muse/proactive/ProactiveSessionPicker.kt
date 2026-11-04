package io.zer0.muse.proactive

import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.proactive.ProactiveContextLoader.SessionPick
import io.zer0.muse.proactive.ProactiveContextLoader.SessionSource
import kotlinx.coroutines.flow.first

/**
 * 「这条主动消息该写进哪个房间」的策略链。
 *
 * 从 `ProactiveContextLoader` 里拆出来的（那个类此前有 21 个函数，装配材料与选路策略混在一起）。
 * 拆的理由不是"函数多"，而是这两件事的**变化原因不同**：
 * 取材料改的是数据源，选落点改的是产品策略（谁优先、静音怎么处理、要不要新建 Agent 页）。
 *
 * 类型 `SessionPick` / `SessionSource` 仍声明在 [ProactiveContextLoader] 里：
 * 它们是对外契约（`ProactiveManualTestRoute` 与多处测试按
 * `ProactiveContextLoader.SessionPick` 引用），挪走会波及调用方。
 *
 * 每一步都单独包 `runCatching`：依赖没就绪时是**降级到下一级**，而不是让整轮巡检崩掉。
 */
class ProactiveSessionPicker(private val runtime: ProactiveRuntime) {

    private companion object {
        /** 日志里把「距最后活动」折算成分钟的除数。 */
        private const val MINUTE_MS = 60_000L
    }

    /**
     * 定位主动消息的落点,并返回选中原因。
     *
     * 目标行为:在哪儿聊完,就由哪儿发。按优先级取第一个可用:
     *  1. 复用或新建该助手的 Agent 会话（日常问候默认归属 Agent）
     *  2. 若用户显式允许普通对话，再考虑最近活跃/当前查看的普通会话
     *  3. 普通对话被单会话静音时跳过；Agent 静音时也不得绕回普通对话
     *
     * 四级全空返回 null,由上层当作「没有可用会话」跳过本轮。
     */
    suspend fun resolveSessionPick(assistant: AssistantEntity): SessionPick? {
        val now = System.currentTimeMillis()
        val config = runCatching { runtime.settings.proactiveMessageConfigFlow.first() }.getOrDefault(
            io.zer0.muse.data.ProactiveMessageConfig(),
        )
        val pick =
            (if (config.agentSessionEnabled) pickExistingAgent(assistant) else null)
                ?: (if (config.allowPlainChatSession) pickPlainSession(assistant) else null)
                ?: (if (config.agentSessionEnabled) createAgentSession(assistant) else null)
        return pick?.let { logPick(assistant, it, now) }
    }

    /**
     * Manual "test send" is a diagnostic path. It must not borrow a normal task
     * chat even when plain-chat delivery is enabled in settings.
     */
    suspend fun resolveManualTestRoute(assistant: AssistantEntity): SessionPick? {
        val config =
            runCatching { runtime.settings.proactiveMessageConfigFlow.first() }
                .getOrDefault(proactiveConfigOnReadFailure())
        return ProactiveManualTestRoute.resolve(
            config = config,
            existingAgentSession = { pickExistingAgent(assistant) },
            createAgentSession = {
                createAgentSession(assistant)
                    ?: error("无法创建 Agent 会话")
            },
        )
    }

    /** 普通对话落点必须显式允许，且当前会话未被静音。 */
    private suspend fun pickPlainSession(assistant: AssistantEntity): SessionPick? =
        pickRecentChat(assistant)
            ?: pickRecentAny(assistant, agentAllowed = false)
            ?: pickViewed(assistant, agentAllowed = false)

    private suspend fun isMuted(sessionId: String): Boolean =
        runCatching { runtime.proactiveSessionPreferences.isMuted(sessionId) }.getOrDefault(false)

    /** 1) 用户最后说话的**普通**会话（Agent Tab 不算：那是独立界面，在那里说话不代表消息该出现在 Agent 页）。 */
    private suspend fun pickRecentChat(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentChatByAssistant(assistant.id)
        }.getOrNull() ?: return null
        if (session.isAgentSession || isMuted(session.id)) return null
        return SessionPick(session.id, SessionSource.RECENT_ACTIVE, session.updatedAt)
    }

    /** 1.5) 用户只在 Agent Tab 里聊过 —— 写回那个 Agent 会话，而不是新建（新建会让 Agent 页越堆越多）。 */
    private suspend fun pickRecentAny(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentActiveByAssistant(assistant.id)
        }.getOrNull() ?: return null
        if ((!agentAllowed && session.isAgentSession) || isMuted(session.id)) return null
        return SessionPick(session.id, SessionSource.RECENT_ANY, session.updatedAt)
    }

    /** 2) 用户正看着的会话（刚打开还没发言时会漏到这一级，写进去正是用户想看到的地方）。 */
    private suspend fun pickViewed(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val viewed = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        val session = viewed
            ?.let { id -> runCatching { runtime.sessions.getSessionById(id) }.getOrNull() }
            ?.takeIf { it.isWritableForProactive(assistant.id) }
            ?.takeIf { agentAllowed || !it.isAgentSession }
            ?.takeIf { !isMuted(it.id) }
            ?: return null
        return SessionPick(session.id, SessionSource.VIEWED, session.updatedAt)
    }

    /** 3) 复用该助手已有的 Agent 会话（用户主动清空过才需要新建）。 */
    private suspend fun pickExistingAgent(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentAgentByAssistant(assistant.id, 1).firstOrNull()
        }.getOrNull()
            ?.takeIf { it.deletedAt == null && !it.archived }
            ?.takeIf { !isMuted(it.id) }
            ?: return null
        return SessionPick(session.id, SessionSource.EXISTING_AGENT, session.updatedAt)
    }

    /** 4) 最后兜底：一个可用会话都没有，建一个新的 Agent 会话。 */
    private suspend fun createAgentSession(assistant: AssistantEntity): SessionPick? {
        val created = runCatching {
            runtime.sessions.createAgentSession(assistant.id)
        }.getOrNull() ?: return null
        return SessionPick(created, SessionSource.NEW_AGENT, System.currentTimeMillis())
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
}

/** 与 `ProactiveContextLoader` 用同一个 tag：日志排查时「选路」和「装配」要能一起 grep 到。 */
private const val TAG = "ProactiveContext"
