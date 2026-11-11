package io.zer0.muse.knowledge.domain

import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.rag.RagConfig

/**
 * R5 配置解析 — 设计 §9.1「拆分三类配置」的类型化落地。
 *
 * 三类配置分开,修改影响面不同(计划 R5 步骤 2):
 *  - [IngestProfile]:解析/OCR 能力、分块与 overlap —— 只影响**新内容**,旧内容明确提示需重处理;
 *  - [EmbeddingProfile]:提供方、实际模型语义身份、维度、归一化/距离 —— 变更需新建空间或显式迁移;
 *  - [RetrievalPolicy]:topK/阈值/融合/重排/预算 —— 只影响查询,改 topK **不得**触发 embedding 重建。
 *
 * 每个字段记录来源([PolicyFieldSource]),页面/任务/工具/聊天都读同一份 [ResolvedPolicy],
 * 废除不同调用方各自 copy RagConfig 的做法。解析不修改用户显式保存的值:
 * KB 覆盖字段为空/NULL 时继承全局(与既有 `withKbOverrides` 语义一致)。
 */
enum class PolicyFieldSource {
    /** 全局设置(RagConfig)。 */
    GLOBAL_DEFAULT,

    /** 知识库显式覆盖(knowledge_bases.*_override)。 */
    KB_OVERRIDE,

    /** 助手级覆盖。 */
    ASSISTANT_OVERRIDE,

    /** 会话级覆盖。 */
    SESSION_OVERRIDE,

    /** 本轮请求显式指定(如 mention 文档,只改选择范围,不改编码空间)。 */
    EXPLICIT_REQUEST,

    /** 内置默认值(用户从未保存)。 */
    BUILT_IN,
}

/** 摄取配置 — 修改影响新内容,不静默覆写旧内容。 */
data class IngestProfile(
    val chunkSize: Int,
    val chunkOverlap: Int,
    val markdownAware: Boolean,
    val chunkByToken: Boolean,
    /** 解析器类型名(RagConfig.ParserType.name);R5 只做配置解析,真实解析在 R3 已完成。 */
    val parserType: String,
)

/**
 * 编码配置 — 空间身份的来源。
 *
 * [localModelDigest] 是本地模型文件内容的 SHA-256:同一路径文件被替换时 digest 变化,
 * 不得被当作同一空间(计划 R5 步骤 1)。
 * [endpointFingerprint] 是不含凭据的端点指纹(host+path 的稳定摘要,绝不写入 apiKey)。
 * [remoteRevisionVerified] 标记远端是否提供了可验证的模型修订;false 时按 opaque identity 处理,
 * 端点/配置变化要重新验证,不承诺识别服务端静默升级(计划 R5 步骤 5)。
 */
data class EmbeddingProfile(
    /** RagConfig.EmbeddingSource.name。 */
    val source: String,
    val providerId: String = "",
    val model: String = "",
    val localModelPath: String = "",
    val localModelDigest: String = "",
    val endpointFingerprint: String = "",
    /** 已验证的向量维度;null = 未验证,unknown 不是可发布空间。 */
    val verifiedDimension: Int? = null,
    /** 可验证的模型修订;null = opaque(远端未提供)。 */
    val modelRevision: String? = null,
    val remoteRevisionVerified: Boolean = false,
    /** 归一化规则(如 "l2")。 */
    val normalization: String = "l2",
    /** 距离规则(如 "cosine")。 */
    val distance: String = "cosine",
) {
    /** 是否为真正的语义编码;LOCAL_KEYWORD 只是关键词能力,不能冒充语义 embedding。 */
    val isSemantic: Boolean get() = source != RagConfig.EmbeddingSource.LOCAL_KEYWORD.name
}

/** 查询策略 — 只影响检索,修改 topK 不触发重编码。 */
data class RetrievalPolicy(
    val topK: Int,
    val threshold: Float,
    val mmrLambda: Float,
    val hybridEnabled: Boolean,
    val hybridBm25Weight: Float,
    val hybridVectorWeight: Float,
    val rerankEnabled: Boolean,
    val rerankProvider: String,
    val rerankModel: String,
    val tokenBudget: Int,
)

/** 解析结果 — 三 profile + 每字段来源。 */
data class ResolvedPolicy(
    val ingest: IngestProfile,
    val embedding: EmbeddingProfile,
    val retrieval: RetrievalPolicy,
    val fieldSources: Map<String, PolicyFieldSource>,
) {
    fun sourceOf(field: String): PolicyFieldSource =
        fieldSources[field] ?: PolicyFieldSource.BUILT_IN
}

/**
 * 配置解析器 — 全局 → KB → 助手 → 会话逐层合并,后者只覆盖显式非空值。
 *
 * 解析规则:
 *  - 空串 / null = 继承下层,「清空覆盖」不是有效输入(用户要恢复继承需删除覆盖字段);
 *  - 数值 0 与 false 是**有效显式值**(不能被当成"未设置"吞掉);
 *  - 不读取任何凭据字段做空间身份(apiKey 不进入 [EmbeddingProfile])。
 */
object KnowledgePolicyResolver {

    fun resolve(
        global: RagConfig,
        kb: KnowledgeBaseEntity? = null,
        assistantOverrides: Map<String, String> = emptyMap(),
        sessionOverrides: Map<String, String> = emptyMap(),
    ): ResolvedPolicy {
        val sources = mutableMapOf<String, PolicyFieldSource>()

        // ---- IngestProfile ----
        val chunkSize = kb?.chunkSizeOverride ?: global.chunkSize
        sources["ingest.chunkSize"] =
            if (kb?.chunkSizeOverride != null) PolicyFieldSource.KB_OVERRIDE else PolicyFieldSource.GLOBAL_DEFAULT
        val chunkOverlap = kb?.chunkOverlapOverride ?: global.chunkOverlap
        sources["ingest.chunkOverlap"] =
            if (kb?.chunkOverlapOverride != null) PolicyFieldSource.KB_OVERRIDE else PolicyFieldSource.GLOBAL_DEFAULT

        val ingest = IngestProfile(
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
            markdownAware = global.markdownAware,
            chunkByToken = global.chunkByToken,
            parserType = global.documentParserType.name,
        )

        // ---- EmbeddingProfile(不含凭据)----
        val kbModel = kb?.embeddingModelOverride?.takeIf { it.isNotBlank() }
        val embeddingModel = kbModel ?: global.cloudModel
        sources["embedding.model"] =
            if (kbModel != null) PolicyFieldSource.KB_OVERRIDE else PolicyFieldSource.GLOBAL_DEFAULT
        val source = (assistantOverrides["embedding.source"]?.takeIf { it.isNotBlank() })
            ?: (sessionOverrides["embedding.source"]?.takeIf { it.isNotBlank() })
            ?: global.embeddingSource.name
        val providerId = assistantOverrides["embedding.providerId"]?.takeIf { it.isNotBlank() }
            ?: global.cloudProviderId
        val localPath = assistantOverrides["embedding.localModelPath"]?.takeIf { it.isNotBlank() }
            ?: global.localModelPath
        val localDigest = assistantOverrides["embedding.localModelDigest"]?.takeIf { it.isNotBlank() } ?: ""
        val endpointFingerprint = assistantOverrides["embedding.endpointFingerprint"]?.takeIf { it.isNotBlank() } ?: ""
        val dimension = assistantOverrides["embedding.verifiedDimension"]?.toIntOrNull()
        val revision = assistantOverrides["embedding.modelRevision"]?.takeIf { it.isNotBlank() }
        val revisionVerified = assistantOverrides["embedding.remoteRevisionVerified"] == "true"

        val embedding = EmbeddingProfile(
            source = source,
            providerId = providerId,
            model = embeddingModel,
            localModelPath = localPath,
            localModelDigest = localDigest,
            endpointFingerprint = endpointFingerprint,
            verifiedDimension = dimension,
            modelRevision = revision,
            remoteRevisionVerified = revisionVerified,
        )

        // ---- RetrievalPolicy(只影响查询)----
        val topK = kb?.topKOverride ?: global.topK
        sources["retrieval.topK"] = if (kb?.topKOverride != null) PolicyFieldSource.KB_OVERRIDE else PolicyFieldSource.GLOBAL_DEFAULT
        val threshold = kb?.thresholdOverride ?: global.threshold
        sources["retrieval.threshold"] =
            if (kb?.thresholdOverride != null) PolicyFieldSource.KB_OVERRIDE
            else PolicyFieldSource.GLOBAL_DEFAULT

        val retrieval = RetrievalPolicy(
            topK = topK,
            threshold = threshold,
            mmrLambda = global.mmrLambda,
            hybridEnabled = global.hybridEnabled,
            hybridBm25Weight = global.hybridBm25Weight,
            hybridVectorWeight = global.hybridVectorWeight,
            rerankEnabled = global.rerankEnabled,
            rerankProvider = global.rerankProvider,
            rerankModel = global.rerankModel,
            tokenBudget = global.tokenBudget,
        )

        return ResolvedPolicy(ingest = ingest, embedding = embedding, retrieval = retrieval, fieldSources = sources)
    }
}
