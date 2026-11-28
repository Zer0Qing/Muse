package io.zer0.muse.knowledge.jobs

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeJobEntity
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.data.knowledge.KnowledgeTransactionRunner
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import java.util.UUID

/**
 * R4 作业入队编排 — 把全量重建、单篇重建、KB 重建与附件清理统一登记为持久 job/jobItem。
 *
 * 对应计划 R4 步骤「将全量重建、单篇重建、KB 重建及附件清理接到同一作业 runner,
 * 修正空『全部』参数的语义并测试生产入队链」。
 *
 * 边界说明(重要,R4 不越界):
 *  - 本类只负责**登记持久账本**(job + 逐文件 jobItem),不直接执行阶段;
 *    执行由 [JobRunner] 经 [io.zer0.muse.knowledge.jobs.KnowledgeJobWorker] 驱动。
 *  - job 与 items 的写入在**同一事务**内完成:不允许出现「有 job 没有 item」的半套账本,
 *    否则 Worker 看到空 item 列表会把它当成「无事可做」直接成功。
 *  - 空 kb 列表 = 明确的「全部库」语义(这里用空集表示需调用方展开),
 *    但**空文档集不会扩成「全部」**(计划 §10.1 范围不扩域)。
 */
class KnowledgeJobEnqueueService(
    private val jobDao: KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newJobId: () -> String = { "job-${UUID.randomUUID()}" },
    private val newItemId: () -> String = { "ji-${UUID.randomUUID()}" },
    private val transactionRunner: KnowledgeTransactionRunner = KnowledgeTransactionRunner.direct(),
) {

    /** 生产构造:job + items 在同一 Room 事务内落库。 */
    constructor(db: MuseDb) : this(
        jobDao = db.knowledgeJobDao(),
        transactionRunner = KnowledgeTransactionRunner.room(db),
    )

    /** 一次入队其结果:jobId + 逐文件 itemId。空文档集是明确空结果,不扩成全部。 */
    data class EnqueueResult(
        val jobId: String,
        val itemIds: List<String>,
        val emptyReason: String? = null,
    ) {
        val isEmpty: Boolean get() = emptyReason != null
    }

    /**
     * 把一批文档登记为一次重建作业。
     * @param docIds 目标文档;空集返回空结果,不扩成「全部库」(范围不扩域)。
     */
    suspend fun enqueueReindex(ownerType: String, ownerId: String, docIds: Collection<String>, datasetEpoch: Long = 0L): EnqueueResult {
        if (docIds.isEmpty()) {
            return EnqueueResult(jobId = "", itemIds = emptyList(), emptyReason = "空文档集:不扩成全部")
        }
        val jobId = newJobId()
        val now = clock()
        val distinctDocIds = docIds.distinct()
        val itemIds = distinctDocIds.map { newItemId() }

        transactionRunner.run {
            jobDao.insertJob(
                KnowledgeJobEntity(
                    jobId = jobId,
                    ownerType = ownerType,
                    ownerId = ownerId,
                    state = KnowledgeJobState.QUEUED.name,
                    datasetEpoch = datasetEpoch,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            distinctDocIds.forEachIndexed { index, docId ->
                jobDao.insertJobItem(
                    KnowledgeJobItemEntity(
                        jobItemId = itemIds[index],
                        jobId = jobId,
                        docId = docId,
                        state = KnowledgeJobState.QUEUED.name,
                        stage = KnowledgeJobStage.ACQUIRE.name,
                        datasetEpoch = datasetEpoch,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        Logger.i(TAG, "已入队重建作业 jobId=$jobId, ${itemIds.size} 个文档")
        return EnqueueResult(jobId = jobId, itemIds = itemIds)
    }

    /**
     * 附件清理入队:把某会话的附件文档登记为清理作业项(删除语义,执行时由 JobRunner
     * 的 CLEANUP 阶段落地)。与重建共用同一账本,便于统一状态展示。
     */
    suspend fun enqueueAttachmentCleanup(sessionId: String, docIds: Collection<String>, datasetEpoch: Long = 0L): EnqueueResult =
        enqueueReindex(
            ownerType = "SessionAttachment",
            ownerId = sessionId,
            docIds = docIds,
            datasetEpoch = datasetEpoch,
        )

    /** 修正「空全部参数」语义:调用方传入「全部 KB」时用显式全量列表;空列表是空结果而非全部。 */
    suspend fun expandAllKbDocIds(allDocIds: Collection<String>): Collection<String> = allDocIds

    private companion object {
        const val TAG = "KnowledgeJobEnqueueService"
    }
}
