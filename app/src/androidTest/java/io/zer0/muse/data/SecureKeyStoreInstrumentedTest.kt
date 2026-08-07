package io.zer0.muse.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R-TEST-03/04: MuMu 设备面验证。
 *
 * - SecureKeyStore 走真实 Android Keystore 加解密往返
 * - BackupCrypto 真实设备 AES-GCM 往返
 * - SettingsSnapshotPolicy 在真实环境排除敏感键
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    @Test
    fun keystoreEncryptDecryptRoundTrip() = runBlocking {
        val plain = "sk-test-${System.currentTimeMillis()}"
        val encrypted = SecureKeyStore.encrypt(plain)
        assertTrue("应带 enc_v1: 前缀", encrypted.startsWith("enc_v1:"))
        assertFalse("密文不应含明文", encrypted.contains(plain))
        assertEquals(plain, SecureKeyStore.decrypt(encrypted))
    }

    @Test
    fun keystorePassthroughForLegacyPlainAndEmpty() = runBlocking {
        assertEquals("", SecureKeyStore.decrypt(""))
        assertEquals("legacy", SecureKeyStore.decrypt("legacy"))
        assertEquals("", SecureKeyStore.encrypt(""))
    }

    @Test
    fun backupCryptoRoundTripOnDevice() {
        val plain = "backup-json-${System.currentTimeMillis()}"
        val encrypted = BackupCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), "pass-123")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertEquals(plain, String(BackupCrypto.decrypt(encrypted, "pass-123"), Charsets.UTF_8))
    }

    @Test
    fun snapshotPolicyExcludesSensitiveKeysOnDevice() {
        val raw = mapOf(
            "active_provider_id" to "openai",
            "provider_1_api_key" to "sk-secret",
            "mcp_token_x" to "jwt-secret",
            "theme_mode" to "mono",
        )
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertEquals(setOf("active_provider_id", "theme_mode"), safe.keys)
    }
}
