package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.domain.EmbeddingProfile
import io.zer0.muse.knowledge.index.EmbeddingSpaceRegistry
import io.zer0.muse.knowledge.index.SpaceResolution
import io.zer0.muse.rag.RagConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 步骤 1/3/5(计划 R5:「同维异模型、端点变化、本地同路径文件替换不可被当同空间」;
 * 「unknown 维度阻止于发布前；验证数量/NaN/Infinity/零向量与整批维度；实际凭据不写空间指纹」;
 * 「标记不可验证的远端模型修订」)。
 */
class EmbeddingSpaceIdentityTest {

    private val registry = EmbeddingSpaceRegistry()

    private fun cloudProfile(
        model: String = "model-a",
        dimension: Int? = 768,
        revision: String? = "rev-1",
        verified: Boolean = true,
        endpoint: String = "fp-1",
    ) = EmbeddingProfile(
        source = RagConfig.EmbeddingSource.CLOUD.name,
        providerId = "provider-x",
        model = model,
        endpointFingerprint = endpoint,
        verifiedDimension = dimension,
        modelRevision = revision,
        remoteRevisionVerified = verified,
    )

    @Test
    fun `same dimension different model yields different space id`() {
        val a = registry.resolve(cloudProfile(model = "model-a", dimension = 768)) as SpaceResolution.Ready
        val b = registry.resolve(cloudProfile(model = "model-b", dimension = 768)) as SpaceResolution.Ready

        assertEquals("维度相同", a.identity.dimension, b.identity.dimension)
        assertNotEquals("同维不同模型必须隔离", a.identity.spaceId, b.identity.spaceId)
    }

    @Test
    fun `opaque remote revision requires verification`() {
        val profile = cloudProfile(revision = null, verified = false)
        val result = registry.resolve(profile)
        assertTrue("opaque 修订应需重新验证", result is SpaceResolution.NeedsVerification)
    }

    @Test
    fun `endpoint without fingerprint requires verification`() {
        val profile = cloudProfile(endpoint = "")
        val result = registry.resolve(profile)
        assertTrue(result is SpaceResolution.NeedsVerification)
    }

    @Test
    fun `local model same path but different content digest is different space`() {
        val base = EmbeddingProfile(
            source = RagConfig.EmbeddingSource.LOCAL.name,
            providerId = "",
            model = "",
            localModelPath = "/models/embed.onnx",
            localModelDigest = "digest-aaaa",
            verifiedDimension = 384,
        )
        val replaced = base.copy(localModelDigest = "digest-bbbb")

        val a = registry.resolve(base) as SpaceResolution.Ready
        val b = registry.resolve(replaced) as SpaceResolution.Ready
        assertNotEquals("同路径替换文件必须换空间", a.identity.spaceId, b.identity.spaceId)
    }

    @Test
    fun `local model without digest requires verification`() {
        val profile = EmbeddingProfile(
            source = RagConfig.EmbeddingSource.LOCAL.name,
            providerId = "",
            model = "",
            localModelPath = "/models/embed.onnx",
            localModelDigest = "",
            verifiedDimension = 384,
        )
        assertTrue(registry.resolve(profile) is SpaceResolution.NeedsVerification)
    }

    @Test
    fun `local keyword is lexical capability not semantic space`() {
        val profile = EmbeddingProfile(
            source = RagConfig.EmbeddingSource.LOCAL_KEYWORD.name,
            providerId = "",
            model = "",
            localModelPath = "",
            verifiedDimension = null,
        )
        val ready = registry.resolve(profile) as SpaceResolution.Ready
        assertEquals(false, ready.identity.semantic)
        assertEquals("lexical:local_keyword:v1", ready.identity.spaceId)
    }

    @Test
    fun `unknown dimension blocks publishing`() {
        val profile = cloudProfile(dimension = null)
        val result = registry.resolve(profile)
        assertTrue("unknown 维度必须阻止发布", result is SpaceResolution.NeedsVerification)
    }

    @Test
    fun `vector batch validation rejects dimension mismatch nan inf and zero vectors`() {
        val ok = registry.validateVectorBatch(listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f)), 2)
        assertEquals(null, ok)

        val wrongDim = registry.validateVectorBatch(listOf(floatArrayOf(0.1f)), 2)
        assertNotNull("维度不一致必须拒绝", wrongDim)

        val withNan = registry.validateVectorBatch(listOf(floatArrayOf(0.1f, Float.NaN)), 2)
        assertNotNull("NaN 必须拒绝", withNan)

        val withInf = registry.validateVectorBatch(listOf(floatArrayOf(Float.POSITIVE_INFINITY, 0.1f)), 2)
        assertNotNull("Infinity 必须拒绝", withInf)

        val zero = registry.validateVectorBatch(listOf(floatArrayOf(0f, 0f)), 2)
        assertNotNull("零向量必须拒绝", zero)

        val empty = registry.validateVectorBatch(emptyList(), 2)
        assertNotNull("空批必须拒绝", empty)
    }

    @Test
    fun `endpoint fingerprint strips credentials and query`() {
        val fp1 = EmbeddingSpaceRegistry.endpointFingerprintOf("https://user:secret@api.example.com/v1/embed?key=SECRET")
        val fp2 = EmbeddingSpaceRegistry.endpointFingerprintOf("https://api.example.com/v1/embed")
        assertEquals("凭据与 query 不参与指纹", fp1, fp2)
        assertEquals(false, fp1.contains("SECRET"))
        assertEquals(false, fp1.contains("secret"))
    }

    @Test
    fun `verification failures produce unavailable`() {
        val profile = cloudProfile()
        val bad = io.zer0.muse.knowledge.index.CapabilityVerification(
            dimension = 768,
            sampleCount = 2,
            allFinite = false,
            allNonZeroNorm = true,
            uniformDimension = true,
        )
        assertTrue(registry.resolve(profile, bad) is SpaceResolution.Unavailable)
    }
}
