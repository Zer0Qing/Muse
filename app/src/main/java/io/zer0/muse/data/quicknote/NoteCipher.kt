package io.zer0.muse.data.quicknote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * B3-06: 快捷笔记真实 AES-GCM 加密。
 *
 * 密钥保存在 Android Keystore,应用数据被拖出也无法直接还原明文。
 * 密文格式: base64(iv):base64(ciphertext),GCM tag 已包含在 ciphertext 中。
 */
class NoteCipher {
    private val alias = "muse_quick_notes_aes_gcm"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "invalid ciphertext" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
