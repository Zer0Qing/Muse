package io.zer0.muse.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-03: SecureKeyStore 兼容透传逻辑；Android Keystore 加解密往返需真机（Robolectric 无 AndroidKeyStore）。
 *
 * Phase 3 (可靠性 P1): 追加 [SecureKeyCipher.decryptOrNull] 显式失败语义测试 —
 * 解密失败返回 null(原因由实现记录),兼容入口 [SecureKeyStore.decrypt] 保持空串语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureKeyStoreTest {

    @Test
    fun `plain text passes through decrypt`() = runBlocking {
        assertEquals("legacy-plain", SecureKeyStore.decrypt("legacy-plain"))
    }

    @Test
    fun `empty value is not encrypted`() = runBlocking {
        assertEquals("", SecureKeyStore.encrypt(""))
        assertEquals("", SecureKeyStore.decrypt(""))
    }

    private class FakeCipher : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String =
            if (plain.isEmpty()) plain else "fake:${plain.reversed()}"
        override suspend fun decrypt(stored: String): String =
            if (stored.startsWith("fake:")) stored.removePrefix("fake:").reversed() else stored

        /** Phase 3 (P1): "fake:broken" 模拟密钥失效/数据损坏 → 显式 null。 */
        override suspend fun decryptOrNull(stored: String): String? =
            if (stored == "fake:broken") null else decrypt(stored)
    }

    @Test
    fun `delegate can be swapped for jvm tests`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertEquals("hello", SecureKeyStore.decrypt(SecureKeyStore.encrypt("hello")))
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun `decryptOrNull returns null on failure instead of empty string`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertNull("解密失败必须显式返回 null", SecureKeyStore.decryptOrNull("fake:broken"))
            // 兼容入口保持旧语义(空串),既有调用方守卫不变
            assertEquals("", SecureKeyStore.decrypt("fake:broken"))
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun `decryptOrNull passes through empty and legacy plaintext`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertEquals("", SecureKeyStore.decryptOrNull(""))
            assertEquals("legacy-plain", SecureKeyStore.decryptOrNull("legacy-plain"))
            assertEquals("hello", SecureKeyStore.decryptOrNull(SecureKeyStore.encrypt("hello")))
        } finally {
            SecureKeyStore.delegate = original
        }
    }
}
