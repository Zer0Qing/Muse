package io.zer0.muse.rag

/**
 * v1.54: Embedding 提供者接口。
 *
 * 实现类:
 *  - [OpenAIEmbeddingProvider]: 云端 API(OpenAI / 智谱 / 通义等 OpenAI 兼容接口)
 *  - [LocalKeywordEmbeddingProvider]: 本地关键词哈希 embedding(离线,无需 ONNX)
 *  - [OnnxEmbeddingProvider]: 本地 ONNX 推理(预留,完整实现在后续版本启用)
 *
 * @see RagConfig.EmbeddingSource
 */
interface EmbeddingProvider {
    /** 提供者标识(用于日志和 UI 展示)。 */
    val id: String

    /** 显示名。 */
    val displayName: String

    /** 输出向量维度(不同模型维度不同,检索时须维度一致)。 */
    val dimension: Int

    /** 模型名(用于追踪 knowledge_docs.embedding_model)。 */
    val modelName: String

    /**
     * 批量生成 embedding。
     * @param texts 待编码文本列表
     * @return 对应的向量列表(顺序与输入一致),每条为 FloatArray
     * @throws Exception 网络/模型错误时抛出,由调用方 runCatching 处理
     */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /** 单条文本 embedding(便捷方法,内部调 [embed])。 */
    suspend fun embed(text: String): FloatArray =
        embed(listOf(text)).firstOrNull()
            ?: throw IllegalStateException("Provider returned empty vector list")
}
