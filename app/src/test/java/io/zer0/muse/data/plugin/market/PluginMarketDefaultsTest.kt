package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 内置官方目录常量的自检。
 *
 * 内置值写错的表现是「用户端目录一律被拒绝」，而这类错误在编译期看不出来，
 * 因此这里把公钥的合法性、规范形式与指纹都固定下来：任何手改都必须让本测试失败。
 */
class PluginMarketDefaultsTest {

    @Test
    fun builtInCatalogUrlIsHttpsOnOfficialHost() {
        val url = PluginMarketDefaults.CATALOG_URL
        assertTrue("必须是 https", url.startsWith("https://"))
        assertEquals("https://museai.ltd/muse-market/signed-catalog.json", url)
    }

    @Test
    fun builtInRootKeyIdMatchesBakedKeyMap() {
        assertEquals(
            mapOf(PluginMarketDefaults.CATALOG_ROOT_KEY_ID to PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY),
            PluginMarketDefaults.catalogRootKeys,
        )
    }

    @Test
    fun builtInRootKeyIsCanonicalP256PublicKeyWithExpectedFingerprint() {
        val encoded = PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY
        // App 写入/比较信任根时要求标准 Base64；内置值必须已经是规范形式，否则规范化解出不同字符串。
        val canonical = PluginSecurityGate.canonicalPublicKey(encoded).getOrThrow()
        assertEquals("内置公钥必须是规范的标准 Base64", encoded, canonical)

        val der = Base64.getDecoder().decode(canonical)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val ecKey = key as? ECPublicKey ?: error("内置公钥不是 EC 公钥")
        assertEquals("必须是 P-256", 256, ecKey.params.curve.field.fieldSize)

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it) }
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_FINGERPRINT, fingerprint)
    }
}
