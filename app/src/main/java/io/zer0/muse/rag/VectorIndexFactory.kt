package io.zer0.muse.rag

/**
 * v1.55: 向量索引工厂。
 *
 * 根据数据规模自动选择合适的索引策略:
 *  - [IndexStrategy.BRUTE_FORCE]:小规模(<5000 chunk),复用现有 [VectorSearchService] 暴力遍历
 *  - [IndexStrategy.HNSW]:大规模(>=5000 chunk),使用 [HnswVectorIndex] 近似最近邻
 *
 * 工厂本身不创建暴力遍历索引(它由 [RagService] 直接持有 [VectorSearchService] 实现);
 * 此处仅负责创建 [VectorIndex] 实现(目前只有 HNSW)。
 */
object VectorIndexFactory {

    /** 索引策略枚举。 */
    enum class IndexStrategy {
        /** 暴力遍历(小规模,<5000 chunk):由现有 [VectorSearchService] 实现,RagService 直接持有。 */
        BRUTE_FORCE,

        /** HNSW 近似最近邻(大规模,>=5000 chunk):由 [HnswVectorIndex] 实现。 */
        HNSW,
    }

    /** 自动选择阈值:chunk 数 >= 此值时使用 HNSW,否则使用暴力遍历。 */
    const val AUTO_THRESHOLD = 5000

    /**
     * 创建指定策略的 [VectorIndex]。
     *
     * - [IndexStrategy.BRUTE_FORCE] 返回 null(由 [RagService] 走 [VectorSearchService] 路径)
     * - [IndexStrategy.HNSW] 返回 [HnswVectorIndex](使用默认参数 M=16/efC=200/efS=50)
     *
     * @return [VectorIndex] 实例;BRUTE_FORCE 策略返回 null
     */
    fun createIndex(strategy: IndexStrategy): VectorIndex? = when (strategy) {
        IndexStrategy.BRUTE_FORCE -> null  // 暴力遍历由 RagService 现有 vectorSearch 实现
        IndexStrategy.HNSW -> HnswVectorIndex()
    }

    /**
     * 根据 chunk 数量自动选择策略并创建索引。
     *
     * @param chunkCount 当前已索引 chunk 数
     * @return [VectorIndex] 实例;chunk 数 < [AUTO_THRESHOLD] 时返回 null(走暴力遍历)
     */
    fun createAutoIndex(chunkCount: Int): VectorIndex? =
        createIndex(autoStrategy(chunkCount))

    /** 根据 chunk 数量选择策略(>=5000 用 HNSW,否则 BRUTE_FORCE)。 */
    fun autoStrategy(chunkCount: Int): IndexStrategy =
        if (chunkCount >= AUTO_THRESHOLD) IndexStrategy.HNSW else IndexStrategy.BRUTE_FORCE
}
