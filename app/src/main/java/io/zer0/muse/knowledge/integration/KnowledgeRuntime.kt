package io.zer0.muse.knowledge.integration

import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeV2Dao
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.backup.KnowledgePortableBackupService
import io.zer0.muse.knowledge.domain.KnowledgePolicyResolver
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.ResolvedPolicy
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.jobs.DefaultKnowledgeJobProcessor
import io.zer0.muse.knowledge.jobs.JobRunner
import io.zer0.muse.knowledge.jobs.KnowledgeJobEnqueueService
import io.zer0.muse.knowledge.jobs.KnowledgeWriteGate
import io.zer0.muse.knowledge.retrieval.ActiveGenerationResolver
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
import io.zer0.muse.knowledge.storage.KnowledgeManagementFacade
import io.zer0.muse.rag.RagConfig
import java.io.File

/**
 * R4/R9 运行时装配 — 把分散的合同实现组合成可用的本地知识引擎。
 *
 * 边界:
 *  - 本类只做**对象装配**,不注册进 AppKoinModule(真实读写切换仍关闭至 R9 切换批准,
 *    `WriteCutoverGuardTest` 保持绿);R9 切换时由装配层显式创建本对象。
 *  - 所有组件都来自既有合同:作业账本/GenerationPublisher/R3 分块/R6 检索/R7 证据/R9 备份。
 */
class KnowledgeRuntime private constructor(
    val db: MuseDb,
    val jobDao: KnowledgeJobDao,
    val v2Dao: KnowledgeV2Dao,
    val publisher: GenerationPublisher,
    val jobRunner: JobRunner,
    val jobEnqueue: KnowledgeJobEnqueueService,
    val processor: DefaultKnowledgeJobProcessor,
    val retriever: KnowledgeRetriever,
    val assembler: EvidenceAssembler,
    val contributor: KnowledgeContextContributor,
    val lifecycle: KnowledgeLifecycleService,
    val management: KnowledgeManagementFacade,
    val portableBackup: KnowledgePortableBackupService,
    /** 原件内容寻址存储(blobsDirectory 下的 store 目录)。 */
    val contentStore: io.zer0.muse.knowledge.storage.KnowledgeContentStore,
    val policyProvider: suspend (TrustedGenerationContext) -> ResolvedPolicy,
) {

    companion object {
        /**
         * 构建运行时。
         *
         * @param blobsDirectory 内容寻址原件目录(生产应位于 app 私有 filesDir 下)。
         * @param ragConfigProvider 全局 RAG 配置来源;默认 RagConfig(),由调用方注入真实设置。
         */
        fun create(
            db: MuseDb,
            blobsDirectory: File,
            ragConfigProvider: suspend () -> RagConfig = { RagConfig() },
        ): KnowledgeRuntime {
            val jobDao = db.knowledgeJobDao()
            val v2Dao = db.knowledgeV2Dao()
            val folderDao = db.knowledgeFolderV2Dao()
            val publisher = GenerationPublisher(jobDao)
            val jobRunner = JobRunner(
                jobDao = jobDao,
                writeAllowed = { KnowledgeWriteGate.isWriteAllowed() },
                latestEpochProvider = { v2Dao.getLatestEpoch()?.epochNo },
            )
            val jobEnqueue = KnowledgeJobEnqueueService(jobDao)
            val processor = DefaultKnowledgeJobProcessor(
                v2Dao = v2Dao,
                publisher = publisher,
            )

            val generationResolver = ActiveGenerationResolver { scope ->
                val docIds: List<String> = when (scope) {
                    is KnowledgeScope.Resolved -> scope.docIds.toList()
                    is KnowledgeScope.AllUserLibraries -> v2Dao.getAllDocs().map { it.docId }
                    else -> emptyList()
                }
                docIds.mapNotNull { docId -> jobDao.getActiveGeneration(docId) }
            }
            val retriever = KnowledgeRetriever(
                lexicalRetriever = V2LexicalRetriever(jobDao),
                generationResolver = generationResolver,
            )
            val assembler = EvidenceAssembler()
            val policyProvider: suspend (TrustedGenerationContext) -> ResolvedPolicy = {
                // 当前按全局配置解析;助手/会话覆盖由后续装配层扩展(合同已支持)。
                KnowledgePolicyResolver.resolve(ragConfigProvider())
            }
            val contributor = KnowledgeContextContributor(
                retriever = retriever,
                assembler = assembler,
                policyProvider = policyProvider,
            )
            val lifecycle = KnowledgeLifecycleService(
                folderDao = folderDao,
                v2Dao = v2Dao,
                jobDao = jobDao,
            )
            return KnowledgeRuntime(
                db = db,
                jobDao = jobDao,
                v2Dao = v2Dao,
                publisher = publisher,
                jobRunner = jobRunner,
                jobEnqueue = jobEnqueue,
                processor = processor,
                retriever = retriever,
                assembler = assembler,
                contributor = contributor,
                lifecycle = lifecycle,
                management = KnowledgeManagementFacade(lifecycle),
                portableBackup = KnowledgePortableBackupService(v2Dao, folderDao, jobDao),
                contentStore = io.zer0.muse.knowledge.storage.KnowledgeContentStore(
                    java.io.File(blobsDirectory, "store"),
                ),
                policyProvider = policyProvider,
            )
        }
    }
}
