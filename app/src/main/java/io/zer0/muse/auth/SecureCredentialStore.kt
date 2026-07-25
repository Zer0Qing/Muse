package io.zer0.muse.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P2-11: 安全凭证存储,基于 Android Keystore + 普通 SharedPreferences(加密后落盘)。
 *
 * 与普通 API Key(存在 SettingsRepository.providers Flow 中)物理隔离,
 * OAuth access_token / refresh_token 存此处,降低泄露风险。
 *
 * 实现:项目无 androidx.security:security-crypto 依赖,采用 Keystore 直接加密 + 普通 SP 兜底,
 *      与既有 [io.zer0.muse.data.SecureKeyStore] 风格保持一致。
 *
 * 加密:AES-256-GCM,密钥由 Android Keystore 管理(不可导出,卸载即销毁)
 * 存储:`"enc_v1:" + Base64(iv + ciphertext + authTag)` 写入 SharedPreferences
 *
 * 安全保证:
 *  - 不在日志中输出 token 明文(只输出长度/前 4 字符做调试)
 *  - 加密失败不静默回退明文(写入被放弃,调用方读取时返回 null,用户需重新登录)
 *  - 解密失败返回 null(用户需重新登录),不抛异常以避免崩溃
 */
class SecureCredentialStore(context: Context) {

    private val masterKeyAlias: String = "muse_oauth_master_key"

    /** 加密 SP 实例(独立于其他模块的 SP,实现物理隔离)。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 初始化主密钥(Android Keystore 中生成,AES256-GCM)。返回 alias 便于调用方观察。 */
    private fun ensureMasterKey(): String {
        getOrCreateKey()
        return masterKeyAlias
    }

    /** 加密存储 OAuth token(providerId → TokenBundle)。 */
    suspend fun storeOAuthToken(providerId: String, bundle: TokenBundle) {
        if (providerId.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                ensureMasterKey()
                val json = AppJson.encodeToString(TokenBundle.serializer(), bundle)
                val encrypted = encrypt(json)
                prefs.edit().putString(providerId, encrypted).apply()
                // 维护 providerId 索引(便于 listProviderIds 不遍历所有 SP key)
                val ids = readIndex().toMutableSet()
                ids.add(providerId)
                writeIndex(ids)
                Logger.i(TAG, "OAuth token 已存储: provider=${maskId(providerId)}, len=${json.length}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 加密失败绝不静默回退明文;但也不能让 OAuth 流程崩溃,记录错误日志
                Logger.w(TAG, "storeOAuthToken 失败: ${truncateForLog(t.message)}")
            }
        }
    }

    /** 读取 OAuth token。无记录或解密失败返回 null。 */
    suspend fun getOAuthToken(providerId: String): TokenBundle? {
        if (providerId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val encrypted = prefs.getString(providerId, null) ?: return@withContext null
            val json = decrypt(encrypted) ?: return@withContext null
            runCatching {
                AppJson.decodeFromString(TokenBundle.serializer(), json)
            }.onFailure {
                Logger.w(TAG, "getOAuthToken 反序列化失败: ${truncateForLog(it.message)}")
            }.getOrNull()
        }
    }

    /** 删除 OAuth token。 */
    suspend fun deleteOAuthToken(providerId: String) {
        if (providerId.isBlank()) return
        withContext(Dispatchers.IO) {
            prefs.edit().remove(providerId).apply()
            val ids = readIndex().toMutableSet()
            ids.remove(providerId)
            writeIndex(ids)
            Logger.i(TAG, "OAuth token 已删除: provider=${maskId(providerId)}")
        }
    }

    /** 列出所有已存储 OAuth 的 providerId。 */
    suspend fun listProviderIds(): List<String> = withContext(Dispatchers.IO) {
        readIndex()
    }

    // ── 内部工具(Keystore 加解密 + SP 索引维护) ─────────────────────────

    /** 获取或创建 AES-256 密钥(由 Android Keystore 托管)。 */
    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(masterKeyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            masterKeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    /** 加密明文,返回 `"enc_v1:" + Base64(iv + ciphertext + authTag)`。 */
    private fun encrypt(plain: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // GCM 自动生成 12B IV
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    /** 解密。无前缀视为明文旧数据原样返回;解密失败返回 null。 */
    private fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val encoded = stored.removePrefix(PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherBytes = combined.copyOfRange(IV_LENGTH, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.onFailure {
            Logger.w(TAG, "decrypt 失败: ${truncateForLog(it.message)}")
        }.getOrNull()
    }

    /** providerId 索引(避免遍历所有 SP key 来判断哪些是 OAuth token)。 */
    private fun readIndex(): List<String> {
        val raw = prefs.getString(KEY_INDEX, "") ?: ""
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun writeIndex(ids: Set<String>) {
        prefs.edit().putString(KEY_INDEX, ids.joinToString(",")).apply()
    }

    /** 截断日志消息,避免泄露 token 内容或撑爆日志文件。 */
    private fun truncateForLog(msg: String?): String {
        if (msg == null) return ""
        return if (msg.length > MAX_LOG_MSG_LENGTH) msg.substring(0, MAX_LOG_MSG_LENGTH) + "..." else msg
    }

    /** 日志安全:providerId 只输出前 4 字符 + 长度,避免关联性推断。 */
    private fun maskId(id: String): String {
        return if (id.length <= 4) id else "${id.take(4)}***(${id.length})"
    }

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val PREFS_NAME = "muse_oauth_store"
        private const val KEY_INDEX = "__provider_ids__"
        private const val PREFIX = "enc_v1:"
        private const val IV_LENGTH = 12 // GCM 推荐 12 字节 IV
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MAX_LOG_MSG_LENGTH = 200
        private val lock = Any()
    }
}

/**
 * OAuth token bundle(标准 OAuth 2.0 token response 投影)。
 *
 * 由 [SecureCredentialStore] 加密存储, [OAuthManager] 在登录成功后写入、
 * 在 [OAuthManager.refreshTokenIfNeeded] 中读取并更新。
 *
 * @param accessToken 访问令牌(Bearer)
 * @param refreshToken 刷新令牌(部分供应商不返回,可空)
 * @param expiresAt 过期时间(epoch millis,0 表示不过期/未知)
 * @param scope 实际授权范围(可能与请求的不同)
 */
@Serializable
data class TokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0, // epoch millis
    val scope: String = "",
)
