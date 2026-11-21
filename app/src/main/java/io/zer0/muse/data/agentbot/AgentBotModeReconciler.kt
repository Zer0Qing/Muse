package io.zer0.muse.data.agentbot

import io.zer0.common.Logger

/**
 * 关闭再启用中枢模式时的对账（V22）。
 *
 * ## 为什么需要它
 *
 * 中枢是编译期开关，但"关掉"不等于"什么都没发生"：关之前可能还有
 * QUEUED 任务、挂着审批、未消费的事件。用户下次打开时如果直接继续，
 * 会看到一堆"来历不明"的东西；如果直接清空，用户交代过的活就没了。
 *
 * 这里做的是**对账**：把每一类残留归到"可继续 / 需用户确认 / 已失效"三档，
 * 而不是一刀切。
 *
 * ## 与重启对账的区别
 *
 * [BotTaskScheduler.reconcileAfterRestart] 处理的是"进程被杀"，
 * 那时任务**本该还在跑**。这里处理的是"用户主动关掉功能"，
 * 语义完全不同 —— 关掉期间没人跑，所以 RUNNING 的 run 一定是死租约。
 */
class AgentBotModeReconciler(
    private val repository: AgentBotRepository,
) {

    /** 对账结果，供界面提示用户。 */
    data class Report(
        /** 可以继续执行的任务数。 */
        val resumable: Int = 0,
        /** 需要用户决定的任务数（结果不确定）。 */
        val needsUser: Int = 0,
        /** 已失效的审批数。 */
        val expiredApprovals: Int = 0,
        /** 未消费事件数（会被重新处理）。 */
        val pendingEvents: Int = 0,
    ) {
        val hasAnything: Boolean
            get() = resumable > 0 || needsUser > 0 || expiredApprovals > 0 || pendingEvents > 0
    }

    /**
     * 重新启用时对账。
     *
     * 三步：
     *  1. 死租约的 run 标 UNKNOWN（关掉期间没人跑，它们的产出不可信）；
     *  2. 挂着的审批标过期（关掉期间用户看不到，期限早过了）；
     *  3. 数一遍剩余可继续的任务与未消费事件。
     */
    suspend fun reconcileOnEnable(botId: String): Report {
        // 1) 死租约。
        var needsUser = 0
        repository.staleRuns().forEach { run ->
            Logger.w("ModeReconciler", "关闭期间的死租约，标 UNKNOWN: ${run.runId}")
            repository.closeRun(run.runId, TaskRunStatus.UNKNOWN, "mode_disabled")
            repository.transition(run.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
            needsUser++
        }

        // 2) 挂着的审批：用户关掉功能期间看不到卡片，期限已过。
        //    用 EXPIRED 而不是 DENIED —— 用户没拒绝，只是没机会看见。
        val expired = repository.expireApprovals()

        // 3) 数剩余。
        val tasks = repository.listTasks(botId)
        val resumable = tasks.count {
            it.status == BotTaskStatus.QUEUED || it.status == BotTaskStatus.WAITING_RESOURCE
        }
        val pendingEvents = repository.pendingEvents(botId, Int.MAX_VALUE).size

        val report = Report(
            resumable = resumable,
            needsUser = needsUser + tasks.count { it.status == BotTaskStatus.NEEDS_USER },
            expiredApprovals = expired,
            pendingEvents = pendingEvents,
        )
        Logger.i(
            "ModeReconciler",
            "对账完成: 可继续=${report.resumable} 需确认=${report.needsUser} " +
                "过期审批=${report.expiredApprovals} 待处理事件=${report.pendingEvents}",
        )
        return report
    }
}
