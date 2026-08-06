package io.zer0.muse.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-03: SecureKeyStore 兼容透传逻辑；Android Keystore 加解密往返需真机（Robolectric 无 AndroidKeyStore）。
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
}
