package io.zer0.muse.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.120: 云备份 AES-256-GCM 加解密。
 *
 * 密钥派生:PBKDF2-HMAC-SHA256(password, salt, iterations=100000, keyLen=256bit)
 * 加密算法:AES-256-GCM(12B IV + 128bit authTag)
 *
 * 文件格式:[magic(4B "MENC")] [version(1B)] [salt(16B)] [iv(12B)] [ciphertext+authTag]
 *
 * 跨设备:用户在新设备输入相同 backupPassword 即可解密(salt 随密文一起存储)。
 * 兼容性:importFromCloud 检测 magic header,无 magic 视为旧版明文备份,原样处理。
 */
object BackupCrypto {
    private const val MAGIC = "MENC"
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    /** 加密,返回带 magic header 的完整字节流。password 为空时抛 IllegalArgumentException。 */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "backupPassword must not be empty" }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        // 拼装: magic(4) + version(1) + salt(16) + iv(12) + ciphertext
        return ByteArray(MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH + ciphertext.size).also { out ->
            var offset = 0
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, offset); offset += MAGIC.length
            out[offset] = VERSION; offset += 1
            salt.copyInto(out, offset); offset += SALT_LENGTH
            iv.copyInto(out, offset); offset += IV_LENGTH
            ciphertext.copyInto(out, offset)
        }
    }

    /** 解密。data 须为 [encrypt] 输出格式。password 错误或数据损坏抛异常。 */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "backupPassword must not be empty" }
        require(data.size > MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH) { "encrypted data too short" }
        val magic = String(data, 0, MAGIC.length, Charsets.US_ASCII)
        require(magic == MAGIC) { "invalid magic header: $magic" }
        val version = data[MAGIC.length]
        require(version == VERSION) { "unsupported version: $version" }
        var offset = MAGIC.length + 1
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH); offset += SALT_LENGTH
        val iv = data.copyOfRange(offset, offset + IV_LENGTH); offset += IV_LENGTH
        val ciphertext = data.copyOfRange(offset, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** 判断字节流是否为本工具加密的格式(用于 importFromCloud 兼容旧明文备份)。 */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size <= MAGIC.length) return false
        val magic = runCatching { String(data, 0, MAGIC.length, Charsets.US_ASCII) }.getOrNull()
        return magic == MAGIC
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
