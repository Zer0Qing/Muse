package io.zer0.muse.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.crypto.AEADBadTagException

class FramedBackupCryptoTest {
    @Test
    fun multipleFramesRoundTripAndTheHeaderIsRecognized() {
        val content = ByteArray(850_000) { (it * 11).toByte() }
        val encrypted = encrypt(content)
        assertTrue(BackupCrypto.isEncrypted(encrypted.copyOf(4)))
        assertArrayEquals(content, decrypt(encrypted))
    }

    @Test
    fun rejectsWrongPasswordMissingFinalFrameAndTrailingBytes() {
        val encrypted = encrypt(ByteArray(300_000) { it.toByte() })
        assertThrows(AEADBadTagException::class.java) { decrypt(encrypted, "incorrect") }
        assertThrows(EOFException::class.java) { decrypt(encrypted.copyOf(encrypted.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(encrypted + 1.toByte()) }
    }

    private fun encrypt(content: ByteArray): ByteArray = ByteArrayOutputStream().let { output ->
        FramedBackupCrypto.encryptingStream(output, "fixture-password").use { it.write(content) }
        output.toByteArray()
    }

    private fun decrypt(content: ByteArray, password: String = "fixture-password"): ByteArray =
        FramedBackupCrypto.decryptingStream(content.inputStream(5, content.size - 5), password).use { it.readBytes() }
}
