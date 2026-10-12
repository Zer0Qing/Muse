package io.zer0.muse.data.knowledge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-RAG-F1: 内置文档索引的待处理判定必须比对 embedding 模型指纹。
 *
 * 此前 ensureIndexed 只看 chunkCount/contentHash:换 embedding 模型(尤其
 * 维度相同的模型)后,文档已索引就不会重建,新旧向量混存 → 检索质量静默劣化。
 */
class BuiltInKnowledgeDocSeederIndexingTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/data/knowledge/BuiltInKnowledgeDocSeeder.kt").readText()

    /** 安全截取:从锚点取一段(不越界)。 */
    private fun body(src: String, anchor: String): String {
        val start = src.indexOf(anchor)
        assertTrue("未找到 $anchor", start >= 0)
        val end = (start + 900).coerceAtMost(src.length)
        return src.substring(start, end)
    }

    @Test
    fun `pending filter compares embedding model fingerprint`() {
        val src = source()
        val body = body(src, "private fun needsIndexing(")
        assertTrue(
            "待索引判定必须比对 embeddingModel 与 RagConfig.embeddingModelKey(config)",
            body.contains("doc.embeddingModel == RagConfig.embeddingModelKey(config)") &&
                body.contains("!fingerprintMatches"),
        )
    }

    @Test
    fun `pending filter still covers chunk count and content hash`() {
        val src = source()
        val body = body(src, "private fun needsIndexing(")
        assertTrue(body.contains("doc.chunkCount <= 0"))
        assertTrue(body.contains("doc.contentHash == RagService.computeContentHash(doc.content)"))
        assertTrue(body.contains("!contentMatches"))
    }

    @Test
    fun `reindex writes current embedding model key`() {
        val src = source()
        // 锚定索引成功后的回写(seed 段另有一处 upsert,不能按首个匹配定位)
        val start = src.indexOf("chunkCount = chunks,")
        assertTrue("未找到索引回写", start >= 0)
        val body = src.substring(start, start + 300)
        assertTrue(
            "回写必须刷新 embeddingModel 指纹",
            body.contains("embeddingModel = RagConfig.embeddingModelKey(config)"),
        )
    }
}
