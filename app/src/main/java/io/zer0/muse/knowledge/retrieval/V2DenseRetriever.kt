package io.zer0.muse.knowledge.retrieval

import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeVectorRow
import kotlin.math.sqrt

/**
 * R6 V2 向量召回 — 范围内数据库精确扫描(设计 §10.2「ANN 不能原生按范围过滤时
 * 使用可验证的范围内精确扫描」)。
 *
 * 约束:
 *  - 只扫描**活动代**且 `embedding_blob` 非空的行(未完成向量批次不可见);
 *  - 查询向量维度必须与库内向量维度一致,不一致 → 该路径降级(不比较跨维度余弦);
 *  - 余弦相似度只在本空间内比较,不跨空间比较原始分数;
 *  - 候选预算 = limit * oversample,达上限标记 incomplete(不伪装完整 topK)。
 *
 * 性能边界:这是**扫描**实现,不是 ANN。10k 档已在 JVM/设备基线内;更大规模需 ANN(R10 容量探索)。
 */
class V2DenseRetriever(
    private val dao: KnowledgeJobDao,
    private val oversample: Int = 4,
) : DenseRetriever {

    override suspend fun search(
        request: KnowledgeSearchRequest,
        activeGenerationIds: List<String>,
        limit: Int,
    ): DenseRecall {
        if (activeGenerationIds.isEmpty()) return DenseRecall(hits = emptyList())

        val queryVector = request.queryVector
            ?: return DenseRecall(
                hits = emptyList(),
                degradedReason = "本轮未提供查询向量,dense 路径跳过",
            )
        if (queryVector.isEmpty()) {
            return DenseRecall(hits = emptyList(), degradedReason = "查询向量为空")
        }

        val candidateBudget = (limit * oversample).coerceAtLeast(limit)
        val rows = try {
            dao.getVectorRows(activeGenerationIds, candidateBudget)
        } catch (e: Exception) {
            return DenseRecall(hits = emptyList(), degradedReason = "向量索引不可用: ${e.message}")
        }
        if (rows.isEmpty()) {
            return DenseRecall(hits = emptyList(), degradedReason = "活动代内没有已就绪向量")
        }
        val incomplete = rows.size >= candidateBudget

        val scored = mutableListOf<DenseHit>()
        var dimensionMismatch = 0
        for (row in rows) {
            val vector = row.embeddingBlob.toFloatArray()
            if (vector.size != queryVector.size) {
                dimensionMismatch++
                continue
            }
            val score = cosine(queryVector, vector)
            if (!score.isFinite()) continue
            scored.add(
                DenseHit(
                    chunkId = "${row.generationId}#${row.chunkIndex}",
                    docId = row.docId,
                    generationId = row.generationId,
                    text = row.content,
                    locator = row.locator,
                    score = score,
                    rank = 0,
                ),
            )
        }

        val degradedReason = when {
            scored.isEmpty() && dimensionMismatch > 0 ->
                "库内向量维度与查询向量不一致($dimensionMismatch 条跳过)"
            scored.isEmpty() -> "向量相似度计算无有效结果"
            dimensionMismatch > 0 -> "部分向量维度不一致($dimensionMismatch 条跳过)"
            else -> null
        }

        val hits = scored
            .sortedWith(compareByDescending<DenseHit> { it.score }.thenBy { it.chunkId })
            .take(candidateBudget)
            .mapIndexed { index, hit -> hit.copy(rank = index) }

        return DenseRecall(hits = hits, incomplete = incomplete, degradedReason = degradedReason)
    }

    companion object {
        /** 余弦相似度;零向量返回 NaN(调用方过滤,不伪造 0 分)。 */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return Float.NaN
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i].toDouble()
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }
            if (normA <= 0.0 || normB <= 0.0) return Float.NaN
            return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
        }

        /** BIG_ENDIAN 字节 → FloatArray(与 GenerationPublisher 写入口径一致)。 */
        fun ByteArray.toFloatArray(): FloatArray {
            if (size % 4 != 0) return FloatArray(0)
            val buffer = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.BIG_ENDIAN)
            return FloatArray(size / 4) { buffer.getFloat() }
        }
    }
}
