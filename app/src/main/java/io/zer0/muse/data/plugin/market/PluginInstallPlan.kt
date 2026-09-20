package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import java.io.File

/**
 * A confirmation-bound install plan.
 *
 * The plan pins the exact staged artifact bytes and the reviewed package preview, so the
 * later confirm step re-reads the same file instead of re-downloading from the network.
 * A stale plan (file replaced, bytes changed, or expiry passed) must fail closed.
 */
data class PluginInstallPlan(
    val entry: PluginCatalogEntry,
    val stagedFile: File,
    val artifactSha256: String,
    val preview: PluginSecurityGate.InstallPreview,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs
}

sealed class InstallPlanResult {
    data class Ready(val plan: PluginInstallPlan) : InstallPlanResult()
    data class Rejected(val reason: String) : InstallPlanResult()
}

/**
 * Builds preview plans from signed catalog entries.
 *
 * This class deliberately performs no installation: it verifies the catalog attestation,
 * ensures the artifact exists and matches the catalog digest, and produces the host-owned
 * preview that the user must confirm before [io.zer0.muse.data.plugin.PluginManager] commits.
 */
class PluginInstallCoordinator(
    private val trustRootKeys: Map<String, String>,
    private val lastAcceptedSequence: (String) -> Long?,
    private val planTtlMs: Long = DEFAULT_PLAN_TTL_MS,
) {
    fun preparePlan(
        signedCatalog: SignedPluginCatalog,
        entryId: String,
        stagedFile: File,
        artifactSha256: String,
        reviewedPackage: PluginPackageReview,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): InstallPlanResult {
        val verification = PluginCatalogVerifier.verify(
            signed = signedCatalog,
            trustedKeys = trustRootKeys,
            nowEpochMs = nowEpochMs,
            // 已接受 sequence 按目录 id 记录，切换目录时不会互相把对方判成回退。
            lastAcceptedSequence = lastAcceptedSequence(signedCatalog.payload.catalogId),
        )
        if (!verification.valid) {
            return InstallPlanResult.Rejected(verification.reason ?: "目录校验失败")
        }
        val entry = signedCatalog.payload.entries.firstOrNull { it.id == entryId }
            ?: return InstallPlanResult.Rejected("目录中不存在插件: $entryId")
        if (!stagedFile.isFile) return InstallPlanResult.Rejected("插件包不存在")
        if (!artifactSha256.equals(entry.artifactSha256, ignoreCase = true)) {
            return InstallPlanResult.Rejected("插件包摘要与目录不一致")
        }
        if (!reviewedPackage.contentSha256.equals(entry.manifestSha256, ignoreCase = true)) {
            return InstallPlanResult.Rejected("插件内容摘要与目录不一致")
        }
        if (reviewedPackage.preview.id != entry.id) {
            return InstallPlanResult.Rejected("插件 id 与目录不一致")
        }
        if (reviewedPackage.preview.version != entry.version) {
            return InstallPlanResult.Rejected("插件版本与目录不一致")
        }
        // 目录声明的发行者身份必须与包内签名解析出的身份一致：目录里的这两个字段会展示给用户，
        // 若允许它们和真实签名不一致，卡片上的发行者就是可伪造的展示信息。
        if (reviewedPackage.preview.publisherId != entry.publisherId) {
            return InstallPlanResult.Rejected("插件发行者与目录不一致")
        }
        if (!reviewedPackage.preview.publisherKeyFingerprint.equals(entry.publisherKeyFingerprint, ignoreCase = true)) {
            return InstallPlanResult.Rejected("插件发行者公钥与目录不一致")
        }
        return InstallPlanResult.Ready(
            PluginInstallPlan(
                entry = entry,
                stagedFile = stagedFile,
                artifactSha256 = artifactSha256,
                preview = reviewedPackage.preview,
                createdAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + planTtlMs,
            ),
        )
    }

    /** Package-level review result that stays bound to the reviewed bytes. */
    data class PluginPackageReview(
        val preview: PluginSecurityGate.InstallPreview,
        val contentSha256: String,
    )

    companion object {
        internal const val DEFAULT_PLAN_TTL_MS = 5 * 60 * 1000L
    }
}
