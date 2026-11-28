package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一次待用户决定的授权请求。
 *
 * ## 为什么不能只靠内存里的 CompletableDeferred
 *
 * 用户可能在审批卡出现时切走、退后台、甚至杀掉进程。若审批只存在于内存，
 * 那条任务就永远等不到答复，而"用户没看见"和"用户拒绝"是两件完全不同的事。
 * 落库后：卡片可被重新读出来，过期有明确状态，拒绝可被审计。
 *
 * ## 三个时间戳为什么要分开
 *
 * createdAt = 创建；readyAt = 卡片可被读取；expiresAt = 过期。
 * "可被读取"不等于"用户已看到"，所以交互期限从 readyAt 算，
 * 而不是从 createdAt —— 否则主 Bot 一卡顿，用户的思考时间就被悄悄吃掉。
 */
@Serializable
@Entity(
    tableName = "bot_approvals",
    indices = [
        Index(value = ["botId", "status"]),
        Index(value = ["taskId"]),
        Index(value = ["expiresAt"]),
    ],
)
data class ApprovalRequestEntity(
    @PrimaryKey val approvalId: String,
    @ColumnInfo(defaultValue = "") val botId: String = "",
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    @ColumnInfo(defaultValue = "") val taskId: String = "",
    @ColumnInfo(defaultValue = "") val runId: String = "",
    @ColumnInfo(defaultValue = "") val stepId: String = "",
    @ColumnInfo(defaultValue = "1") val taskRevision: Int = 1,
    @ColumnInfo(defaultValue = "") val toolCallId: String = "",
    @ColumnInfo(defaultValue = "") val toolName: String = "",
    /** 参数引用（全量参数落在产物里，不在这里存正文）。 */
    @ColumnInfo(defaultValue = "") val argumentsRef: String = "",
    /** 参数哈希：参数一变，旧批准立即失效。 */
    @ColumnInfo(defaultValue = "") val argumentsHash: String = "",
    /** 资源版本（例如文件 hash），资源一变旧批准同样失效。 */
    @ColumnInfo(defaultValue = "") val resourceVersion: String = "",
    /** 风险等级，与 ToolPermissionResolver 同口径。 */
    @ColumnInfo(defaultValue = "") val risk: String = "",
    /** 见 [ApprovalStatus]。 */
    @ColumnInfo(defaultValue = "PENDING") val status: String = ApprovalStatus.PENDING,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val readyAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val expiresAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val resolvedAt: Long = 0L,
    /** 谁做的决定：user / system-expired / system-invalidated。 */
    @ColumnInfo(defaultValue = "") val decisionActor: String = "",
    @ColumnInfo(defaultValue = "") val decisionReason: String = "",
)

/**
 * 审批状态。
 *
 * [EXPIRED] 与 [DENIED] 必须分开：过期不是用户拒绝，把两者混为一谈会让
 * 主 Bot 汇报"你拒绝了"——而用户其实只是没看见。
 */
object ApprovalStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val DENIED = "DENIED"
    const val EXPIRED = "EXPIRED"
    const val INVALIDATED = "INVALIDATED"
    const val CANCELLED = "CANCELLED"

    /** 已结算：不可再被 CAS 改写。 */
    val SETTLED: Set<String> = setOf(APPROVED, DENIED, EXPIRED, INVALIDATED, CANCELLED)

    fun isSettled(status: String): Boolean = status in SETTLED
}
