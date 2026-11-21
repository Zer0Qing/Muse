package io.zer0.muse.backup

import java.security.SecureRandom
import java.util.Arrays
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

    /** H-SEC-5: NIST SP 800-132 推荐最小 210,000 次(SHA-256)。提升抗暴力破解强度。 */
    private const val PBKDF2_ITERATIONS = 210_000
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
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
            offset += MAGIC.length
            out[offset] = VERSION
            offset += 1
            salt.copyInto(out, offset)
            offset += SALT_LENGTH
            iv.copyInto(out, offset)
            offset += IV_LENGTH
            ciphertext.copyInto(out, offset)
        }
    }


    /**
     * 流式加密：把明文流包成密文流，头格式与 [encrypt] 完全一致。
     *
     * ## 为什么不能直接复用 [encrypt]
     *
     * [encrypt] 一次性吃 `ByteArray`。大备份（10000+ 消息）走它等于把
     * "全量对象 + 巨串 + 字节数组"三份内存同时驻留 —— 那正是 [writeNdJson]
     * 逐行流式写出要避免的 OOM。
     *
     * 这里用 `CipherOutputStream` 边写边加密：内存峰值只有一行 NDJSON，
     * 而输出格式与 [decrypt] 完全兼容（magic + version + salt + iv + ciphertext）。
     *
     * 调用方负责 `close()` —— 那一步才会写出 GCM tag。
     */
    fun encryptingStream(
        out: java.io.OutputStream,
        password: String,
    ): java.io.OutputStream {
        require(password.isNotEmpty()) { "backupPassword must not be empty" }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        // 头必须先写：它不参与加密，读取侧靠它判断格式并还原密钥。
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(VERSION))
        out.write(salt)
        out.write(iv)
        out.flush()
        return javax.crypto.CipherOutputStream(out, cipher)
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
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = data.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
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
        // H-SEC-6: 生成密钥后立即复制并清零敏感字节,防止原始 secret 残留在堆中
        val rawSecret = factory.generateSecret(spec).encoded
        val keyBytes = rawSecret.copyOf(rawSecret.size)
        Arrays.fill(rawSecret, 0.toByte())
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }
}
