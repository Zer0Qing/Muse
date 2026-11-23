package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Bot 事件账本 —— 中枢内部的"发生了什么"。
 *
 * ## 为什么事件要先落盘再唤醒
 *
 * 子任务完成的那一刻，进程可能随时被杀。如果只是往内存队列里丢一条，
 * 用户就会看到"任务做完了但主 Bot 从没提过"。事件先落库，消费方按 eventId
 * 记 receipt，业务提交幂等 —— 这样"至少一次投递"是可接受的。
 *
 * ## 为什么不能 consume 后立刻删
 *
 * 删掉再尝试落盘，中间那一步失败就永久丢了。保留期必须晚于
 * "消费成功 + 任务终态 + 审计留存"三者中最晚的那个。
 */
@Serializable
@Entity(
    tableName = "bot_events",
    indices = [
        Index(value = ["botId", "sequence"]),
        Index(value = ["taskId"]),
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["consumedAt"]),
    ],
)
data class BotEventEntity(
    @PrimaryKey val eventId: String,
    @ColumnInfo(defaultValue = "") val botId: String = "",
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 关联任务；纯对话事件为空串。 */
    @ColumnInfo(defaultValue = "") val taskId: String = "",
    @ColumnInfo(defaultValue = "") val runId: String = "",
    /** 见 [BotEventKind]。 */
    @ColumnInfo(defaultValue = "") val kind: String = "",
    /** 单调递增序号，给"按顺序重放"用。 */
    @ColumnInfo(defaultValue = "0") val sequence: Long = 0L,
    /** 载荷引用（大内容不进事件表）。 */
    @ColumnInfo(defaultValue = "") val payloadRef: String = "",
    /** 幂等键：同一件事重复产生事件时只留一条。 */
    @ColumnInfo(defaultValue = "") val dedupeKey: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    /** 0 表示尚未消费。 */
    @ColumnInfo(defaultValue = "0") val consumedAt: Long = 0L,
    @ColumnInfo(defaultValue = "") val leaseOwner: String = "",
    @ColumnInfo(defaultValue = "0") val leaseUntil: Long = 0L,
    @ColumnInfo(defaultValue = "0") val fenceToken: Long = 0L,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1,
)

/** 事件类型。新增类型要同步 [BotEventKind.all]，否则"未知类型"会被静默忽略。 */
object BotEventKind {
    const val USER_INPUT_RECEIVED = "UserInputReceived"
    const val TASK_ACCEPTED = "TaskAccepted"
    const val TASK_PROGRESSED = "TaskProgressed"
    const val TASK_RESULT_READY = "TaskResultReady"
    /**
     * 审核已明确允许发布，可以对外说话了。
     *
     * 与 [TASK_RESULT_READY] 分开：执行完成只是"有材料了"，审核通过才是"可以说"。
     * 只用一个事件会让"跑完就公开发言"这条错误路径重新长出来。
     */
    const val TASK_PUBLICATION_READY = "TaskPublicationReady"
    const val TASK_FAILED = "TaskFailed"
    const val APPROVAL_REQUIRED = "ApprovalRequired"
    const val APPROVAL_RESOLVED = "ApprovalResolved"
    const val TASK_CANCELLED = "TaskCancelled"
    const val REVIEW_READY = "ReviewReady"
    const val RESOURCE_CHANGED = "ResourceChanged"
    const val TASK_STEERED = "TaskSteered"
    const val STEP_UPDATED = "StepUpdated"

    val all: Set<String> = setOf(
        USER_INPUT_RECEIVED,
        TASK_ACCEPTED,
        TASK_PROGRESSED,
        TASK_RESULT_READY,
        TASK_PUBLICATION_READY,
        TASK_FAILED,
        APPROVAL_REQUIRED,
        APPROVAL_RESOLVED,
        TASK_CANCELLED,
        REVIEW_READY,
        RESOURCE_CHANGED,
        TASK_STEERED,
        STEP_UPDATED,
    )
}