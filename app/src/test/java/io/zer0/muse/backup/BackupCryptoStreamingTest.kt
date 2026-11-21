package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 流式加密的输出必须与一次性加密完全兼容。
 *
 * 这条守的是 BLK-6：本地导出此前只写明文，用户在云备份页设了密码却在
 * "导出备份"拿到可被任何文件管理器读开的 JSON。
 */
class BackupCryptoStreamingTest {

    private val password = "correct horse battery staple"

    @Test
    fun `streaming output decrypts with the same password`() {
        val plaintext = "{\"type\":\"meta\"}\n{\"type\":\"session\"}\n".toByteArray()
        val sink = ByteArrayOutputStream()

        val encrypting = BackupCrypto.encryptingStream(sink, password)
        encrypting.write(plaintext)
        encrypting.close()

        val cipherBytes = sink.toByteArray()
        assertTrue("输出必须是加密格式", BackupCrypto.isEncrypted(cipherBytes))

        val decrypted = BackupCrypto.decrypt(cipherBytes, password)
        assertEquals(String(plaintext), String(decrypted))
    }

    @Test
    fun `streaming output is not readable as plaintext`() {
        val secret = "我的聊天记录不该被任何文件管理器读开"
        val sink = ByteArrayOutputStream()
        val encrypting = BackupCrypto.encryptingStream(sink, password)
        encrypting.write(secret.toByteArray(Charsets.UTF_8))
        encrypting.close()

        val raw = String(sink.toByteArray(), Charsets.ISO_8859_1)
        assertFalse("明文不得出现在密文流里", raw.contains(secret))
    }

    @Test
    fun `wrong password fails instead of returning garbage`() {
        val sink = ByteArrayOutputStream()
        val encrypting = BackupCrypto.encryptingStream(sink, password)
        encrypting.write("data".toByteArray())
        encrypting.close()

        var threw = false
        try {
            BackupCrypto.decrypt(sink.toByteArray(), "wrong password")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("密码错必须失败，不能返回乱码", threw)
    }

    @Test
    fun `empty password is refused`() {
        var threw = false
        try {
            BackupCrypto.encryptingStream(ByteArrayOutputStream(), "")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("空密码不该产出一个看起来加密、实则没有密钥的文件", threw)
    }
}
