package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * 外部插件发行者信任根测试。
 *
 * 覆盖：显式信任、同 ID 换钥拒绝、非规范 Base64 归一化、撤销和损坏文件隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginTrustStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun trustBindsPublisherKeyAndIsScopedToPublisherId() {
        val store = store()
        val key = publicKey()
        val result = store.trust("publisher.test", key)

        assertTrue(result.isSuccess)
        assertNotNull(store.find("publisher.test"))
        assertTrue(store.isTrusted(PluginSignature("publisher.test", key, signature = "sig")))
        assertFalse(store.isTrusted(PluginSignature("publisher.other", key, signature = "sig")))
    }

    @Test
    fun trustRejectsSamePublisherIdWithAnotherKey() {
        val store = store()
        store.trust("publisher.test", publicKey()).getOrThrow()

        val second = store.trust("publisher.test", publicKey())

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message?.contains("另一把公钥") == true)
    }

    @Test
    fun trustRejectsInvalidPublisherId() {
        val store = store()

        val result = store.trust("bad id!", publicKey())

        assertTrue(result.isFailure)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun trustRejectsNonCanonicalBase64EncodingAndKeepsTrustRootEmpty() {
        val store = store()
        val wrapped = publicKey().chunked(16).joinToString("\n")

        val result = store.trust("publisher.test", wrapped)

        assertTrue(result.isFailure)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun revokeRemovesPublisherBinding() {
        val store = store()
        val key = publicKey()
        store.trust("publisher.test", key).getOrThrow()

        assertTrue(store.revoke("publisher.test"))
        assertNull(store.find("publisher.test"))
        assertFalse(store.isTrusted(PluginSignature("publisher.test", key, signature = "sig")))
        assertFalse(store.revoke("publisher.test"))
    }

    @Test
    fun corruptedTrustRootIsQuarantinedInsteadOfTrustingAnything() {
        val file = trustFile()
        file.parentFile?.mkdirs()
        file.writeText("{not-json")

        val store = PluginTrustStore(file)

        assertTrue(store.list().isEmpty())
        assertFalse(file.exists())
        assertTrue(
            file.parentFile?.listFiles()?.any { it.name.startsWith("${file.name}.corrupt-") } == true,
        )
    }

    private fun store(): PluginTrustStore = PluginTrustStore(trustFile())

    private fun trustFile(): File = File(
        context.cacheDir,
        "trust_${System.nanoTime()}_${(0..9999).random()}.json",
    )

    private fun publicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }
}
