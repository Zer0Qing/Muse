package io.zer0.muse.data.plugin

/** 待安装插件的版本相关身份信息。 */
data class PluginInstallCandidate(
    val id: String,
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    /** 是否带有效发行者签名（未签名的隔离草稿为 false）。 */
    val signed: Boolean,
    val minAppVersion: String,
)

/** 已安装插件的版本相关身份；用于比较发行者血缘与降级。 */
data class InstalledPluginIdentity(
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    val signed: Boolean,
)

sealed class PluginVersionDecision {
    data object Allow : PluginVersionDecision()
    data class Reject(val reason: String) : PluginVersionDecision()
}

/**
 * 插件安装的版本策略（纯逻辑，便于单测）。
 *
 * 拦住三类真实风险：
 *  1. **降级安装**：比已安装版本更旧的包（可能带已知漏洞）被静默装上。回滚走显式入口，
 *     那条路径由用户主动发起并会重新校验，所以用 [allowDowngrade] 放行。
 *  2. **发行者换人**：同名插件 id 被另一把公钥的包覆盖，等于接管已装插件的身份与技能。
 *  3. **未签名覆盖已签名**：隔离草稿（AI 生成或本地导入的未签名包）绝不允许顶掉用户
 *     已经确认过信任的签名版本。
 *
 * 另外强制 manifest 的 `minAppVersion`：要求高于当前 App 版本的插件直接拒绝，
 * 而不是装上一个注定运行失败的插件。
 */
object PluginVersionPolicy {

    fun evaluate(
        candidate: PluginInstallCandidate,
        installed: InstalledPluginIdentity?,
        appVersion: String,
        allowDowngrade: Boolean = false,
        /**
         * 本机作者密钥的发行者 id。
         *
         * 由它签名的安装允许被**新的未签名草稿**覆盖：这是助手迭代自己写的插件的唯一路径，
         * 且草稿依旧是禁用+未确认状态，必须由用户再次签名才会生效，因此不构成提权。
         * 第三方已签名安装仍然拒绝被未签名包覆盖。
         */
        localAuthorPublisherId: String? = null,
    ): PluginVersionDecision {
        val candidateVersion = PluginVersion.parse(candidate.version)
            ?: return PluginVersionDecision.Reject("插件版本号不是语义化版本: ${candidate.version}")
        val requiredApp = PluginVersion.parse(candidate.minAppVersion)
            ?: return PluginVersionDecision.Reject("minAppVersion 不是语义化版本: ${candidate.minAppVersion}")
        val currentApp = PluginVersion.parse(appVersion)
            ?: return PluginVersionDecision.Reject("无法解析当前 App 版本: $appVersion")

        if (requiredApp > currentApp) {
            return PluginVersionDecision.Reject("插件要求 App 版本不低于 $requiredApp，当前为 $currentApp")
        }
        val existing = installed ?: return PluginVersionDecision.Allow

        if (existing.signed && !candidate.signed) {
            val authoredLocally = localAuthorPublisherId != null &&
                existing.publisherId.isNotEmpty() &&
                existing.publisherId == localAuthorPublisherId
            if (!authoredLocally) {
                return PluginVersionDecision.Reject("已安装签名版本，不能用未签名包覆盖")
            }
        }
        if (existing.signed && candidate.signed) {
            val samePublisher = existing.publisherId.isNotEmpty() &&
                existing.publisherId == candidate.publisherId &&
                existing.publisherKeyFingerprint.isNotEmpty() &&
                existing.publisherKeyFingerprint.equals(
                    candidate.publisherKeyFingerprint,
                    ignoreCase = true,
                )
            if (!samePublisher) {
                return PluginVersionDecision.Reject("插件发行者与已安装版本不一致，拒绝覆盖安装")
            }
        }

        val installedVersion = PluginVersion.parse(existing.version)
            ?: return PluginVersionDecision.Allow // 旧记录版本号不规范时不阻止升级到规范版本
        if (candidateVersion < installedVersion && !allowDowngrade) {
            return PluginVersionDecision.Reject(
                "新版本 $candidateVersion 低于已安装的 $installedVersion，已拒绝降级安装",
            )
        }
        return PluginVersionDecision.Allow
    }
}
