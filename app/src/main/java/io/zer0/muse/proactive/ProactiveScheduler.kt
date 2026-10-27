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
                val hasWork = runCatching { cycle.execute(TRIGGER_TIMER, force = false) }
                    .onFailure { e -> Logger.w(TAG, "巡检异常: ${e.message}") }
                    .getOrNull()
                    ?.ledgerNotEmpty
                    ?: false
                delay(if (hasWork) CHECK_WITH_WORK_MS else CHECK_IDLE_MS)
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
            pending?.assistantId
                ?.takeIf { it.isNotBlank() }
                ?.let { stores.relations.grantCredit(it) }
        }.onFailure { e -> Logger.w(TAG, "反馈记录失败: ${e.message}") }
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
