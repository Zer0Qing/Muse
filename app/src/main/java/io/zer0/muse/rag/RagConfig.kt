package io.zer0.muse.rag

import kotlinx.serialization.Serializable

/**
 * v1.54: RAG 配置。
 *
 * v1.133 扩展:
 *  - [mmrLambda]:MMR 多样性重排权重(0=纯多样性,1=纯相似度,默认 0.5)
 *  - [hybridEnabled]:是否启用 BM25+向量 混合检索(RRF 融合)
 *  - [rerankEnabled]:是否启用 cross-encoder 重排序
 *  - [rerankProvider]:重排序 Provider 类型(cohere/jina/local)
 *  - [tokenBudget]:注入 token 预算(0 = 不限制;>0 = 按相似度降序累加,超预算即停)
 *  - [markdownAware]:分块时是否感知 Markdown 结构(代码块/标题/表格)
 *  - [chunkByToken]:是否按 token 数分块(true=按 [chunkSize] token,false=按字符)
 *
 * 持久化在 DataStore(rag_config_json key),由 SettingsRepository 管理。
 */
@Serializable
data class RagConfig(
    /** RAG 总开关(自动注入)。关闭后知识库仍可被 knowledge_search 工具手动检索。 */
    val enabled: Boolean = true,
    /** Embedding 来源。 */
    val embeddingSource: EmbeddingSource = EmbeddingSource.CLOUD,
    /**
     * 云端 embedding 使用的 Provider ID(对应 SettingsRepository 里的 ProviderConfig.id)。
     * 空串表示用当前激活的 chat Provider。
     */
    val cloudProviderId: String = "",
    /** 云端 embedding 模型名(空串表示用 Provider 默认 embedding 模型)。 */
    val cloudModel: String = "",
    /** 本地 ONNX 模型文件路径(filesDir 下的相对路径)。 */
    val localModelPath: String = "",
    /** 自动注入检索的 top-k 数量。 */
    val topK: Int = 3,
    /** 自动注入的相似度阈值(0-1,低于此值不注入)。 */
    val threshold: Float = 0.35f,
    /** 分块目标大小([chunkByToken]=true 时为 token 数,false 时为字符数)。 */
    val chunkSize: Int = 500,
    /** 分块重叠(同 [chunkSize] 单位)。 */
    val chunkOverlap: Int = 50,

    // ── v1.133 新增 ──
    /** MMR 多样性重排权重(0=纯多样性,1=纯相似度,默认 0.5)。 */
    val mmrLambda: Float = 0.5f,
    /** 是否启用 BM25+向量 混合检索(RRF 融合)。 */
    val hybridEnabled: Boolean = false,
    /** 是否启用 cross-encoder 重排序。 */
    val rerankEnabled: Boolean = false,
    /** 重排序 Provider 类型(cohere/jina/local,空串表示按 LocalRerankProvider)。 */
    val rerankProvider: String = "",
    /**
     * 注入 token 预算(0 = 不限制;>0 = 按相似度降序累加,超预算即停)。
     * 推荐 = 当前模型 contextWindow × 0.3。
     */
    val tokenBudget: Int = 0,
    /** 分块时是否感知 Markdown 结构(代码块/标题/表格整体保留)。 */
    val markdownAware: Boolean = false,
    /** 是否按 token 数分块(true=按 [chunkSize] token,false=按字符)。 */
    val chunkByToken: Boolean = false,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, current: $chunkSize" }
        require(chunkOverlap >= 0) { "chunkOverlap cannot be negative, current: $chunkOverlap" }
        require(chunkOverlap < chunkSize) { "chunkOverlap($chunkOverlap) must be < chunkSize($chunkSize)" }
        require(threshold in 0f..1f) { "threshold must be in 0..1 range, current: $threshold" }
        require(topK > 0) { "topK must be > 0, current: $topK" }
        require(mmrLambda in 0f..1f) { "mmrLambda must be in 0..1 range, current: $mmrLambda" }
        require(tokenBudget >= 0) { "tokenBudget cannot be negative, current: $tokenBudget" }
    }

    /** Embedding 来源枚举。 */
    @Serializable
    enum class EmbeddingSource {
        /** 云端 API(质量优先,需联网)。 */
        CLOUD,
        /**
         * v1.133: 本地 ONNX(神经网络语义嵌入,需下载模型文件)。
         * 若 onnxruntime-android 依赖未引入,RagService 会降级到 LOCAL_KEYWORD。
         */
        LOCAL,
        /** v1.133: 本地关键词哈希(无 ONNX 时的离线降级方案,非真正语义)。 */
        LOCAL_KEYWORD,
    }
}
