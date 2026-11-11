package io.zer0.muse.knowledge.jobs

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.data.knowledge.KnowledgeV2Dao
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeAvailability
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.PublishResult
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.ParsedBlock
import io.zer0.muse.knowledge.ingest.StructuredChunker
import io.zer0.muse.knowledge.storage.KnowledgeContentStore

/**
 * R4 默认作业处理器 — 把 V2 文档的当前完整 revision 重建为词法 generation 并发布。
 *
 * 适用:已完成摄取(完整 revision 已落库)的文档重建/重分词;PDF/OCR 的真实解析在
 * R3 解析器适配层完成后再进入本处理器,本类不重复解析文件、不读 preview。
 *
 * 失败语义:
 *  - 文档/版本不存在 → Failed(不可重试,需用户处理);
 *  - 校验失败 → abandon 新代、保持旧活动代可查,返回 Failed;
 *  - 发布冲突 → Failed(不覆盖活动指针)。
 */
class DefaultKnowledgeJobProcessor(
    private val v2Dao: KnowledgeV2Dao,
    private val publisher: GenerationPublisher,
    private val chunker: StructuredChunker = StructuredChunker(chunkSize = 500, chunkOverlap = 50),
) : KnowledgeJobProcessor {

    override suspend fun process(
        jobItem: KnowledgeJobItemEntity,
        execution: JobRunner.JobExecution,
    ): JobStepResult {
        val doc = v2Dao.getDoc(jobItem.docId)
            ?: return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.ACQUIRE,
                reason = "文档不存在: ${jobItem.docId}",
                retryable = false,
            )
        val revision = v2Dao.getRevision(doc.currentRevisionId)
            ?: return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.ACQUIRE,
                reason = "当前 revision 不存在: ${doc.currentRevisionId}",
                retryable = false,
            )
        if (revision.fullContent.isBlank()) {
            return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.PARSE,
                reason = "文档没有完整正文(仅 preview/ChunkOnly),不能重建词法索引",
                retryable = false,
            )
        }

        execution.advance(KnowledgeJobStage.PARSE, "chars=${revision.fullContent.length}")
        if (execution.isCancelRequested()) return JobStepResult.Cancelled(jobItem.jobItemId)

        execution.advance(KnowledgeJobStage.CHUNK)
        val chunks = chunker.chunkFromBlocks(
            listOf(
                ParsedBlock(
                    text = revision.fullContent,
                    page = 0,
                    section = 1,
                    kind = "paragraph",
                ),
            ),
        )
        if (chunks.isEmpty()) {
            return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.CHUNK,
                reason = "分块结果为空",
                retryable = false,
            )
        }

        execution.advance(KnowledgeJobStage.LEXICAL, "chunks=${chunks.size}")
        val generationId = try {
            publisher.stageGeneration(doc.docId, revision.revisionId, chunks)
        } catch (e: Exception) {
            Logger.w("DefaultKnowledgeJobProcessor", "staging 失败(doc=${doc.docId}): ${e.message}", e)
            return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.LEXICAL,
                reason = "staging 失败: ${e.message}",
                retryable = true,
            )
        }

        execution.advance(KnowledgeJobStage.VALIDATE)
        // 分块带 overlap:校验前须按 overlap 重建原文,否则拼接会把重叠段重复计入导致 hash 误报。
        val validationError = publisher.validate(generationId, revision.revisionId, revision.contentHash) { texts ->
            KnowledgeContentStore.hashOf(chunker.reconstruct(texts).toByteArray())
        }
        if (validationError != null) {
            publisher.abandon(generationId)
            return JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.VALIDATE,
                reason = validationError,
                retryable = true,
            )
        }

        execution.advance(KnowledgeJobStage.PUBLISH)
        val published = publisher.publish(generationId, revision.revisionId)
        return when (published) {
            is PublishResult.Published -> JobStepResult.Succeeded(
                jobItemId = jobItem.jobItemId,
                availability = KnowledgeAvailability.KEYWORD_READY,
            )
            is PublishResult.Conflict -> JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.PUBLISH,
                reason = "发布冲突: ${published.reason}",
                retryable = false,
            )
            is PublishResult.ValidationFailed -> JobStepResult.Failed(
                jobItemId = jobItem.jobItemId,
                stage = KnowledgeJobStage.PUBLISH,
                reason = "发布校验失败: ${published.reason}",
                retryable = true,
            )
        }
    }
}
