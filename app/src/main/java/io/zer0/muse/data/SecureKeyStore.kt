package io.zer0.muse.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.53-A2: 敏感字符串(apiKey / OAuth token)加密存储工具。
 *
 * 基于 Android Keystore 的 AES-256-GCM 对称加密,用于加密 DataStore 中的敏感字段。
 *
 * - 密钥由 Android Keystore 硬件级管理(不可导出,进程隔离,卸载即销毁)
 * - 加密结果格式:`"enc_v1:" + Base64(iv(12B) + ciphertext + authTag(16B))`
 * - 解密时:无 `enc_v1:` 前缀视为明文旧数据,原样透传(迁移兼容)
 * - 首次调用时自动生成密钥(alias = [ALIAS])
 * - 加解密为 IO/Keystore binder 操作,函数声明为 suspend 并用 withContext(Dispatchers.IO) 强制离主线程
 *
 * 迁移策略:
 * - 旧版本明文存储的 apiKey 读取时 decrypt 透传明文,下次写入时 encrypt 自动加密
 * - 用户无感知,无需显式迁移步骤
 * - Keystore 密钥卸载/清数据后丢失,加密数据无法解密(返回空串),用户需重新输入 apiKey
 */
object SecureKeyStore {
    private const val TAG = "SecureKeyStore"
    private const val ALIAS = "muse_api_key_master"
    private const val PREFIX = "enc_v1:"
    private const val IV_LENGTH = 12 // GCM 推荐 12 字节 IV
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val MAX_LOG_MSG_LENGTH = 500

    // M-SK1: 保护 getOrCreateKey 临界区,避免 check-then-create TOCTOU 竞态导致重复生成密钥
    private val lock = Any()

    /**
     * 获取或创建 AES-256 密钥(由 Android Keystore 托管)。
     * 首次调用生成,后续调用从 Keystore 读取。
     *
     * M-SK1: 用 synchronized 保护 check-then-create 临界区,避免并发首调时
     * 两个线程同时观察到无密钥并同时 generateKey(后者会覆盖前者,导致已加密数据无法解密)。
     */
    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * 加密明文,返回 `"enc_v1:" + Base64(iv + ciphertext)`。
     *
     * - 空字符串原样返回(不加密空值)
     * - 已加密(有前缀)的字符串原样返回(避免重复加密)
     * - 加密失败抛出 [KeyStoreException],由调用方处理(H-SK1:绝不回退明文,避免无声破坏安全保证)
     *
     * M-SK2: Keystore 为 binder/磁盘操作,声明为 suspend 并用 withContext(Dispatchers.IO) 强制离主线程。
     */
    suspend fun encrypt(plain: String): String = withContext(Dispatchers.IO) {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return@withContext plain
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv // GCM 自动生成 12B IV
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // iv + cipherBytes(authTag 附加在密文末尾,GCM 规范)
            PREFIX + Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
        } catch (e: CancellationException) {
            // L4: suspend 上下文中必须重抛 CancellationException,否则破坏协程取消语义
            throw e
        } catch (t: Throwable) {
            // H-SK1: 加密失败绝不静默回退明文(否则敏感凭据会以明文落盘),抛异常由调用方处理
            Logger.w(TAG, "encrypt failed: ${truncateForLog(t.message)}")
            throw KeyStoreException("SecureKeyStore encrypt failed: ${truncateForLog(t.message)}", t)
        }
    }

    /**
     * 解密。
     *
     * - 空字符串原样返回
     * - 无 `enc_v1:` 前缀视为明文旧数据,原样透传(迁移兼容)
     * - 解密失败(密钥失效/数据损坏)返回空串(避免崩溃,用户需重新输入)
     *
     * M-SK2: Keystore 为 binder/磁盘操作,声明为 suspend 并用 withContext(Dispatchers.IO) 强制离主线程。
     */
    suspend fun decrypt(stored: String): String = withContext(Dispatchers.IO) {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return@withContext stored
        // L5: 改用 resultOf 替代 runCatching(自定义 Result API,正确重抛 CancellationException)
        resultOf {
            val encoded = stored.removePrefix(PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return@resultOf ""
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherBytes = combined.copyOfRange(IV_LENGTH, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.onError { msg, _ ->
            Logger.w(TAG, "decrypt failed, returning empty: ${truncateForLog(msg)}")
        }.getOrNull() ?: ""
    }

    /** 截断过长的日志消息,避免异常消息(可能含 stacktrace)撑爆日志文件。 */
    private fun truncateForLog(msg: String?): String {
        if (msg == null) return ""
        return if (msg.length > MAX_LOG_MSG_LENGTH) msg.substring(0, MAX_LOG_MSG_LENGTH) + "..." else msg
    }
}
