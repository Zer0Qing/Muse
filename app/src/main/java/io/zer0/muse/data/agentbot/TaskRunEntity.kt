package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一次具体执行（一个 Task 可以有多轮 attempt）。
 *
 * ## 为什么 run 与 task 分开
 *
 * 返工是常态：审核打回、约束变更、资源冲突，都会让同一件事重新跑一遍。
 * 把 attempt 揉进 task 会丢掉"上一轮跑到哪、为什么失败"，而审核恰恰要看这些。
 *
 * ## lease / fence 的作用
 *
 * 进程随时可能被杀。重新拉起后必须能判断"这个 run 是不是已经被别的 worker 接手了"，
 * 否则两个 worker 同时写同一份产物。lease 是租约，fence 是围栏令牌：
 * 旧 worker 即便醒来，它的 fence 也已经过期，写不进当前版本。
 */
@Serializable
@Entity(
    tableName = "bot_task_runs",
    indices = [
        Index(value = ["taskId", "attempt"], unique = true),
        Index(value = ["status"]),
        Index(value = ["leaseUntil"]),
    ],
)
data class TaskRunEntity(
    @PrimaryKey val runId: String,
    @ColumnInfo(defaultValue = "") val taskId: String = "",
    /** 第几次尝试，从 1 起。 */
    @ColumnInfo(defaultValue = "1") val attempt: Int = 1,
    /** 子 Agent 线程 id；尚未分配时为空串。 */
    @ColumnInfo(defaultValue = "") val threadId: String = "",
    /** 启动时对应的 task revision。迟到结果按它判 stale。 */
    @ColumnInfo(defaultValue = "1") val taskRevision: Int = 1,
    /** 见 [TaskRunStatus]。 */
    @ColumnInfo(defaultValue = "QUEUED") val status: String = TaskRunStatus.QUEUED,
    @ColumnInfo(defaultValue = "") val leaseOwner: String = "",
    @ColumnInfo(defaultValue = "0") val leaseUntil: Long = 0L,
    /** 围栏令牌，每次领取 +1。旧 worker 的令牌落后即失效。 */
    @ColumnInfo(defaultValue = "0") val fenceToken: Long = 0L,
    @ColumnInfo(defaultValue = "0") val cancelRequestedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val startedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val finishedAt: Long = 0L,
    /** 检查点引用（恢复时从哪继续）。 */
    @ColumnInfo(defaultValue = "") val checkpointRef: String = "",
    @ColumnInfo(defaultValue = "") val errorCode: String = "",
)

/** Run 的状态集合。注意 [COMPLETED] 只代表"跑完了"，不代表任务成功。 */
object TaskRunStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val WAITING_RESOURCE = "WAITING_RESOURCE"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val UNKNOWN = "UNKNOWN"

    val TERMINAL: Set<String> = setOf(COMPLETED, FAILED, CANCELLED, UNKNOWN)

    fun isTerminal(status: String): Boolean = status in TERMINAL
}

/**
 * Run 里的一个步骤。
 *
 * 记的是"这次调用做了什么、能不能重放"，而不是结果正文 —— 结果进事件与产物，
 * 这里只留审核与恢复需要的判据。
 */
@Serializable
@Entity(
    tableName = "bot_task_steps",
    indices = [
        Index(value = ["runId", "stepIndex"], unique = true),
        Index(value = ["approvalId"]),
    ],
)
data class TaskStepEntity(
    @PrimaryKey val stepId: String,
    @ColumnInfo(defaultValue = "") val runId: String = "",
    /** 步骤序号，从 0 起。 */
    @ColumnInfo(defaultValue = "0") val stepIndex: Int = 0,
    @ColumnInfo(defaultValue = "") val toolName: String = "",
    /** 参数哈希：审批绑定与"参数变了旧批准失效"都靠它。 */
    @ColumnInfo(defaultValue = "") val argumentsHash: String = "",
    /** 前置基线（例如目标文件的原 hash），发布时核验。 */
    @ColumnInfo(defaultValue = "") val baseHash: String = "",
    /** 绑定的审批 id；空表示这一步不需要审批。 */
    @ColumnInfo(defaultValue = "") val approvalId: String = "",
    /** 见 [StepSideEffect]。 */
    @ColumnInfo(defaultValue = "READ_ONLY") val sideEffect: String = StepSideEffect.READ_ONLY,
    @ColumnInfo(defaultValue = "0") val startedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val finishedAt: Long = 0L,
)

/**
 * 副作用类别 —— 决定"进程被杀后这一步能不能自动重放"。
 *
 * 这不是给用户看的标签，是恢复策略的判据：把不可盲重放的步骤当成可重试，
 * 代价是重复发消息、重复改文件。
 */
object StepSideEffect {
    /** 只读查询，随便重试。 */
    const val READ_ONLY = "READ_ONLY"

    /** 有写但幂等（覆盖写同一份内容），可安全重试。 */
    const val RETRY_SAFE = "RETRY_SAFE"

    /** 已产生外部副作用，结果未写回时只能标 UNKNOWN 让用户核验。 */
    const val NO_BLIND_REPLAY = "NO_BLIND_REPLAY"
}