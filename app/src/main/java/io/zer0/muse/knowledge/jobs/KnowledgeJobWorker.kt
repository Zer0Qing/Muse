package io.zer0.muse.knowledge.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import org.koin.core.context.GlobalContext

/**
 * R4 作业调度器 — WorkManager 只是唤醒器,不是作业事实来源(设计 §8.2)。
 *
 * 本类不持有阶段逻辑:领取、checkpoint、取消与 fencing 全在 [JobRunner] 与
 * `knowledge_v2_job_items` 账本内。Worker 被杀/重启只会让下一次唤醒重新 [JobRunner.run],
 * 由账本决定从哪个阶段继续或直接返回终态(幂等)。
 *
 * 终态合同(逻辑在 [KnowledgeJobFinalizer],可脱离 WorkManager 单测):
 *  - 每个 item 的结果由 [JobRunner] 以 CAS 写入 `knowledge_v2_job_items`;
 *  - 全部 item 处理完后按**账本**状态写 job 自身终态:
 *    全成功 → SUCCEEDED,有警告 → SUCCEEDED_WITH_WARNINGS,有失败 → FAILED,有取消 → CANCELLED;
 *  - 存在**本轮新增的可重试**失败且未达尝试上限时,把这些 item 退回 QUEUED 并返回
 *    [Result.retry];不可重试失败、已达上限或回排失败时直接落 FAILED 终态,避免无限重试。
 *
 * 接线边界:本 Worker 未接入生产知识库导入链(真实写入切换仍关闭)。[KnowledgeJobProcessor]
 * 由装配层提供;未提供时使用 [DefaultKnowledgeJobProcessor](revision → 词法重建)。
 */
class KnowledgeJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
        if (jobId.isNullOrBlank()) {
            Logger.w(TAG, "缺少 jobId,拒绝执行")
            return Result.failure()
        }
        val koin = resultOf { GlobalContext.get() }.getOrNull()
            ?: return Result.retry().also { Logger.w(TAG, "Koin 未初始化,稍后重试") }
        val db = resultOf { koin.get<MuseDb>() }.getOrNull()
            ?: return Result.retry().also { Logger.w(TAG, "MuseDb 解析失败,稍后重试") }

        val dao = db.knowledgeJobDao()
        val finalizer = KnowledgeJobFinalizer(dao, maxAttempts = MAX_ATTEMPTS)
        val items = dao.getJobItems(jobId)
        if (items.isEmpty()) {
            // 入队事务保证不会出现「有 job 无 item」;真出现说明账本已损坏,如实报失败。
            Logger.w(TAG, "作业无逐文件项,jobId=$jobId")
            finalizer.markFailed(jobId, "作业无逐文件项(账本损坏)")
            return Result.failure()
        }

        val processor = resultOf { koin.get<KnowledgeJobProcessor>() }.getOrNull()
            ?: DefaultKnowledgeJobProcessor(
                v2Dao = db.knowledgeV2Dao(),
                publisher = io.zer0.muse.knowledge.index.GenerationPublisher(db),
            )
        val runner = JobRunner(
            jobDao = dao,
            writeAllowed = { KnowledgeWriteGate.isWriteAllowed() },
            latestEpochProvider = { db.knowledgeV2Dao().getLatestEpoch()?.epochNo },
        )

        // 本轮开始前就已终态的 item:它们的失败不是这次执行产生的新故障,不计入重试判据。
        val alreadyTerminal = items
            .filter { item -> isTerminalState(item.state) }
            .mapTo(mutableSetOf()) { it.jobItemId }

        dao.updateJobState(jobId, KnowledgeJobState.RUNNING.name, System.currentTimeMillis())
        val outcomes = items.map { item ->
            item.jobItemId to runner.run(item.jobItemId) { processor.process(item, this) }
        }

        val decision = finalizer.settle(
            jobId = jobId,
            outcomes = outcomes,
            previouslyTerminal = alreadyTerminal,
            runAttempt = runAttemptCount,
        )
        Logger.i(TAG, "作业执行完毕 jobId=$jobId, ${items.size} 项,结论=$decision")
        return when (decision) {
            is KnowledgeJobFinalizer.Decision.Retry -> Result.retry()
            is KnowledgeJobFinalizer.Decision.Done ->
                if (decision.state == KnowledgeJobState.FAILED) Result.failure() else Result.success()
        }
    }

    /**
     * 账本里的 state 字符串是否终态。未知字符串不是终态(保守:宁可重跑也不当成已完成)。
     */
    private fun isTerminalState(state: String): Boolean {
        val parsed = KnowledgeJobState.entries.firstOrNull { it.name == state } ?: return false
        return parsed.isTerminal
    }

    companion object {
        private const val TAG = "KnowledgeJobWorker"

        /** 尝试上限:超过后不再 retry,把可重试失败落成 FAILED 终态。 */
        internal const val MAX_ATTEMPTS = 3

        const val KEY_JOB_ID = "job_id"
        const val UNIQUE_WORK_PREFIX = "muse_knowledge_job_"

        /** 显式入队(仅装配层调用;生产路径不调用)。 */
        fun enqueue(context: Context, jobId: String) {
            val request = OneTimeWorkRequestBuilder<KnowledgeJobWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_WORK_PREFIX$jobId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
            Logger.i(TAG, "已入队知识作业 jobId=$jobId")
        }
    }
}

/**
 * 作业终态结算 — 把「item 结果 → job 终态 / 是否重试」的判定从 WorkManager 里拆出来,
 * 使其可以对着真实 DAO 单测,也让重试判据只有一处实现。
 *
 * 判据:
 *  - **账本是事实来源**:job 终态按 `knowledge_v2_job_items` 的实际状态汇总;
 *    本次返回结果只用来识别「本轮新产生的可重试失败」;
 *  - 有本轮新增的可重试失败且未达尝试上限 → 把这些 item 退回 QUEUED 并返回 [Decision.Retry];
 *    否则它们会卡在 FAILED 终态,重试只是空转;
 *  - 不可重试失败、已达尝试上限,或回排失败(账本已被其他路径改写) → 直接落 FAILED 终态,
 *    避免无限重试。
 */
internal class KnowledgeJobFinalizer(
    private val jobDao: KnowledgeJobDao,
    private val maxAttempts: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Decision {
        /** job 保持 RUNNING,由 WorkManager 退避后再次唤醒。 */
        data object Retry : Decision

        /** job 已落终态。 */
        data class Done(val state: KnowledgeJobState) : Decision
    }

    /** item 状态汇总 — job 终态由「最坏的那个 item」决定。 */
    private data class Summary(
        val succeeded: Int,
        val warned: Int,
        val failed: Int,
        val cancelled: Int,
        val unterminated: Int,
    ) {
        val terminalState: KnowledgeJobState
            get() = when {
                failed > 0 -> KnowledgeJobState.FAILED
                cancelled > 0 -> KnowledgeJobState.CANCELLED
                warned > 0 -> KnowledgeJobState.SUCCEEDED_WITH_WARNINGS
                succeeded > 0 && unterminated == 0 -> KnowledgeJobState.SUCCEEDED
                // 仍有 item 没落终态(本轮写入被 fence/取消拒绝):不冒充成功。
                else -> KnowledgeJobState.FAILED
            }
    }

    suspend fun settle(
        jobId: String,
        outcomes: List<Pair<String, JobStepResult>>,
        previouslyTerminal: Set<String>,
        runAttempt: Int,
    ): Decision {
        val retryableItemIds = outcomes
            .filter { (itemId, result) ->
                result is JobStepResult.Failed && result.retryable && itemId !in previouslyTerminal
            }
            .map { it.first }

        if (retryableItemIds.isNotEmpty() && runAttempt + 1 < maxAttempts) {
            var requeued = 0
            for (itemId in retryableItemIds) {
                requeued += jobDao.requeueFailedItem(
                    jobItemId = itemId,
                    failedState = KnowledgeJobState.FAILED.name,
                    queuedState = KnowledgeJobState.QUEUED.name,
                    updatedAt = clock(),
                )
            }
            if (requeued == retryableItemIds.size) {
                jobDao.updateJobState(jobId, KnowledgeJobState.RUNNING.name, clock())
                return Decision.Retry
            }
            // 回排被拒:账本已被其他路径改写,本次重试无意义,按失败收尾。
            Logger.w(
                "KnowledgeJobFinalizer",
                "重试回排未全部生效(jobId=$jobId, $requeued/${retryableItemIds.size}),落失败终态",
            )
        }

        val summary = summarize(jobDao.getJobItems(jobId).map { it.state })
        val state = when {
            retryableItemIds.isNotEmpty() -> KnowledgeJobState.FAILED
            else -> summary.terminalState
        }
        writeTerminal(jobId, state)
        return Decision.Done(state)
    }

    /** 账本损坏等无法进入正常结算的场合:直接落 FAILED,不留在 RUNNING 里假装在跑。 */
    suspend fun markFailed(jobId: String, reason: String) {
        Logger.w("KnowledgeJobFinalizer", "作业直接失败(jobId=$jobId): $reason")
        writeTerminal(jobId, KnowledgeJobState.FAILED)
    }

    private suspend fun writeTerminal(jobId: String, state: KnowledgeJobState) {
        jobDao.updateJobState(jobId, state.name, clock())
    }

    private fun summarize(states: List<String>): Summary {
        var succeeded = 0
        var warned = 0
        var failed = 0
        var cancelled = 0
        var unterminated = 0
        states.forEach { state ->
            when (state) {
                KnowledgeJobState.SUCCEEDED.name -> succeeded++
                KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name -> warned++
                KnowledgeJobState.FAILED.name -> failed++
                KnowledgeJobState.CANCELLED.name -> cancelled++
                else -> unterminated++
            }
        }
        return Summary(succeeded, warned, failed, cancelled, unterminated)
    }
}

/**
 * 阶段处理器扩展点 — 装配层可提供自定义实现(如 OCR/云端解析);
 * 未提供时 [KnowledgeJobWorker] 使用 [DefaultKnowledgeJobProcessor](完整 revision → 词法重建)。
 */
interface KnowledgeJobProcessor {
    suspend fun process(
        jobItem: io.zer0.muse.data.knowledge.KnowledgeJobItemEntity,
        execution: JobRunner.JobExecution,
    ): io.zer0.muse.knowledge.domain.JobStepResult
}
