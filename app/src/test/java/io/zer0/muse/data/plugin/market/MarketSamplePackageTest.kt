package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginPackageLoader
import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入库样本包的跨端契约回归测试（v2.0.0 市场事故锁定）。
 *
 * 两个样本分别锁定一类字节序列：
 *  · official catalog 样本（color-toolkit 1.0.0，线上目录真实产物，由旧版签名工具产出）——
 *    存量包必须永远能被 App 验签；App 端 manifest 序列化行为一旦漂移（如空默认字段被编码），
 *    本测试立刻失败；
 *  · regenerated tool 样本（修复版 `tools/market-signer/sign.py` 产出，含 contributes
 *    规范化与空 toolCards 跳过）——锁定「签名工具与 App 序列化逐字一致」的重建路径。
 *
 * 重新生成 regenerated 样本：用 `tools/market-signer/sign.py pack` 打一个含 contributes
 * （configuration 一项）与空 toolCards 的包替换资源文件，保持文件名不变即可。
 */
class MarketSamplePackageTest {

    // ── 样本 1：线上官方目录的真实包（存量兼容） ──

    @Test
    fun `official catalog sample passes app signature gate`() {
        val loaded = loadSample("color-toolkit-1.0.0.muse-plugin")

        // 不带本机信任根：必须通过密码学验签（v2.0.0 正是在这一步报“签名与内容不匹配”）。
        val untrusted = PluginSecurityGate.review(loaded)
        assertEquals(
            "官方样本包验签失败: ${untrusted.reason}",
            PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
            untrusted.signature.status,
        )
        assertTrue(untrusted.allowed)

        // 接入其发行者公钥后可安装，内容摘要必须与线上目录登记的 manifestSha256 一致。
        val envelope = checkNotNull(loaded.manifest.signature)
        val trusted = PluginSecurityGate.review(
            loaded,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue("官方样本包应可安装: ${trusted.reason}", trusted.isInstallable)
        assertEquals("museai", trusted.preview.publisherId)
        assertEquals(
            "内容摘要必须与线上目录 manifestSha256 一致",
            "1601d031755e91f920d80a25263abebca024e633444ef0c0f33bff47f33f23ec",
            trusted.preview.contentSha256,
        )
    }

    // ── 样本 2：修复版签名工具的产出（重建一致） ──

    @Test
    fun `regenerated signing tool sample passes app signature gate`() {
        val loaded = loadSample("signer-selftest-b-1.0.0.muse-plugin")

        val untrusted = PluginSecurityGate.review(loaded)
        assertEquals(
            "签名工具重建包验签失败: ${untrusted.reason}",
            PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
            untrusted.signature.status,
        )

        val envelope = checkNotNull(loaded.manifest.signature)
        val trusted = PluginSecurityGate.review(
            loaded,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue(trusted.isInstallable)

        // contributes 的规范化（字段顺序 + 默认值填充）也随之被锁定。
        val contributes = loaded.manifest.contributes
        assertNotNull("样本必须声明 contributes", contributes)
        assertEquals(listOf("token"), checkNotNull(contributes).configuration.map { it.key })

        // 空 toolCards 未进入字节序列：App 读到的是默认空映射。
        assertTrue(loaded.manifest.toolCards.isEmpty())
    }

    private fun loadSample(name: String): PluginPackageLoader.LoadedPluginPackage {
        val stream = checkNotNull(javaClass.getResourceAsStream("/market/$name")) {
            "缺少测试样本 /market/$name"
        }
        val bytes = stream.use { it.readBytes() }
        val loaded = PluginPackageLoader.loadFromZip(bytes)
        val ok = loaded as? PluginPackageLoader.Result.Ok
        checkNotNull(ok) { "样本包加载失败: $name — $loaded" }
        return ok.package_
    }
}
