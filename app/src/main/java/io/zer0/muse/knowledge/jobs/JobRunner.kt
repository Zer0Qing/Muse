package io.zer0.muse.knowledge.jobs

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.knowledge.domain.JobFence
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeAvailability
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import java.util.UUID

/**
 * R4 JobRunner — 持久作业领取、checkpoint、取消与恢复(设计 §8.2;计划 R4)。
 *
 * 职责边界:
 *  - 领取用**原子 CAS**(DAO.tryAcquire):只有 lease 空闲/过期且非终态才能抢到,防双 Worker;
 *  - 每次推进前检查取消意图(落库的 cancel_requested),命中则转 CANCELLED 终态;
 *  - 终态提交用**CAS**(DAO.commitTerminal):必须仍本人持有 + fence 未变 + 未终态,
 *    否则拒绝——这保证「取消/被抢占后无后续 Completed 回写」;
 *  - 阶段推进持久化 stage + checkpoint,不把各阶段拼成完成时间;
 *  - 同 item 重复 resume 幂等:已终态直接返回其记录,不重复执行。
 *
 * WorkManager 只是调度器,本类是作业事实来源;测试可直接构造并以注入的阶段执行器驱动。
 */
class JobRunner(
    private val jobDao: KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newHolderId: () -> String = { "worker-${UUID.randomUUID()}" },
    /** R9: 恢复期间暂停领取(默认始终允许;恢复协调器接管时注入门禁)。 */
    private val writeAllowed: () -> Boolean = { true },
    /** R9: 当前数据集最新 epoch(用于拒绝旧 epoch 任务提交;null = 未接线,不校验)。 */
    private val latestEpochProvider: suspend () -> Long? = { null },
) {

    private val terminalStates = listOf(
        KnowledgeJobState.SUCCEEDED.name,
        KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name,
        KnowledgeJobState.FAILED.name,
        KnowledgeJobState.CANCELLED.name,
    )

    /**
     * 一次作业项的完整执行(领取 -> 逐阶段推进 -> 终态提交)。
     *
     * [execute] 由调用方提供:给定当前持有者,执行实际阶段工作并通过 [JobExecution]
     * 回调报告阶段推进/结果。故障注入测试在 [execute] 内按需抛异常或提前返回。
     */
    suspend fun run(
        jobItemId: String,
        leaseDurationMs: Long = 5 * 60 * 1000,
        execute: suspend JobExecution.() -> JobStepResult,
    ): JobStepResult {
        val item = jobDao.getJobItem(jobItemId) ?: return missingItem(jobItemId)

        // R9: 恢复期间隔离写入 —— 新作业不领取,已领取项也不推进。
        if (!writeAllowed()) return pausedByRestore(jobItemId)

        // 幂等:已终态直接返回;已请求取消但尚未转终态则直接落 CANCELLED。
        if (item.state in terminalStates) return terminalFromRecord(item)
        if (item.cancelRequested != 0) {
            // 未领取的取消(lease_holder 为空)必须用库中真实 holder 匹配 CAS,
            // 否则 holder 不匹配导致提交失败、错报 FAILED。
            return finalizeCancelled(item, holder = item.leaseHolder)
        }

        val holder = newHolderId()
        val now = clock()
        val acquired = jobDao.tryAcquire(
            jobItemId = jobItemId,
            holder = holder,
            expiresAt = now + leaseDurationMs,
            runningState = KnowledgeJobState.RUNNING.name,
            terminalStates = terminalStates,
            nowMs = now,
        )
        // 已被别的 Worker 领走或已终态:不推进。
        if (acquired == 0) return acquireRejected(item)

        val execution = JobExecution(jobDao = jobDao, jobItemId = jobItemId, holder = holder, clock = clock)

        val result = try {
            execution.execute()
        } catch (e: Exception) {
            // 阶段执行抛异常 -> FAILED(默认可重试);但若期间收到取消,转 CANCELLED。
            Logger.w("JobRunner", "作业项执行异常(item=$jobItemId): ${e.message}", e)
            val latest = jobDao.getJobItem(jobItemId)
            if (latest?.cancelRequested == 1) {
                return finalizeCancelled(latest, holder)
            }
            JobStepResult.Failed(
                jobItemId = jobItemId,
                stage = execution.currentStage,
                reason = e.message ?: "unknown",
                retryable = true,
            )
        }

        // R9: 旧 datasetEpoch 的任务不得写回新数据集(恢复分配新 epoch 后,旧 Worker 提交被拒)。
        val currentEpoch = latestEpochProvider()
        if (currentEpoch != null && item.datasetEpoch > 0 && currentEpoch > item.datasetEpoch) {
            Logger.w(
                "JobRunner",
                "拒绝旧 epoch 任务提交(item=$jobItemId, itemEpoch=${item.datasetEpoch}, current=$currentEpoch)",
            )
            return JobStepResult.Fenced(
                jobItemId = jobItemId,
                expected = JobFence(datasetEpoch = item.datasetEpoch, generationFence = item.generationFence),
                actual = JobFence(datasetEpoch = currentEpoch, generationFence = item.generationFence),
            )
        }

        return commitResult(item, holder, result)
    }

    /** 作业项不存在(不可重试)。 */
    private fun missingItem(jobItemId: String) = JobStepResult.Failed(
        jobItemId = jobItemId,
        stage = KnowledgeJobStage.ACQUIRE,
        reason = "作业项不存在: $jobItemId",
        retryable = false,
    )

    /** 恢复期间暂停(可重试)。 */
    private fun pausedByRestore(jobItemId: String) = JobStepResult.Failed(
        jobItemId = jobItemId,
        stage = KnowledgeJobStage.ACQUIRE,
        reason = "恢复进行中,作业暂停",
        retryable = true,
    )

    /** 领取被拒:已终态返回终态,否则 Fenced。 */
    private suspend fun acquireRejected(item: KnowledgeJobItemEntity): JobStepResult {
        val current = jobDao.getJobItem(item.jobItemId)
        return if (current != null && current.state in terminalStates) {
            terminalFromRecord(current)
        } else {
            JobStepResult.Fenced(
                jobItemId = item.jobItemId,
                expected = JobFence(datasetEpoch = item.datasetEpoch, generationFence = item.generationFence),
                actual = current?.let { JobFence(it.datasetEpoch, it.generationFence) },
            )
        }
    }

    /** 请求取消:先落库(取消意图),返回后由 run/下一次 resume 观察到并转终态。 */
    suspend fun requestCancel(jobItemId: String): Boolean {
        val rows = jobDao.requestCancel(
            jobItemId = jobItemId,
            cancelRequestedState = KnowledgeJobState.CANCEL_REQUESTED.name,
            terminalStates = terminalStates,
            updatedAt = clock(),
        )
        return rows > 0
    }

    /**
     * 终态提交 CAS:拒绝旧任务/被抢占/已终态的写入。
     * @return 最终有效结果(提交成功即本次结果,提交失败则以库中现状为准)。
     */
    private suspend fun commitResult(
        item: KnowledgeJobItemEntity,
        holder: String,
        result: JobStepResult,
    ): JobStepResult {
        // 取消优先:若执行期间收到取消意图,即使 execute 返回 Succeeded 也必须落 CANCELLED,
        // 不得让 Completed 覆盖取消(计划 R4 验收「取消无后续 Completed 回写」)。
        if (result !is JobStepResult.Failed) {
            val latest = jobDao.getJobItem(item.jobItemId)
            if (latest?.cancelRequested == 1) {
                return finalizeCancelled(latest, holder)
            }
        }
        val (terminalState, stage, reason) = when (result) {
            is JobStepResult.Succeeded -> Triple(
                if (result.warnings.isEmpty()) KnowledgeJobState.SUCCEEDED.name
                else KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name,
                KnowledgeJobStage.CLEANUP,
                result.warnings.joinToString("; "),
            )
            is JobStepResult.Failed -> Triple(
                KnowledgeJobState.FAILED.name,
                result.stage,
                result.reason,
            )
            is JobStepResult.Cancelled -> Triple(
                KnowledgeJobState.CANCELLED.name,
                KnowledgeJobStage.CLEANUP,
                "cancelled",
            )
            is JobStepResult.Fenced -> return result // 不会走到此处(领取已拒)
            is JobStepResult.Progressed -> return result // 未终态,无需提交
        }
        val committed = jobDao.commitTerminal(
            jobItemId = item.jobItemId,
            holder = holder,
            expectedFence = item.generationFence,
            terminalState = terminalState,
            stage = stage.name,
            reason = reason,
            terminalStates = terminalStates,
            updatedAt = clock(),
        )
        if (committed == 0) {
            // CAS 失败:已终态/被抢占/fence 已变 -> 以库中现状为准,拒绝本次写入。
            val current = jobDao.getJobItem(item.jobItemId)
            return if (current != null) terminalFromRecord(current)
            else JobStepResult.Fenced(
                jobItemId = item.jobItemId,
                expected = JobFence(item.datasetEpoch, item.generationFence),
                actual = null,
            )
        }
        return result
    }

    private suspend fun finalizeCancelled(
        item: KnowledgeJobItemEntity,
        holder: String,
    ): JobStepResult {
        val committed = jobDao.commitTerminal(
            jobItemId = item.jobItemId,
            holder = holder,
            expectedFence = item.generationFence,
            terminalState = KnowledgeJobState.CANCELLED.name,
            stage = KnowledgeJobStage.CLEANUP.name,
            reason = "cancelled",
            terminalStates = terminalStates,
            updatedAt = clock(),
        )
        return if (committed == 1) JobStepResult.Cancelled(item.jobItemId)
        else terminalFromRecord(jobDao.getJobItem(item.jobItemId) ?: item)
    }

    private fun terminalFromRecord(item: KnowledgeJobItemEntity): JobStepResult = when (item.state) {
        KnowledgeJobState.SUCCEEDED.name, KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name ->
            JobStepResult.Succeeded(
                jobItemId = item.jobItemId,
                availability = KnowledgeAvailability.KEYWORD_READY,
                warnings = if (item.resultReason.isBlank()) emptyList() else listOf(item.resultReason),
            )
        KnowledgeJobState.CANCELLED.name -> JobStepResult.Cancelled(item.jobItemId)
        else -> JobStepResult.Failed(
            jobItemId = item.jobItemId,
            stage = KnowledgeJobStage.valueOf(item.stage),
            reason = item.resultReason.ifBlank { "terminal" },
            retryable = item.state == KnowledgeJobState.FAILED.name,
        )
    }

    /**
     * 阶段执行上下文 — 提供给 [run] 的 execute 回调:推进阶段(持久化 checkpoint)+ 读当前取消意图。
     */
    class JobExecution internal constructor(
        private val jobDao: KnowledgeJobDao,
        val jobItemId: String,
        private val holder: String,
        private val clock: () -> Long,
    ) {
        var currentStage: KnowledgeJobStage = KnowledgeJobStage.ACQUIRE
            private set

        /** 推进到下一阶段并落 checkpoint(仅本持有者)。 */
        suspend fun advance(stage: KnowledgeJobStage, checkpoint: String = "") {
            this.currentStage = stage
            jobDao.updateProgress(
                jobItemId = jobItemId,
                holder = holder,
                stage = stage.name,
                checkpoint = checkpoint,
                updatedAt = clock(),
            )
        }

        /** 查询是否已被请求取消(执行器应在阶段边界检查)。 */
        suspend fun isCancelRequested(): Boolean =
            jobDao.getJobItem(jobItemId)?.cancelRequested == 1
    }
}
