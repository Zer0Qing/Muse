package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本策略测试：降级、发行者血缘、未签名覆盖、minAppVersion。 */
class PluginVersionPolicyTest {

    private val appVersion = "1.0.89"

    private fun candidate(
        version: String = "1.1.0",
        publisherId: String = "museai",
        fingerprint: String = "a".repeat(64),
        signed: Boolean = true,
        minAppVersion: String = "1.0.0",
    ) = PluginInstallCandidate(
        id = "math-toolkit",
        version = version,
        publisherId = publisherId,
        publisherKeyFingerprint = fingerprint,
        signed = signed,
        minAppVersion = minAppVersion,
    )

    private fun installed(
        version: String = "1.0.0",
        publisherId: String = "museai",
        fingerprint: String = "a".repeat(64),
        signed: Boolean = true,
    ) = InstalledPluginIdentity(
        version = version,
        publisherId = publisherId,
        publisherKeyFingerprint = fingerprint,
        signed = signed,
    )

    private fun rejected(decision: PluginVersionDecision): String {
        assertTrue("期望拒绝，实际放行", decision is PluginVersionDecision.Reject)
        return (decision as PluginVersionDecision.Reject).reason
    }

    @Test
    fun freshInstallAndSameVersionReinstallAreAllowed() {
        assertEquals(PluginVersionDecision.Allow, PluginVersionPolicy.evaluate(candidate(), null, appVersion))
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(candidate(version = "1.0.0"), installed(), appVersion),
        )
    }

    @Test
    fun upgradeWithSamePublisherIsAllowed() {
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(candidate(version = "1.1.0"), installed("1.0.0"), appVersion),
        )
    }

    @Test
    fun downgradeIsRejectedUnlessExplicitlyAllowed() {
        val decision = PluginVersionPolicy.evaluate(candidate(version = "0.9.0"), installed("1.0.0"), appVersion)
        assertTrue(rejected(decision).contains("降级"))

        // 回滚入口显式放行。
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "0.9.0"),
                installed("1.0.0"),
                appVersion,
                allowDowngrade = true,
            ),
        )
    }

    @Test
    fun publisherChangeIsRejected() {
        val otherKey = PluginVersionPolicy.evaluate(
            candidate(fingerprint = "b".repeat(64)),
            installed(),
            appVersion,
        )
        assertTrue(rejected(otherKey).contains("发行者"))

        val otherId = PluginVersionPolicy.evaluate(
            candidate(publisherId = "someone-else"),
            installed(),
            appVersion,
        )
        assertTrue(rejected(otherId).contains("发行者"))
    }

    @Test
    fun unsignedPackageCannotOverwriteSignedInstall() {
        val decision = PluginVersionPolicy.evaluate(
            candidate(version = "2.0.0", signed = false),
            installed(),
            appVersion,
        )
        assertTrue(rejected(decision).contains("未签名"))
    }

    @Test
    fun signedPackageMayReplaceUnsignedDraft() {
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "1.0.0"),
                installed(publisherId = "", fingerprint = "", signed = false),
                appVersion,
            ),
        )
    }

    @Test
    fun localAuthorInstallMayBeReplacedByNewDraftForIteration() {
        // 助手迭代自己写的插件：本机作者签名的安装允许被新草稿覆盖（草稿仍需用户再签名）。
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "1.1.0", publisherId = "", fingerprint = "", signed = false),
                installed(version = "1.0.0", publisherId = "local-author", signed = true),
                appVersion,
                localAuthorPublisherId = "local-author",
            ),
        )
        // 但没有传本机作者身份时仍然拒绝（默认关闭，避免误放宽）。
        assertTrue(
            rejected(
                PluginVersionPolicy.evaluate(
                    candidate(version = "1.1.0", signed = false),
                    installed(version = "1.0.0", publisherId = "local-author", signed = true),
                    appVersion,
                ),
            ).contains("未签名"),
        )
        // 第三方已签名安装依然不能被草稿覆盖。
        assertTrue(
            rejected(
                PluginVersionPolicy.evaluate(
                    candidate(version = "1.1.0", signed = false),
                    installed(version = "1.0.0", publisherId = "museai", signed = true),
                    appVersion,
                    localAuthorPublisherId = "local-author",
                ),
            ).contains("未签名"),
        )
    }

    @Test
    fun minAppVersionAboveCurrentAppIsRejected() {
        val decision = PluginVersionPolicy.evaluate(
            candidate(minAppVersion = "1.2.0"),
            null,
            appVersion,
        )
        assertTrue(rejected(decision).contains("App 版本"))
    }

    @Test
    fun malformedVersionsAreRejectedInsteadOfGuessed() {
        assertTrue(
            rejected(PluginVersionPolicy.evaluate(candidate(version = "1.0"), null, appVersion))
                .contains("语义化版本"),
        )
        assertTrue(
            rejected(PluginVersionPolicy.evaluate(candidate(minAppVersion = "latest"), null, appVersion))
                .contains("minAppVersion"),
        )
    }

    @Test
    fun prereleaseUpgradeOverStableIsRejectedAsDowngrade() {
        // 1.0.0-rc.1 < 1.0.0：预发布版本覆盖正式版本同样属于降级。
        val decision = PluginVersionPolicy.evaluate(
            candidate(version = "1.0.0-rc.1"),
            installed("1.0.0"),
            appVersion,
        )
        assertTrue(rejected(decision).contains("降级"))
    }
}
