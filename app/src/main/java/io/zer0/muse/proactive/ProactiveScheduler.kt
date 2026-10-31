package io.zer0.muse.proactive

import io.zer0.common.Logger
import io.zer0.muse.data.proactive.OutcomeState
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主动消息调度器（重建版）。
 *
 * 与旧 [io.zer0.muse.schedule.ProactiveMessageRunner] 的根本区别是**主时钟换了**：
 * 旧实现按「距上次 X 分钟」轮询，新实现按「账上有没有到点的事」驱动。
 * 进程内没有固定 tick，每轮跑完自己算出下次该什么时候看，然后 delay 到那个点。
 *
 * 这个类只负责生命周期与对外入口；一轮具体怎么跑在 [ProactiveCycleRunner] 里。
 */
class ProactiveScheduler(
    private val stores: ProactiveStores,
    private val cycle: ProactiveCycleRunner,
    private val appScope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ProactiveSched"

        /** 账上有事时的复查间隔。 */
        private const val CHECK_WITH_WORK_MS = 20 * 60 * 1000L

        /** 账上没事时的复查间隔。更闲一点，反正也没话说。 */
        private const val CHECK_IDLE_MS = 60 * 60 * 1000L

        /**
         * 本轮被别的触发源占着时的重试间隔。
         *
         * 只用来「把这一轮补上」，不是调度节奏的一部分：等前一轮跑完再看一次即可，
         * 那时它会自己按账本重新算出该睡多久。
         */
        private const val CHECK_RETRY_MS = 5 * 60 * 1000L

        const val TRIGGER_STARTUP = "startup"
        const val TRIGGER_TIMER = "timer"
        const val TRIGGER_WORKER = "worker"
        const val TRIGGER_RESUME = "resume"
        const val TRIGGER_MANUAL = "manual"
    }

    private var job: Job? = null

    /** 最近一次巡检的结论，供设置页「测试」按钮如实反馈。 */
    @Volatile
    var lastOutcome: String? = null
        private set

    /**
     * 是否已经有一轮巡检在跑。
     *
     * 对外暴露给 Worker 做一次廉价的预判：它被系统唤醒时若发现进程内
     * 正好有一轮在跑，就没必要再解析一次依赖、读一次配置。真正的互斥
     * 仍然在 [ProactiveCycleRunner.execute] 里 —— 这里只是省掉一次无谓的唤醒。
     */
    val isCycleInFlight: Boolean get() = cycle.isInFlight

    // ══════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 启动进程内轮询。
     *
     * 不是固定 tick：每轮结束按账本状态算下一次的时间。
     * 账上有事就 20 分钟看一次，没事就 1 小时 —— 反正没事的时候看也没用。
     *
     * 异常不向外抛：这是后台循环，抛出去会静默杀死整个 job，
     * 而「主动消息不发了」这种故障没人会主动报上来。
     */
    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "主动消息调度已启动")
            while (isActive) {
                // 走 runCycle 而不是 cycle.execute：四路触发源必须落在同一个入口上，
                // 否则周期路径既拿不到 lastOutcome，也容易在下次改动时被漏掉互斥。
                val outcome = runCatching { runCycle(TRIGGER_TIMER, force = false) }
                    .onFailure { e -> Logger.w(TAG, "巡检异常: ${e.message}") }
                    .getOrNull()
                // 这一轮被别的触发源占着没跑成，账本状态无从得知 ——
                // 按「没事」睡一小时等于白白错过一次机会，等一小会儿再看。
                delay(
                    when {
                        outcome == null -> CHECK_IDLE_MS
                        outcome.alreadyRunning -> CHECK_RETRY_MS
                        outcome.ledgerNotEmpty -> CHECK_WITH_WORK_MS
                        else -> CHECK_IDLE_MS
                    },
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "主动消息调度已停止")
    }

    // ══════════════════════════════════════════════════════════════
    // 对外入口
    // ══════════════════════════════════════════════════════════════

    /** 跑一轮（Worker / 回前台 / 设置页测试都走这里）。 */
    suspend fun runCycle(trigger: String, force: Boolean = false): ProactiveCycleRunner.Outcome {
        val outcome = cycle.execute(trigger, force)
        lastOutcome = outcome.detail
        return outcome
    }

    /**
     * 用户主动发消息时调一次。
     *
     * 做两件事：检查这条是不是在回应最近的主动消息（回写 REPLIED），
     * 以及回补额度（他来找我说明关系在走近）。
     *
     * 全程吞异常：这是聊天发送路径上的旁路，记一笔反馈失败
     * 不该影响用户把话发出去。
     */
    suspend fun onUserSpoke(sessionId: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val pending = stores.outcomes.findPendingInSession(sessionId)
            if (pending != null) {
                stores.outcomes.resolve(pending.id, OutcomeState.REPLIED, now)
                Logger.i(TAG, "用户回复了主动消息，亲密度更新")
            }
        }.onFailure { e -> Logger.w(TAG, "反馈记录失败: ${e.message}") }
    }

    /**
     * 用户打开了会话：把该会话里最近一条还没反应的主动消息记为已读。
     *
     * 只看最近一条，不回头扫历史：用户打开会话这个动作只能说明「这一条他看到了」，
     * 而把更早那些一起标成 OPENED，等于凭空造出一堆「被点开过」的正反馈。
     *
     * 通知入口、小手机、主聊天全部收敛到会话切换这一条路径上，
     * 所以调用点只需要在那里接一次。
     */
    suspend fun onSessionOpened(sessionId: String) {
        runCatching {
            val pending = stores.outcomes.findPendingInSession(sessionId) ?: return@runCatching
            stores.outcomes.resolve(pending.id, OutcomeState.OPENED, System.currentTimeMillis())
        }.onFailure { e -> Logger.w(TAG, "记录主动消息已读失败: ${e.message}") }
    }

    /**
     * 用户点了通知上的「这次不用」。
     *
     * 与「划掉通知」不同，这是一个明确表态：这条恰好不是他想看的。
     * 只影响这一条记录（DISMISSED），不连带改设置、不暂停后面几轮。
     */
    suspend fun onProactiveDismissed(outcomeId: String) {
        runCatching {
            stores.outcomes.resolve(outcomeId, OutcomeState.DISMISSED, System.currentTimeMillis())
            Logger.i(TAG, "用户把主动消息标为「这次不用」")
        }.onFailure { e -> Logger.w(TAG, "记录「这次不用」失败: ${e.message}") }
    }

    /**
     * 用户说「最近别烦我」。
     *
     * 冷却期内四问的 Q1 直接拦截，但账本照常积累 ——
     * 事还是记着，只是不说。
     */
    suspend fun startCooldown(assistantId: String, days: Int = 7): String {
        stores.relations.startCooldown(assistantId, System.currentTimeMillis(), days)
        Logger.i(TAG, "用户请求冷却 $days 天")
        return "好，这 $days 天我不会主动找你。有事随时叫我。"
    }
}
