package io.zer0.muse.rag

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-RAG-F3: embedding 指纹必须使用稳定维度口径,不能用「首次 embed 前的猜测维度」。
 *
 * 原实现:`embeddingFingerprint` 直接拼 `provider.dimension`,而该值在未实测时是
 * guessDimension() 的猜测值 —— 猜测值与真实维度不一致时,指纹在「猜测→真实」之间
 * 跳变,HNSW 索引每次冷启动都判定指纹变化并重建。
 */
class RagEmbeddingFingerprintStabilityTest {

    private fun ragSource(): String = java.io.File("src/main/java/io/zer0/muse/rag/RagService.kt").readText()

    private fun providerSource(): String = java.io.File("src/main/java/io/zer0/muse/rag/OpenAIEmbeddingProvider.kt").readText()

    @Test
    fun `fingerprint uses stable dimension instead of raw dimension`() {
        val src = ragSource()
        val start = src.indexOf("private fun embeddingFingerprint(")
        assertTrue("未找到 embeddingFingerprint", start >= 0)
        val body = src.substring(start, start + 600)
        assertTrue(
            "指纹必须拼 fingerprintDimension(稳定口径)",
            body.contains("provider.fingerprintDimension"),
        )
        assertTrue(
            "指纹不应再直接拼 provider.dimension",
            !body.contains("\${provider.dimension}"),
        )
    }

    @Test
    fun `cloud provider exposes stable fingerprint dimension`() {
        val src = providerSource()
        assertTrue(
            "OpenAIEmbeddingProvider 必须覆写 fingerprintDimension",
            src.contains("override val fingerprintDimension: String"),
        )
        assertTrue(
            "未实测时指纹维度应为稳定占位 unknown,而非 guess 值",
            src.contains("measuredDimension?.toString() ?: \"unknown\""),
        )
    }
}
