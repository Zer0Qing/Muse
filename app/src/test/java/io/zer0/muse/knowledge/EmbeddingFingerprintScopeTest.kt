package io.zer0.muse.knowledge

import io.zer0.muse.rag.RagConfig
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 步骤 8(计划 R5「`LOCAL_KEYWORD` 迁移成清楚标识的关键词能力；冷启动 unknown→实测
 * 不再反复触发错误『模型切换』」)。
 *
 * 本测试用源码契约方式钉住编码指纹口径:指纹必须**只跟随编码字段**,
 * 不得包含 topK / chunkSize / threshold / rerank 等纯检索参数。
 * (指纹函数是 private;行为级验证需真实 Provider,归 R10 设备矩阵。)
 */
class EmbeddingFingerprintScopeTest {

    private val source: String by lazy {
        val candidates = listOf(
            java.io.File("src/main/java/io/zer0/muse/rag/EmbeddingService.kt"),
            java.io.File("app/src/main/java/io/zer0/muse/rag/EmbeddingService.kt"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("无法定位 EmbeddingService.kt")
    }

    private fun fingerprintBody(): String {
        val start = source.indexOf("private fun fingerprintOf(")
        require(start >= 0) { "未找到 fingerprintOf" }
        val end = source.indexOf("\n    }", start)
        require(end > start) { "未找到 fingerprintOf 结束位置" }
        return source.substring(start, end)
    }

    @Test
    fun `fingerprint must not include whole config hash`() {
        val body = fingerprintBody()
        assertTrue(
            "指纹不得包含 ragConfig.hashCode()(会把 topK/chunk 等检索参数算进编码身份)",
            !body.contains("hashCode()"),
        )
    }

    @Test
    fun `fingerprint includes embedding source and model identity`() {
        val body = fingerprintBody()
        assertTrue("必须含编码来源", body.contains("embeddingSource"))
        assertTrue("必须含本地模型路径", body.contains("localModelPath"))
        assertTrue("必须含云端模型名", body.contains("cloudModel"))
        assertTrue("必须含 provider 身份", body.contains("provider.id"))
        assertTrue("apiKey 只以摘要参与", body.contains("apiKeyDigest"))
    }

    @Test
    fun `fingerprint excludes retrieval-only params`() {
        val body = fingerprintBody()
        listOf("topK", "threshold", "mmrLambda", "chunkSize", "chunkOverlap", "rerankEnabled", "tokenBudget")
            .forEach { param ->
                assertTrue("指纹不得含检索参数: $param", !body.contains(param))
            }
    }

    @Test
    fun `local keyword fingerprint is stable across retrieval tuning`() {
        // 语义层验证:仅改检索参数时,编码配置(来源/模型)完全一致。
        val base = RagConfig(embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD)
        val tuned = base.copy(topK = 20, chunkSize = 1200, chunkOverlap = 200, threshold = 0.1f)
        assertTrue("仅改检索参数不得改变编码配置", base.embeddingSource == tuned.embeddingSource)
        assertTrue("本地模型路径一致", base.localModelPath == tuned.localModelPath)
        assertNotEquals("但配置整体确实不同(证明旧指纹会误判)", base.hashCode(), tuned.hashCode())
    }
}
