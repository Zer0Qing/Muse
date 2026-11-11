package io.zer0.muse.knowledge

import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.knowledge.domain.KnowledgePolicyResolver
import io.zer0.muse.knowledge.index.EmbeddingSpaceRegistry
import io.zer0.muse.knowledge.domain.EmbeddingProfile
import io.zer0.muse.rag.RagConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import io.zer0.muse.knowledge.domain.PolicyFieldSource

/**
 * R5 步骤 1/2(计划 R5:「先写覆盖优先级与字段来源测试；修改 topK 不变 spaceId」;
 * 「拆分 IngestProfile、EmbeddingProfile、RetrievalPolicy；迁移旧 RagConfig 与 KB override，
 * 显式保留用户保存值，不让默认覆盖」)。
 */
class KnowledgePolicyResolutionTest {

    private fun baseConfig() = RagConfig(
        chunkSize = 500,
        chunkOverlap = 50,
        topK = 3,
        threshold = 0.35f,
        embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
    )

    @Test
    fun `kb override wins over global and field source is recorded`() {
        val kb = KnowledgeBaseEntity(
            id = "kb-1",
            name = "kb",
            chunkSizeOverride = 300,
            topKOverride = 8,
            thresholdOverride = 0.6f,
        )
        val resolved = KnowledgePolicyResolver.resolve(baseConfig(), kb)

        assertEquals(300, resolved.ingest.chunkSize)
        assertEquals(8, resolved.retrieval.topK)
        assertEquals(0.6f, resolved.retrieval.threshold, 0.0001f)
        assertEquals(PolicyFieldSource.KB_OVERRIDE, resolved.sourceOf("ingest.chunkSize"))
        assertEquals(PolicyFieldSource.KB_OVERRIDE, resolved.sourceOf("retrieval.topK"))
    }

    @Test
    fun `absent kb override inherits global and source is global`() {
        val kb = KnowledgeBaseEntity(id = "kb-1", name = "kb")
        val resolved = KnowledgePolicyResolver.resolve(baseConfig(), kb)

        assertEquals(500, resolved.ingest.chunkSize)
        assertEquals(3, resolved.retrieval.topK)
        assertEquals(PolicyFieldSource.GLOBAL_DEFAULT, resolved.sourceOf("ingest.chunkSize"))
        assertEquals(PolicyFieldSource.GLOBAL_DEFAULT, resolved.sourceOf("retrieval.topK"))
    }

    @Test
    fun `changing topK does not change embedding space id`() {
        val registry = EmbeddingSpaceRegistry()
        val profile = EmbeddingProfile(
            source = RagConfig.EmbeddingSource.CLOUD.name,
            providerId = "prov-1",
            model = "embed-model-a",
            endpointFingerprint = "abc123",
            verifiedDimension = 768,
            modelRevision = "rev-1",
            remoteRevisionVerified = true,
        )
        val space1 = registry.resolve(profile) as io.zer0.muse.knowledge.index.SpaceResolution.Ready

        // 只改查询策略(topK),编码配置不变 -> spaceId 必须完全相同。
        val kb = KnowledgeBaseEntity(id = "kb-1", name = "kb", topKOverride = 99)
        val resolved = KnowledgePolicyResolver.resolve(baseConfig(), kb)
        assertEquals(99, resolved.retrieval.topK)

        val space2 = registry.resolve(profile) as io.zer0.muse.knowledge.index.SpaceResolution.Ready
        assertEquals("改 topK 不得改变空间身份", space1.identity.spaceId, space2.identity.spaceId)
    }

    @Test
    fun `false and zero are valid explicit values not treated as unset`() {
        val config = RagConfig(chunkByToken = false, tokenBudget = 0)
        val resolved = KnowledgePolicyResolver.resolve(config)
        assertEquals(false, resolved.ingest.chunkByToken)
        assertEquals(0, resolved.retrieval.tokenBudget)
    }

    @Test
    fun `user saved embedding model is not overwritten by global default`() {
        val kb = KnowledgeBaseEntity(id = "kb-1", name = "kb", embeddingModelOverride = "user-chosen-model")
        val resolved = KnowledgePolicyResolver.resolve(baseConfig(), kb)
        assertEquals("user-chosen-model", resolved.embedding.model)
        assertEquals(PolicyFieldSource.KB_OVERRIDE, resolved.sourceOf("embedding.model"))
    }

    @Test
    fun `credentials are not part of embedding profile`() {
        val config = RagConfig(rerankApiKey = "SECRET-KEY-SHOULD-NOT-LEAK")
        val resolved = KnowledgePolicyResolver.resolve(config)
        // EmbeddingProfile 无任何 apiKey 字段;确保其字符串形态不含密钥。
        assertNotNull(resolved.embedding)
        assertEquals(false, resolved.embedding.toString().contains("SECRET-KEY-SHOULD-NOT-LEAK"))
    }
}
