package io.zer0.muse.knowledge.domain

/**
 * R4 持久作业合同 — 设计 §8「状态分两轴」与 §8.2「领取、取消与重试」的类型化落地。
 *
 * 两轴分开记录(计划 R4):
 *  - [KnowledgeJobState]  作业本身的执行状态(排队/运行/等待输入/成功/带警告成功/失败/取消中/已取消);
 *  - [KnowledgeAvailability] 文档可用性(仅存储/关键词可查/混合可查/部分可查/更新失败用旧版/来源不可用)。
 *
 * 关键不变式(由 GenerationPublishTest / KnowledgeJobRecoveryTest 钉住):
 *  - 作业终态与可用性分开,「没有向量」不等于「导入失败」,「词法可查」不代表「提取完整」;
 *  - 每次状态推进持久化 [KnowledgeJobStage] 与 checkpoint,不能把各阶段百分比硬拼成完成时间;
 *  - 取消意图先落库([KnowledgeJobState.CANCEL_REQUESTED]),清理使用有界、不可取消收尾。
 */

/** 作业执行状态(R4 §8.1)。 */
enum class KnowledgeJobState {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    SUCCEEDED,
    SUCCEEDED_WITH_WARNINGS,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,

    ;

    /** 是否终态:终态不再被任何后续写入推进(取消终态不得被 Completed 回写覆盖)。 */
    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == SUCCEEDED_WITH_WARNINGS ||
            this == FAILED || this == CANCELLED
}

/** 作业阶段(R4 §8.1;顺序即推进顺序,checkpoint 记当前阶段)。 */
enum class KnowledgeJobStage {
    ACQUIRE,
    PARSE,
    OCR,
    CHUNK,
    LEXICAL,
    EMBED,
    VALIDATE,
    PUBLISH,
    CLEANUP,

    ;

    fun isValidSuccessorOf(previous: KnowledgeJobStage): Boolean {
        val all = entries
        return all.indexOf(this) >= all.indexOf(previous)
    }
}

/** 文档可用性(R4 §8.1;与作业状态正交)。 */
enum class KnowledgeAvailability {
    STORED_ONLY,
    KEYWORD_READY,
    HYBRID_READY,
    PARTIAL_READY,
    UPDATE_FAILED_USING_PREVIOUS,
    SOURCE_UNAVAILABLE,
}

/**
 * 单调 fencing token — 防双 Worker 与旧任务覆盖新编辑(计划 R4 §8.2)。
 *
 * [datasetEpoch] 是数据集级隔离,由恢复协调日志分配且不随恢复载荷回滚;
 * [generationFence] 是文档级并发序,同文档每次新代 +1。两个都必须单调——
 * 旧 Worker / 旧响应 / 旧 outbox 携带的旧 fence 在提交时被拒。
 */
data class JobFence(
    val datasetEpoch: Long,
    val generationFence: Long,
)

/** 一次作业领取的租约 — 领取后必须在 [expiresAtMs] 前推进,否则可被其他 Worker 合法抢占。 */
data class JobLease(
    val jobItemId: String,
    val holderId: String,
    val acquiredAtMs: Long,
    val expiresAtMs: Long,
    val fence: JobFence,
)

/** 作业推进结果 — 类型化终态,不用 Any 或静默 null 表达(AGENTS.md §2.1)。 */
sealed interface JobStepResult {
    /** 正常推进到下一阶段,尚未终态。 */
    data class Progressed(
        val jobItemId: String,
        val stage: KnowledgeJobStage,
        val availability: KnowledgeAvailability,
    ) : JobStepResult

    /** 作业成功终态。 */
    data class Succeeded(
        val jobItemId: String,
        val availability: KnowledgeAvailability,
        val warnings: List<String> = emptyList(),
    ) : JobStepResult

    /** 作业失败终态(带具体失败阶段与可重试类别)。 */
    data class Failed(
        val jobItemId: String,
        val stage: KnowledgeJobStage,
        val reason: String,
        val retryable: Boolean,
    ) : JobStepResult

    /** 取消已生效(终态);后续不得再写 Completed。 */
    data class Cancelled(val jobItemId: String) : JobStepResult

    /** 提交时 fence/epoch 校验失败(旧任务越权);本结果不改变任何活动指针。 */
    data class Fenced(
        val jobItemId: String,
        val expected: JobFence,
        val actual: JobFence?,
    ) : JobStepResult
}

/** Generation 发布结果 — 类型化,不把校验失败伪装成成功。 */
sealed interface PublishResult {
    /** 新代已成为活动代;旧代可延迟清理。 */
    data class Published(val generationId: String, val docId: String) : PublishResult

    /** 提交时 revision/deleteEpoch/fence 已变(并发编辑或删除),旧指针不变。 */
    data class Conflict(val reason: String) : PublishResult

    /** 校验失败(计数/locator/hash/词法不可查),活动指针不变,旧代仍可查。 */
    data class ValidationFailed(val reason: String) : PublishResult
}
