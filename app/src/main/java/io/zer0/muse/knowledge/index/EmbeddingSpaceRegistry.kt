package io.zer0.muse.knowledge.index

import io.zer0.muse.knowledge.domain.EmbeddingProfile
import io.zer0.muse.rag.RagConfig
import java.security.MessageDigest

/**
 * R5 embedding 空间身份 — 设计 §9.2「空间身份规则」与计划 R5 步骤 3/5。
 *
 * 空间身份规则(由 EmbeddingSpaceIdentityTest 钉住):
 *  - spaceId 来自**有效模型语义身份 + 已验证维度 + 归一化/距离规则**,不含凭据;
 *  - LOCAL_KEYWORD 是词法/关键词能力,**不是语义空间**(semantic=false),不能与语义向量混算;
 *  - 本地模型用**内容摘要**(文件 SHA-256)识别:同一路径文件被替换 → 不同空间;
 *  - 远端未提供可验证修订时按 opaque identity 处理:端点变化 → 需要重新验证;
 *  - 维度 unknown(null)不是可发布空间;发布前必须验证。
 *
 * 不做的事:不读取密钥、不发探测请求、不承诺识别服务端静默升级。
 */
data class SpaceIdentity(
    val spaceId: String,
    /** 是否真正的语义编码;LOCAL_KEYWORD=false。 */
    val semantic: Boolean,
    val dimension: Int,
    /** 可验证的模型修订;null = opaque。 */
    val modelRevision: String?,
)

/** 能力验证结果 — 由实际 embedding 批次产出(数量/维度/数值有效性)。 */
data class CapabilityVerification(
    val dimension: Int,
    val sampleCount: Int,
    val allFinite: Boolean,
    val allNonZeroNorm: Boolean,
    val uniformDimension: Boolean,
)

sealed interface SpaceResolution {
    /** 可用于发布/查询的空间。 */
    data class Ready(val identity: SpaceIdentity) : SpaceResolution

    /** 需要先做能力验证(unknown 维度 / 端点变化 / 本地模型未摘要)。 */
    data class NeedsVerification(val reason: String) : SpaceResolution

    /** 明确不可用(来源未配置 / 本地模型缺失 / 维度非法)。 */
    data class Unavailable(val reason: String) : SpaceResolution
}

class EmbeddingSpaceRegistry {

    /**
     * 解析空间。只有通过能力验证且维度确定的 profile 才能得到 [SpaceResolution.Ready]。
     */
    fun resolve(profile: EmbeddingProfile, verification: CapabilityVerification? = null): SpaceResolution =
        when (profile.source) {
            // 关键词能力不冒充语义:维度固定 0,不参与向量余弦。
            RagConfig.EmbeddingSource.LOCAL_KEYWORD.name -> SpaceResolution.Ready(
                SpaceIdentity(
                    spaceId = "lexical:local_keyword:v1",
                    semantic = false,
                    dimension = 0,
                    modelRevision = null,
                ),
            )

            RagConfig.EmbeddingSource.LOCAL.name -> resolveLocal(profile, verification)

            RagConfig.EmbeddingSource.CLOUD.name -> resolveCloud(profile, verification)

            else -> SpaceResolution.Unavailable("未知 embedding 来源: ${profile.source}")
        }

    private fun resolveLocal(
        profile: EmbeddingProfile,
        verification: CapabilityVerification?,
    ): SpaceResolution = when {
        profile.localModelPath.isBlank() -> SpaceResolution.Unavailable("本地模型路径为空")
        profile.localModelDigest.isBlank() ->
            SpaceResolution.NeedsVerification("本地模型缺少内容摘要(同路径替换无法识别)")
        else -> {
            val verified = applyVerification(profile, verification)
            if (verified == null) {
                verificationError(profile, verification)
            } else {
                SpaceResolution.Ready(
                    SpaceIdentity(
                        spaceId = "local:onnx:${profile.localModelDigest.take(16)}:" +
                            "${profile.normalization}:${profile.distance}",
                        semantic = true,
                        dimension = verified,
                        modelRevision = profile.modelRevision,
                    ),
                )
            }
        }
    }

    private fun resolveCloud(
        profile: EmbeddingProfile,
        verification: CapabilityVerification?,
    ): SpaceResolution = when {
        profile.providerId.isBlank() || profile.model.isBlank() ->
            SpaceResolution.Unavailable("云端编码配置缺少 provider 或模型")
        profile.endpointFingerprint.isBlank() ->
            SpaceResolution.NeedsVerification("云端端点未做无凭据指纹,无法识别端点变化")
        // 远端修订不可验证:opaque identity,端点变化需重新验证。
        !profile.remoteRevisionVerified && profile.modelRevision == null ->
            SpaceResolution.NeedsVerification("远端模型修订不可验证(opaque identity),需重新验证后才能发布")
        else -> {
            val verified = applyVerification(profile, verification)
            if (verified == null) {
                verificationError(profile, verification)
            } else {
                SpaceResolution.Ready(
                    SpaceIdentity(
                        spaceId = cloudSpaceId(profile, verified),
                        semantic = true,
                        dimension = verified,
                        modelRevision = profile.modelRevision,
                    ),
                )
            }
        }
    }

    /**
     * 校验一批向量是否满足发布条件(计划 R5 步骤 3:数量/NaN/Infinity/零向量/整批维度)。
     * @return null = 通过;非空 = 具体失败原因。
     */
    fun validateVectorBatch(vectors: List<FloatArray>, expectedDimension: Int): String? = when {
        vectors.isEmpty() -> "向量批为空"
        expectedDimension <= 0 -> "期望维度非法: $expectedDimension"
        else -> vectors.withIndex().firstNotNullOfOrNull { (index, vector) ->
            validateSingleVector(index, vector, expectedDimension)
        }
    }

    private fun validateSingleVector(index: Int, vector: FloatArray, expectedDimension: Int): String? = when {
        vector.size != expectedDimension -> "第 $index 条维度不一致: ${vector.size} != $expectedDimension"
        vector.any { !it.isFinite() } -> "第 $index 条含 NaN/Infinity"
        vector.all { it == 0f } -> "第 $index 条为零向量"
        else -> null
    }

    /** 从实际向量批构造能力验证结果(供 [resolve] 使用)。 */
    fun verificationOf(vectors: List<FloatArray>): CapabilityVerification {
        val dimension = vectors.firstOrNull()?.size ?: 0
        val uniform = vectors.all { it.size == dimension }
        val finite = vectors.all { row -> row.all { it.isFinite() } }
        val nonZero = vectors.all { row -> row.any { it != 0f } }
        return CapabilityVerification(
            dimension = dimension,
            sampleCount = vectors.size,
            allFinite = finite,
            allNonZeroNorm = nonZero,
            uniformDimension = uniform,
        )
    }

    private fun applyVerification(profile: EmbeddingProfile, verification: CapabilityVerification?): Int? {
        val verified = profile.verifiedDimension ?: verification?.dimension
        if (verified == null || verified <= 0) return null
        if (verification == null) return verified
        val consistent = verification.uniformDimension &&
            verification.allFinite &&
            verification.allNonZeroNorm &&
            verification.dimension == verified
        return if (consistent) verified else null
    }

    private fun verificationError(profile: EmbeddingProfile, verification: CapabilityVerification?): SpaceResolution = when {
        profile.verifiedDimension == null && verification == null ->
            SpaceResolution.NeedsVerification("维度 unknown,必须先做能力验证")
        verification != null && !verification.uniformDimension ->
            SpaceResolution.Unavailable("向量批维度不一致")
        verification != null && !verification.allFinite ->
            SpaceResolution.Unavailable("向量批含 NaN/Infinity")
        verification != null && !verification.allNonZeroNorm ->
            SpaceResolution.Unavailable("向量批含零向量")
        profile.verifiedDimension != null && verification != null &&
            verification.dimension != profile.verifiedDimension ->
            SpaceResolution.Unavailable(
                "实测维度与声明不一致: ${verification.dimension} != ${profile.verifiedDimension}",
            )
        else -> SpaceResolution.NeedsVerification("维度未验证")
    }

    private fun cloudSpaceId(profile: EmbeddingProfile, dimension: Int): String {
        val revision = profile.modelRevision ?: "opaque"
        // 只使用非凭据字段拼接;endpointFingerprint 已在解析层去掉凭据。
        return "cloud:${profile.providerId}:${profile.model}:$revision:$dimension:${profile.normalization}:${profile.distance}"
    }

    companion object {
        /** 端点指纹:只取 scheme+host+path 的稳定摘要,丢弃 query/userinfo/密钥。 */
        fun endpointFingerprintOf(endpoint: String): String {
            if (endpoint.isBlank()) return ""
            val withoutQuery = endpoint.substringBefore('?').substringBefore('#')
            val sanitized = withoutQuery
                .removePrefix("https://")
                .removePrefix("http://")
                .substringAfter('@') // 去掉 userinfo
            return MessageDigest.getInstance("SHA-256")
                .digest(sanitized.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }

        /** 本地模型文件内容摘要:同一路径被替换会得到不同摘要。 */
        fun localModelDigestOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
