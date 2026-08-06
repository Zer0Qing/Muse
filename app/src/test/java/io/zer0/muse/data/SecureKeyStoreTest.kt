package io.zer0.muse.data

import kotlinx.coroutines.test.runTest
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
    fun `plain text passes through decrypt`() = runTest {
        assertEquals("legacy-plain", SecureKeyStore.decrypt("legacy-plain"))
    }

    @Test
    fun `empty value is not encrypted`() = runTest {
        assertEquals("", SecureKeyStore.encrypt(""))
        assertEquals("", SecureKeyStore.decrypt(""))
    }
}
