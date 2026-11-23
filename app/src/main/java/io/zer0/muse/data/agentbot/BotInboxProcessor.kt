package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主 Bot 的收件箱处理器 —— 每 Bot 单写者的短回合。
 *
 * ## 单写者的含义
 *
 * 同一时刻只允许一个回合在提交对外输出与调度命令。不是"防止并发 bug"这种泛泛
 * 说法，而是具体的一条：如果两个回合同时对外说话，用户会看到主 Bot 自相矛盾。
 *
 * 后台任务可以并行，Reviewer 也可以独立跑 —— 它们都不持有这把锁。
 *
 * ## 回合顺序
 *
 * 用户取消/显式审批 → 用户新输入 → 需要决定的阻塞 → 审核完成/重要失败
 * → 普通进度 → 可选主动闲聊。
 *
 * 审批卡不参与这个队列：它由持久投影直接渲染，连续聊天不会阻止审批出现，
 * 也不会暗中消耗它的交互期限。
 */
class BotInboxProcessor(
    private val repository: AgentBotRepository,
) {

    /** botId → 该 Bot 的回合锁。一个 Bot 一把，互不影响。 */
    private val botLocks = mutableMapOf<String, Mutex>()

    /** botId → 最近扫描到的 sequence；失败事件不能永远挡住后续队列。 */
    private val scanCursors = mutableMapOf<String, Long>()

    private fun lockFor(botId: String): Mutex = synchronized(botLocks) {
        botLocks.getOrPut(botId) { Mutex() }
    }

    /** 一个回合的处理结果。 */
    data class TurnResult(
        /** 本轮消费掉的事件数。0 表示队列空，没什么可做。 */
        val consumed: Int,
        /** 本轮之后是否还有积压（用于决定要不要立刻再跑一轮）。 */
        val hasBacklog: Boolean,
    )

    /**
     * 跑一个短回合。
     *
     * 只处理"现在就该处理"的事件；[handler] 返回 false 表示这条暂时处理不了
     * （例如等资源），此时**不消费**它，留在队列里等下一轮。
     *
     * 绝不在这里等长任务：回合必须有界，否则"做事时还能聊天"就成了空话。
     */
    suspend fun runTurn(
        botId: String,
        maxEvents: Int = DEFAULT_MAX_EVENTS_PER_TURN,
        handler: suspend (BotEventEntity) -> Boolean,
    ): TurnResult = lockFor(botId).withLock {
        require(maxEvents > 0) { "maxEvents 必须大于 0" }
        val window = maxEvents * PRIORITY_WINDOW_FACTOR
        var cursor = scanCursors[botId] ?: 0L
        var pending = repository.pendingEventsAfter(botId, cursor, window)
        if (pending.isEmpty() && cursor > 0L) {
            cursor = 0L
            pending = repository.pendingEventsAfter(botId, cursor, window)
        }
        val ordered = pending.sortedWith(compareBy({ priorityOf(it.kind) }, { it.sequence }))
        var consumed = 0
        var scannedThrough = cursor
        for (event in ordered) {
            scannedThrough = maxOf(scannedThrough, event.sequence)
            val handled = try {
                handler(event)
            } catch (e: Exception) {
                // 单条事件处理失败不能拖垮整轮：记日志、不消费、留待下一轮或人工介入。
                Logger.w("BotInboxProcessor", "事件处理失败，保留待重试: ${event.eventId}", e)
                false
            }
            if (!handled) continue
            if (repository.markConsumed(event.eventId)) consumed++
            if (consumed >= maxEvents) break
        }
        if (pending.isEmpty()) scanCursors.remove(botId) else scanCursors[botId] = scannedThrough
        val backlog = repository.pendingEvents(botId, 1).isNotEmpty()
        TurnResult(consumed = consumed, hasBacklog = backlog)
    }

    /**
     * 事件优先级。数值越小越先处理。
     *
     * 取消与审批必须压过普通输入：用户说"停下"的时候，
     * 系统还在慢慢读上一条闲聊，是最让人失去信任的体验。
     */
    fun priorityOf(kind: String): Int = when (kind) {
        BotEventKind.APPROVAL_RESOLVED -> 0
        BotEventKind.TASK_CANCELLED -> 1
        BotEventKind.APPROVAL_REQUIRED -> 2
        BotEventKind.USER_INPUT_RECEIVED -> 3
        BotEventKind.TASK_FAILED -> 4
        BotEventKind.TASK_RESULT_READY -> 5
        // 可发布事实与结果事件同级：都代表"这件事有结论了"。
        // 排在进度之前，否则用户要先看完一串心跳才等到真正的结论。
        BotEventKind.TASK_PUBLICATION_READY -> 5
        BotEventKind.REVIEW_READY -> 6
        BotEventKind.TASK_PROGRESSED -> 7
        BotEventKind.TASK_ACCEPTED -> 8
        BotEventKind.RESOURCE_CHANGED -> 9
        // 未知类型排最后：宁可晚处理，也不能因为不认识就丢掉。
        else -> Int.MAX_VALUE
    }

    companion object {
        /** 单回合最多处理的事件数。有界是为了保证回合够短。 */
        const val DEFAULT_MAX_EVENTS_PER_TURN = 8

        /**
         * 排序窗口倍数。
         *
         * `pendingEvents` 按 sequence 取前 N 条，要在其中挑出优先级最高的 8 条，
         * N 必须比 8 大得多。4 倍意味着"最近 32 条里挑 8 条"——
         * 足够让"停下"这种高优先级事件挤进视野，又不会一次读太多。
         */
        private const val PRIORITY_WINDOW_FACTOR = 4
    }
}
