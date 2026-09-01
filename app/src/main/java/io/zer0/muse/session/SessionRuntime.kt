package io.zer0.muse.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * M1.7: 单个 turn 的检查点/恢复状态。
 *
 * 生命周期(主路径):
 * ```
 * NOT_STARTED -> GENERATING -> WAITING_TOOL -> GENERATING -> COMPLETED
 *                  |  ^             |
 *                  |  └-- WAITING_APPROVAL(审批后回 GENERATING)
 *                  +-> CANCELLED / FAILED
 * ```
 * - [GENERATING]:LLM 流式请求进行中(含工具轮之间的续接请求)。
 * - [WAITING_TOOL]:工具执行在途(onToolStart -> onToolFinish)。
 * - [WAITING_APPROVAL]:高风险工具等待用户审批(requestToolApproval 挂起点)。
 * - [RESUMABLE]:进程被杀后由 generation_checkpoints 恢复的 turn 标记。
 * - 终态([COMPLETED]/[CANCELLED]/[FAILED])只记录,同一 turn 内不再迁移;
 *   下一个 [SessionRuntime.beginTurn] 开启新 turn 时显式重置。
 */
enum class TurnPhase {
    NOT_STARTED,
    GENERATING,
    WAITING_TOOL,
    WAITING_APPROVAL,
    RESUMABLE,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * M1.1: 单个会话的稳定运行时对象。
 *
 * 由 [ConversationSessionManager] 按 sessionId 创建并持有,同一会话多次进入
 * (切换/前后台/ViewModel 重建)复用同一实例;引用计数归零且无生成任务时
 * 由 idle reaper 清理。
 *
 * 职责边界:
 *  - 只承载"正在发生"的运行时状态(turn 检查点、生成 Job、取消标志、引用计数)。
 *  - 消息/回合/parts 的持久化事实源仍在 Room(ConversationEvent/Turn/ToolRound/MessagePart),
 *    本类不缓存消息内容,避免出现第二套事实源。
 *  - 前后台切换只改变 UI 订阅,不取消 [generationJob];ViewModel 销毁不等于 turn 取消。
 *
 * 线程安全:phase 走 StateFlow,其余字段用原子类型/@Volatile,可在任意线程读写。
 */
class SessionRuntime(val sessionId: String) {

    private val _phase = MutableStateFlow(TurnPhase.NOT_STARTED)

    /** 当前 turn 检查点状态(UI 可观察,用于恢复提示/调试面板)。 */
    val phase: StateFlow<TurnPhase> = _phase.asStateFlow()

    /** 当前 turn id(与 StreamRunState.turnId / conversation_turns.turn_id 同源)。 */
    val currentTurnId = AtomicReference<String?>(null)

    /** 当前会话的生成任务;完成/替换时由 [ConversationSessionManager] 维护。 */
    @Volatile
    var generationJob: Job? = null
        internal set

    /** 用户请求取消(stop),生成闭包可读取以区分"用户停止"与"异常失败"。 */
    @Volatile
    var cancelRequested: Boolean = false
        private set

    /** 活跃持有者计数(acquire +1 / release -1),归零触发 idle 清理调度。 */
    val refCount = AtomicInteger(0)

    /** 最近活动时间(acquire/生成心跳刷新),供 idle 判定兜底。 */
    val lastActiveAt = AtomicLong(System.currentTimeMillis())

    /** 刷新活跃时间(生成心跳/工具回调调用)。 */
    fun touch() {
        lastActiveAt.set(System.currentTimeMillis())
    }

    /**
     * 开启新 turn:记录 turn id、清除旧取消标志并进入 [TurnPhase.GENERATING]。
     * 新 turn 显式覆盖上一 turn 的任意状态(含终态)—— 终态粘滞只在同一 turn 内生效。
     */
    fun beginTurn(turnId: String) {
        currentTurnId.set(turnId)
        cancelRequested = false
        touch()
        _phase.value = TurnPhase.GENERATING
    }

    /** 工具执行开始(onToolStart):GENERATING -> WAITING_TOOL。 */
    fun markWaitingTool(turnId: String? = null) {
        if (matchesTurn(turnId)) transition(TurnPhase.WAITING_TOOL)
    }

    /** 等待用户审批(requestToolApproval 挂起):GENERATING/WAITING_TOOL -> WAITING_APPROVAL。 */
    fun markWaitingApproval(turnId: String? = null) {
        if (!matchesTurn(turnId)) return
        if (_phase.value == TurnPhase.GENERATING || _phase.value == TurnPhase.WAITING_TOOL) {
            transition(TurnPhase.WAITING_APPROVAL)
        }
    }

    /**
     * 等待点恢复回到 GENERATING:工具执行结束(onToolFinish)或审批完成
     * (deferred.await 返回)后调用;仅当当前处于对应等待态时生效。
     */
    fun markResumed(turnId: String? = null) {
        val waiting = _phase.value == TurnPhase.WAITING_TOOL || _phase.value == TurnPhase.WAITING_APPROVAL
        if (matchesTurn(turnId) && waiting) {
            transition(TurnPhase.GENERATING)
        }
    }

    /** 进程恢复标记:残留 checkpoint 的 turn 标记为可恢复,并记录恢复的 turn id。 */
    fun markResumable(turnId: String? = null) {
        if (turnId != null && currentTurnId.get() == null) {
            currentTurnId.set(turnId)
        }
        if (matchesTurn(turnId)) transition(TurnPhase.RESUMABLE)
    }

    /** turn 终态落账:正常完成 [TurnPhase.COMPLETED] / 用户停止 [TurnPhase.CANCELLED] / 异常 [TurnPhase.FAILED]。 */
    fun markFinished(phase: TurnPhase, turnId: String? = null) {
        if (turnId != null && currentTurnId.get() != null && currentTurnId.get() != turnId) {
            io.zer0.common.Logger.w(
                TAG,
                "turn id 不匹配,忽略终态 $phase: runtime=${currentTurnId.get()} request=$turnId session=$sessionId",
            )
            return
        }
        transition(phase)
    }

    /** 请求取消生成(stop 路径);幂等。 */
    internal fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 状态迁移(唯一写入口)。终态在当前 turn 内不再变更;turn id 不匹配的
     * 迁移只记日志不抛异常 —— 检查点是观测/恢复辅助,不允许因状态簿记错误
     * 打断正在进行的生成。
     */
    private fun transition(target: TurnPhase) {
        val current = _phase.value
        if (current == target) return
        if (current in TERMINAL_PHASES) {
            io.zer0.common.Logger.w(
                TAG,
                "turn 已终态($current),忽略迁移到 $target session=$sessionId turn=${currentTurnId.get()}",
            )
            return
        }
        _phase.value = target
        touch()
    }

    /** turn id 匹配判定:null(调用方未知)或与当前 turn 一致时放行。 */
    private fun matchesTurn(turnId: String?): Boolean =
        turnId == null || currentTurnId.get() == null || currentTurnId.get() == turnId

    companion object {
        private const val TAG = "SessionRuntime"

        private val TERMINAL_PHASES = setOf(TurnPhase.COMPLETED, TurnPhase.CANCELLED, TurnPhase.FAILED)
    }
}
