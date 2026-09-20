package io.zer0.muse.data.plugin.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * 插件市场设置测试：官方目录内置、可被覆盖但信任根不可被削弱。
 *
 * 遵循 Store 测试约定：每个用例先清掉上一个用例留下的覆盖与自定义信任根，保证顺序无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginMarketSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings get() = PluginMarketSettings(context)

    private fun reset() = runBlocking {
        settings.resetCatalogUrl()
        settings.catalogRootKeys().keys
            .filter { it !in PluginMarketDefaults.catalogRootKeys }
            .forEach { settings.removeCatalogRootKey(it) }
    }

    private fun generatedPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }

    @Test
    fun marketWorksOutOfTheBoxWithBuiltInOfficialCatalog() = runBlocking {
        reset()

        assertEquals(PluginMarketDefaults.CATALOG_URL, settings.catalogUrl())
        assertEquals("", settings.catalogUrlOverride())
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    // ── P0-9: 目录信任根可撤 ──

    @Test
    fun removeCatalogRootKey_removesUserRootOnly() = runBlocking {
        reset()

        settings.saveCatalogRootKey("user-root", generatedPublicKey())
        assertTrue(settings.catalogRootKeys().containsKey("user-root"))

        settings.removeCatalogRootKey("user-root")

        assertEquals("自定义信任根撤销后应只剩内置根", PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun removeCatalogRootKey_cannotRemoveBuiltInRoot() = runBlocking {
        reset()

        val builtinId = PluginMarketDefaults.catalogRootKeys.keys.first()
        settings.removeCatalogRootKey(builtinId)

        assertEquals("内置官方信任根不可移除", PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun userOverrideWinsAndCanBeResetToOfficial() = runBlocking {
        reset()

        settings.saveCatalogUrl("https://mirror.example.com/catalog.json")
        assertEquals("https://mirror.example.com/catalog.json", settings.catalogUrl())
        assertEquals("https://mirror.example.com/catalog.json", settings.catalogUrlOverride())
        // 覆盖目录不影响官方信任根：它始终参与验签。
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())

        settings.resetCatalogUrl()
        assertEquals(PluginMarketDefaults.CATALOG_URL, settings.catalogUrl())
    }

    @Test
    fun builtInRootKeyCannotBeOverwrittenOrRemoved() = runBlocking {
        reset()

        val replaced = settings.saveCatalogRootKey(
            PluginMarketDefaults.CATALOG_ROOT_KEY_ID,
            generatedPublicKey(),
        )
        assertTrue("内置 keyId 必须拒绝写入", replaced.isFailure)

        settings.removeCatalogRootKey(PluginMarketDefaults.CATALOG_ROOT_KEY_ID)
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun userRootKeysAreMergedWithBuiltInKeys() = runBlocking {
        reset()

        val custom = generatedPublicKey()
        assertTrue(settings.saveCatalogRootKey("my-root", custom).isSuccess)

        val effective = settings.catalogRootKeys()
        assertEquals(custom, effective["my-root"])
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY, effective[PluginMarketDefaults.CATALOG_ROOT_KEY_ID])

        settings.removeCatalogRootKey("my-root")
        assertFalse(settings.catalogRootKeys().containsKey("my-root"))
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun invalidPublicKeyIsRejectedAndKeepsTrustRootsIntact() = runBlocking {
        reset()

        val result = settings.saveCatalogRootKey("bad-root", "not-a-key")
        assertTrue(result.isFailure)
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun exposeUrlFlowMatchesSuspendAccessor() = runBlocking {
        reset()
        settings.saveCatalogUrl("https://mirror.example.com/catalog.json")

        assertEquals(settings.catalogUrl(), settings.catalogUrlFlow.first())
        settings.resetCatalogUrl()
    }
}
