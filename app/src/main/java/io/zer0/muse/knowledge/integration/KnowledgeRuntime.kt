package io.zer0.muse.knowledge.integration

import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeTransactionRunner
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
import io.zer0.muse.knowledge.retrieval.EvidenceVisibility
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import io.zer0.muse.knowledge.storage.KnowledgeArtifactCleaner
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
import io.zer0.muse.knowledge.storage.KnowledgeManagementFacade
import io.zer0.muse.rag.RagConfig
import java.io.File

/**
 * R4/R9 运行时装配 — 把分散的合同实现组合成可用的本地知识引擎。
 *
 * **生产接入仍然关闭**:本类不注册进 AppKoinModule,`WriteCutoverGuardTest` 保持绿,
 * 真实用户读写路径依旧走旧链路。修复 V2 自身缺陷(事务发布、派生物清理、备份边界、
 * 证据可见性)**不代表**读写切换;切换必须单独批准并由装配层显式创建本对象。
 *
 * 边界:
 *  - 本类只做**对象装配**,不持有业务逻辑;
 *  - 所有组件都来自既有合同:作业账本/GenerationPublisher/R3 分块/R6 检索/R7 证据/R9 备份;
 *  - 写路径组件统一注入 Room 事务边界,读路径组件不写库。
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

    /**
     * 刷新文档可见性索引。
     *
     * 证据装配是同步的,而归属查询是挂起的:因此由装配层在检索前调用本方法把
     * docId → 可见性 刷进索引。**未刷新的文档一律按未知处理并被保守抑制**,
     * 不会因为"没查过"而把内部资料注入上下文。
     *
     * @param docIds 限定刷新的文档集;null = 全库(全库模式才这么做,避免每次查询扫全表)。
     */
    suspend fun refreshVisibilityIndex(docIds: Set<String>? = null) {
        val values = if (docIds == null) {
            v2Dao.getAllDocs().associate { doc -> doc.docId to classifyOwnerType(doc.ownerType) }
        } else {
            docIds.mapNotNull { docId ->
                v2Dao.getDoc(docId)?.let { doc -> doc.docId to classifyOwnerType(doc.ownerType) }
            }.toMap()
        }
        assembler.visibilityIndex.replaceAll(values)
    }

    /** 测试与装配层读取当前判定(便于断言保守过滤生效)。 */
    fun visibilityOf(docId: String): EvidenceVisibility = assembler.visibilityIndex.visibilityOf(docId)

    /**
     * 入口级便捷方法:先刷新可见性索引再准备证据。
     *
     * 直接调 [contributor] 而不刷新索引时,所有文档都会被当作「无法判定」并被保守抑制 ——
     * 这是有意为之的失败安全方向,但会让检索看起来"总是无资料"。入口必须走本方法。
     */
    suspend fun prepareEvidence(
        context: TrustedGenerationContext,
        query: String,
        budgetTokens: Int,
        requestId: String,
    ): ContributionResult {
        // 已解析范围只刷该范围的文档;全库模式才扫全表。
        val scopeDocIds = (context.allowedScope as? KnowledgeScope.Resolved)?.docIds
        refreshVisibilityIndex(scopeDocIds)
        return contributor.prepareEvidence(context, query, budgetTokens, requestId)
    }

    companion object {
        /**
         * 构建运行时。
         *
         * @param blobsDirectory 内容寻址原件目录(生产应位于 app 私有 filesDir 下)。
         * @param ragConfigProvider 全局 RAG 配置来源;默认 RagConfig(),由调用方注入真实设置。
         * @param artifactCleaner 删除文档时的外部派生物清理端口(blob/引用快照);缺省不支持。
         */
        fun create(
            db: MuseDb,
            blobsDirectory: File,
            ragConfigProvider: suspend () -> RagConfig = { RagConfig() },
            artifactCleaner: KnowledgeArtifactCleaner = KnowledgeArtifactCleaner.unsupported(),
        ): KnowledgeRuntime {
            val transactionRunner = KnowledgeTransactionRunner.room(db)
            val jobDao = db.knowledgeJobDao()
            val v2Dao = db.knowledgeV2Dao()
            val folderDao = db.knowledgeFolderV2Dao()
            val publisher = GenerationPublisher(jobDao, transactionRunner)
            val jobRunner = JobRunner(
                jobDao = jobDao,
                writeAllowed = { KnowledgeWriteGate.isWriteAllowed() },
                latestEpochProvider = { v2Dao.getLatestEpoch()?.epochNo },
            )
            val jobEnqueue = KnowledgeJobEnqueueService(jobDao, transactionRunner = transactionRunner)
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
            // 可见性索引由 [refreshVisibilityIndex] 填充;未填充时装配层一律不注入。
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
                artifactCleaner = artifactCleaner,
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
                portableBackup = KnowledgePortableBackupService(
                    v2Dao = v2Dao,
                    folderDao = folderDao,
                    jobDao = jobDao,
                    transactionRunner = transactionRunner,
                ),
                contentStore = io.zer0.muse.knowledge.storage.KnowledgeContentStore(
                    java.io.File(blobsDirectory, "store"),
                ),
                policyProvider = policyProvider,
            )
        }

        /**
         * 归属 → 可见性。只有明确认识的归属才给确定结论,其余一律 UNKNOWN(不注入)。
         */
        fun classifyOwnerType(ownerType: String): EvidenceVisibility = when (ownerType) {
            "UserLibrary", "SessionAttachment" -> EvidenceVisibility.USER_VISIBLE
            "BuiltinHelp" -> EvidenceVisibility.INTERNAL
            else -> EvidenceVisibility.UNKNOWN
        }
    }
}
