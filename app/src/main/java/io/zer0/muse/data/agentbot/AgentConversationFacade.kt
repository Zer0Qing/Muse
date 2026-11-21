package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Agent 中枢的输入接纳口。
 *
 * ## 为什么要单独一层
 *
 * 普通聊天在流式期间会拒绝新输入（isStreaming 守卫）。中枢不能这样：
 * 后台任务在跑的时候，用户必须还能说话 —— 那正是这个功能存在的理由。
 * 所以接纳与执行彻底分开：这里只负责"先落盘、再 ACK"，谁来处理是下一步的事。
 *
 * ## 连发合并
 *
 * 用户连打三句"帮我看下这个""顺便""算了先别动"时，逐条触发三个回合
 * 会让主 Bot 反复改口。合并窗口内的连续输入合成一批，但**每条原文独立落盘** ——
 * 合并只影响"何时唤醒"，不影响"用户到底说了什么"。
 *
 * 取消、审批、安全提示不等待合并窗口：它们必须立刻生效。
 */
class AgentConversationFacade(
    private val repository: AgentBotRepository,
    /** 合并窗口毫秒。0 表示关闭合并（每条立即唤醒）。 */
    private val mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 接纳口串行：同一 Bot 的输入必须按到达顺序落库。 */
    private val acceptLock = Mutex()

    /** botId → 最近一批的接纳结果（合并窗口内复用）。 */
    private val lastBatch = mutableMapOf<String, AcceptedBatch>()

    /**
     * 一次接纳的结果。
     *
     * [eventId] 是唤醒 Coordinator 的凭据；[mergedCount] > 1 表示这批合并了几条。
     */
    data class AcceptedBatch(
        val batchId: String,
        val botId: String,
        val originSessionId: String,
        val texts: List<String>,
        val eventId: String,
        val acceptedAt: Long,
    ) {
        val mergedCount: Int get() = texts.size
    }

    /**
     * 接纳一条用户输入。
     *
     * 永不因为"正在生成"而拒绝 —— 这正是与普通聊天的根本区别。
     * 落盘失败时返回 null 并**保留原文**（调用方据此把内容放回输入框），
     * 而不是静默吞掉。
     */
    suspend fun acceptUserInput(
        botId: String,
        originSessionId: String,
        text: String,
        messageId: String = UUID.randomUUID().toString(),
    ): AcceptedBatch? = acceptLock.withLock {
        if (originSessionId.isBlank()) {
            // 缺归属不能兜底到"当前会话"：那会把 A 的输入写进 B。
            Logger.e("AgentConversationFacade", "拒绝接纳：缺少 originSessionId")
            return@withLock null
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withLock null

        val ts = now()
        val previous = lastBatch[botId]
        val canMerge = mergeWindowMs > 0 &&
            previous != null &&
            previous.originSessionId == originSessionId &&
            ts - previous.acceptedAt <= mergeWindowMs

        if (canMerge && previous != null) {
            // 合并：追加到同一批，事件仍是同一条 —— 不重复唤醒 Coordinator。
            val merged = previous.copy(
                texts = previous.texts + trimmed,
                acceptedAt = ts,
            )
            lastBatch[botId] = merged
            return@withLock merged
        }

        val event = repository.appendEvent(
            botId = botId,
            originSessionId = originSessionId,
            taskId = "",
            runId = "",
            kind = BotEventKind.USER_INPUT_RECEIVED,
            // 幂等键按消息 id：同一条消息重复投递只唤醒一次。
            dedupeKey = "input:$messageId",
            payloadRef = trimmed.take(MAX_INPUT_CHARS),
        )
        val batch = AcceptedBatch(
            batchId = event.eventId,
            botId = botId,
            originSessionId = originSessionId,
            texts = listOf(trimmed),
            eventId = event.eventId,
            acceptedAt = ts,
        )
        lastBatch[botId] = batch
        batch
    }

    /**
     * 取消/审批这类输入必须立刻生效，不走合并窗口。
     *
     * 返回 true 表示已落事件；调用方据此决定要不要再走模型。
     */
    suspend fun acceptImmediateCommand(
        botId: String,
        originSessionId: String,
        kind: String,
        dedupeKey: String,
        payloadRef: String = "",
    ): Boolean = acceptLock.withLock {
        if (originSessionId.isBlank()) return@withLock false
        // 立即命令打断合并：后续输入不该被合进命令之前那批。
        lastBatch.remove(botId)
        repository.appendEvent(
            botId = botId,
            originSessionId = originSessionId,
            taskId = "",
            runId = "",
            kind = kind,
            dedupeKey = dedupeKey,
            payloadRef = payloadRef,
        )
        true
    }

    /**
     * 清掉某 Bot 的合并窗口（切换 Bot / 显式结束一批时用）。
     *
     * ## 为什么也要持锁
     *
     * 审查 P2-15：`lastBatch` 是普通 `mutableMap`，`acceptUserInput` /
     * `acceptImmediateCommand` 都在 `acceptLock` 里改它，这里却在锁外删。
     * 并发下会抛 `ConcurrentModificationException`，或者读到正在被替换的批次。
     */
    suspend fun flushBatch(botId: String) {
        acceptLock.withLock { lastBatch.remove(botId) }
    }

    companion object {
        /** 连发合并窗口。500ms 是待设备体验验证的候选值，不是定论。 */
        const val DEFAULT_MERGE_WINDOW_MS = 500L

        /** 单条输入进事件 payloadRef 的字符上限。正文进消息表，事件只留够判断的片段。 */
        private const val MAX_INPUT_CHARS = 2000
    }
}
