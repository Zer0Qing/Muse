package io.zer0.muse.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: 备份加密/解密往返与格式识别。
 */
class BackupCryptoTest {

    @Test
    fun `encrypt then decrypt restores original bytes`() {
        val plaintext = "备份内容 with 中文 and apiKey=sk-secret-test".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(plaintext, "backup-password")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(plaintext))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(encrypted, "backup-password"))
    }

    @Test
    fun `wrong password is rejected`() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct")
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong")
        }
    }

    @Test
    fun `empty password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("secret".toByteArray(), "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt("MENC".toByteArray(), "")
        }
    }
}
