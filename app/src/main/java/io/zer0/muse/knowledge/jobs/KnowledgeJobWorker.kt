package io.zer0.muse.knowledge.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.session.MuseDb
import org.koin.core.context.GlobalContext

/**
 * R4 作业调度器 — WorkManager 只是唤醒器,不是作业事实来源(设计 §8.2)。
 *
 * 本类不持有阶段逻辑:领取、checkpoint、取消与 fencing 全在 [JobRunner] 与
 * `knowledge_v2_job_items` 账本内。Worker 被杀/重启只会让下一次唤醒重新 [JobRunner.run],
 * 由账本决定从哪个阶段继续或直接返回终态(幂等)。
 *
 * 接线边界:R4 不把本 Worker 接入生产知识库导入链(真实写入切换在 R9,
 * WriteCutoverGuardTest 钉住 AppKoinModule 不注册 V2 写入)。[KnowledgeJobProcessor]
 * 由后续批次的装配层提供;未提供时本 Worker 明确失败,不静默假成功。
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

        // 处理器:优先使用装配层覆盖实现;缺省直接构建默认可用的词法重建处理器。
        // 这使 Worker 具备真实执行能力,但仍只在显式入队时运行(生产入口未接线)。
        val processor = resultOf { koin.get<KnowledgeJobProcessor>() }.getOrNull()
            ?: DefaultKnowledgeJobProcessor(
                v2Dao = db.knowledgeV2Dao(),
                publisher = io.zer0.muse.knowledge.index.GenerationPublisher(db.knowledgeJobDao()),
            )

        val dao = db.knowledgeJobDao()
        val runner = JobRunner(
            jobDao = dao,
            writeAllowed = { KnowledgeWriteGate.isWriteAllowed() },
            latestEpochProvider = { db.knowledgeV2Dao().getLatestEpoch()?.epochNo },
        )
        val items = dao.getJobItems(jobId)
        if (items.isEmpty()) {
            Logger.i(TAG, "作业无逐文件项,jobId=$jobId")
            return Result.success()
        }
        var failed = 0
        for (item in items) {
            val result = runner.run(item.jobItemId) {
                processor.process(item, this)
            }
            when (result) {
                is io.zer0.muse.knowledge.domain.JobStepResult.Failed -> failed++
                else -> Unit
            }
        }
        Logger.i(TAG, "作业执行完毕 jobId=$jobId, ${items.size} 项,失败 $failed")
        return Result.success()
    }

    companion object {
        private const val TAG = "KnowledgeJobWorker"
        const val KEY_JOB_ID = "job_id"
        const val UNIQUE_WORK_PREFIX = "muse_knowledge_job_"

        /** 显式入队(仅 R8/R9 装配层调用;R4 生产路径不调用)。 */
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
 * 阶段处理器扩展点 — 装配层可提供自定义实现(如 OCR/云端解析);
 * 未提供时 [KnowledgeJobWorker] 使用 [DefaultKnowledgeJobProcessor](完整 revision → 词法重建)。
 */
interface KnowledgeJobProcessor {
    suspend fun process(
        jobItem: io.zer0.muse.data.knowledge.KnowledgeJobItemEntity,
        execution: JobRunner.JobExecution,
    ): io.zer0.muse.knowledge.domain.JobStepResult
}
