package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.muse.data.plugin.PluginPackageLoader
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.ui.theme.BubbleSkinValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 用 App 自身的验签器验收 `tools/market-signer` 产出的市场产物。
 *
 * 这是「签名工具是否与 App 算法逐字一致」的权威判据：只要本测试通过，
 * 线上目录与插件包就一定能被 App 接受。产物由脚本生成在
 * `app/build/market-signing/out`（构建目录，不入库）；缺失时跳过，
 * 因此 CI 在没有本地签名产物时不会失败。
 */
class MarketSigningFixtureTest {

    private val fixtureRoot = File("build/market-signing/out")

    @Serializable
    private data class CatalogKey(
        val keyId: String,
        val publicKey: String,
        val fingerprint: String,
    )

    @Serializable
    private data class PublicKeys(
        val catalog: CatalogKey,
        val publishers: Map<String, String>,
    )

    @Test
    fun signedArtifactsFromOfflineToolAreAcceptedByApp() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("未找到签名产物，跳过（需先运行 tools/market-signer/sign.py）", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())

        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = mapOf(keys.catalog.keyId to keys.catalog.publicKey),
            nowEpochMs = System.currentTimeMillis(),
        )
        assertTrue("目录验签失败: ${verification.reason}", verification.valid)
        assertFalse(signed.payload.entries.isEmpty())

        signed.payload.entries.forEach { entry ->
            val artifactFile = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin")
            assertTrue("缺少插件包: ${artifactFile.name}", artifactFile.isFile)
            val bytes = artifactFile.readBytes()
            assertEquals("包大小与目录不符: ${entry.id}", entry.artifactBytes, bytes.size.toLong())
            assertEquals("包摘要与目录不符: ${entry.id}", entry.artifactSha256, sha256Hex(bytes))

            val loaded = PluginPackageLoader.loadFromZip(bytes)
            val package_ = (loaded as? PluginPackageLoader.Result.Ok)?.package_
            assertTrue("包加载失败: ${loaded}", package_ != null)
            val decision = PluginSecurityGate.review(package_!!, keys.publishers)

            assertTrue("签名未被信任: ${decision.reason}", decision.isInstallable)
            assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, decision.signature.status)
            assertEquals("内容摘要与目录不符: ${entry.id}", entry.manifestSha256, decision.preview.contentSha256)
            assertEquals(entry.id, decision.preview.id)
            assertEquals(entry.version, decision.preview.version)
            assertEquals(entry.publisherKeyFingerprint, decision.preview.publisherKeyFingerprint)
        }
    }

    @Test
    fun tamperedPackageIsRejectedEvenWithTrustedPublisher() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("未找到签名产物，跳过", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())
        val entry = signed.payload.entries.first()
        val bytes = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin").readBytes()

        // 负向对照：受信发行者 + 被改动的入口代码必须仍然判失败，否则上面的通过毫无意义。
        val loaded = PluginPackageLoader.loadFromZip(bytes) as PluginPackageLoader.Result.Ok
        val tampered = loaded.package_.copy(entryCode = loaded.package_.entryCode + "\n// tampered\n")
        val decision = PluginSecurityGate.review(tampered, keys.publishers)

        assertFalse(decision.isInstallable)
        assertEquals(PluginSecurityGate.SignatureStatus.INVALID, decision.signature.status)
    }

    /**
     * 发布侧公钥必须与安装包内置的官方目录信任根一致。
     *
     * 这是「线上目录能被已发布 App 验签」的守门测试：用错私钥签发目录会在这里失败，
     * 而不是等用户在设备上看到「未知的目录签名密钥」。
     */
    @Test
    fun publishedCatalogIsSignedByBuiltInOfficialRoot() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("未找到签名产物，跳过", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())

        assertEquals(PluginMarketDefaults.CATALOG_ROOT_KEY_ID, keys.catalog.keyId)
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY, keys.catalog.publicKey)
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_FINGERPRINT, keys.catalog.fingerprint)
        assertEquals(PluginMarketDefaults.CATALOG_ID, signed.payload.catalogId)

        // 仅用安装包内置信任根验签，证明开箱即用的市场配置可用。
        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = PluginMarketDefaults.catalogRootKeys,
            nowEpochMs = System.currentTimeMillis(),
        )
        assertTrue("内置信任根无法验证已发布目录: ${verification.reason}", verification.valid)
    }

    /**
     * 皮肤包必须能被宿主的皮肤模型解析并通过宿主校验（取值范围 + WCAG 对比度）。
     *
     * 皮肤是声明式数据，宿主校验不通过时会静默回退内置气泡；如果发布前不检查，
     * 用户装完只会觉得「装了没用」。发布脚本侧有同样的对比度检查，这里是 App 侧的对照。
     */
    @Test
    fun skinPackagesParseAndPassHostValidation() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("未找到签名产物，跳过", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())
        val skinEntries = signed.payload.entries.filter {
            PluginSecurityGate.UI_SKIN_CAPABILITY in it.capabilities
        }
        assumeTrue("目录中没有皮肤包，跳过", skinEntries.isNotEmpty())

        skinEntries.forEach { entry ->
            val bytes = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin").readBytes()
            val loaded = PluginPackageLoader.loadFromZip(bytes) as PluginPackageLoader.Result.Ok
            val skin = loaded.package_.skin
            assertTrue("皮肤包未解析出皮肤: ${entry.id}", skin != null)
            assertEquals("皮肤不应携带可执行入口: ${entry.id}", "", loaded.package_.entryCode)
            val errors = BubbleSkinValidator.validate(skin!!)
            assertTrue("皮肤未通过宿主校验: ${entry.id} ${errors}", errors.isEmpty())
            assertEquals(entry.id, skin.id)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
