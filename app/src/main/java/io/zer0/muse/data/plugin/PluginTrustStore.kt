package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 外部插件发行者信任根。
 *
 * 信任根只保存在 Android 的 [Context.noBackupFilesDir]，不写入项目目录、插件包、
 * 普通设置或备份。导入包中的公钥只是待验证的身份声明；只有用户明确选择“信任发行者”
 * 后才会写入此处。存储内容仅包含公钥和指纹，不包含任何私钥。
 */
class PluginTrustStore internal constructor(
    private val storageFile: File,
) {

    /** Android 运行时使用的私有、且不进入备份的信任根存储。 */
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, TRUST_ROOT_FILE_NAME),
    )

    @Serializable
    data class TrustedPublisher(
        val publisherId: String,
        val publicKey: String,
        val fingerprint: String,
        val trustedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class TrustRegistry(
        val publishers: List<TrustedPublisher> = emptyList(),
    )

    private val lock = Any()

    @Volatile
    private var cached: List<TrustedPublisher> = load()

    /** 返回当前信任根的公开摘要；不会返回任何私钥材料。 */
    fun list(): List<TrustedPublisher> = cached

    /** 查找指定发行者当前绑定的公钥。 */
    fun find(publisherId: String): TrustedPublisher? =
        cached.firstOrNull { it.publisherId == publisherId }

    /**
     * 明确登记发行者公钥。
     *
     * 同一个发行者 ID 一旦登记，不允许被另一把公钥覆盖；轮换必须使用新的发行者 ID，
     * 避免被篡改的包借“更新公钥”绕过既有信任根。
     */
    fun trust(publisherId: String, publicKey: String): Result<TrustedPublisher> = synchronized(lock) {
        val normalizedId = publisherId.trim()
        if (!PluginSecurityGate.isValidPublisherId(normalizedId)) {
            return@synchronized Result.failure(IllegalArgumentException("发行者 ID 非法"))
        }
        val key = parsePublicKey(publicKey.trim()).getOrElse { error -> return@synchronized Result.failure(error) }
        val normalizedKey = java.util.Base64.getEncoder().encodeToString(key.encoded)
        val fingerprint = PluginSecurityGate.publicKeyFingerprint(key.encoded)
        val existing = cached.firstOrNull { it.publisherId == normalizedId }
        if (existing != null) {
            return@synchronized if (existing.publicKey == normalizedKey) {
                Result.success(existing)
            } else {
                Result.failure(IllegalStateException("发行者 ID 已绑定另一把公钥"))
            }
        }
        val trusted = TrustedPublisher(
            publisherId = normalizedId,
            publicKey = normalizedKey,
            fingerprint = fingerprint,
        )
        val previous = cached
        val next = cached + trusted
        return@synchronized try {
            persist(next)
            cached = next
            Result.success(trusted)
        } catch (error: Exception) {
            cached = previous
            Result.failure(error)
        }
    }

    /** 移除一个发行者信任根；删除后其插件必须再次经过明确的信任决定。 */
    fun revoke(publisherId: String): Boolean = synchronized(lock) {
        val next = cached.filterNot { it.publisherId == publisherId }
        if (next.size == cached.size) return@synchronized false
        persist(next)
        cached = next
        true
    }

    /** 使用本机信任根验证发行者身份；未知发行者不会被隐式加入。 */
    fun isTrusted(signature: PluginSignature): Boolean {
        val trusted = find(signature.publisherId) ?: return false
        return trusted.publicKey == signature.publicKey
    }

    private fun parsePublicKey(encoded: String): Result<PublicKey> = runCatching {
        require(encoded.isNotBlank()) { "发行者公钥为空" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_PUBLIC_KEY_BYTES) { "发行者公钥过大" }
        KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(bytes))
            .also { key ->
                require(key.algorithm == PluginSecurityGate.KEY_ALGORITHM) { "发行者公钥算法不支持" }
            }
    }

    private fun load(): List<TrustedPublisher> {
        if (!storageFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<TrustRegistry>(storageFile.readText()).publishers
                .onEach { parsePublicKey(it.publicKey).getOrThrow() }
                .distinctBy { it.publisherId }
        }.getOrElse { error ->
            AtomicFileStore.quarantine(storageFile, "plugin_trust_root_parse")
            Logger.w("PluginTrustStore", "插件信任根解析失败，已隔离损坏文件", error)
            emptyList()
        }
    }

    private fun persist(publishers: List<TrustedPublisher>) {
        AtomicFileStore.writeText(
            storageFile,
            AppJson.encodeToString(TrustRegistry.serializer(), TrustRegistry(publishers)),
        )
    }

    companion object {
        private const val TRUST_ROOT_FILE_NAME = "muse_plugin_trust_roots.json"
        private const val MAX_PUBLIC_KEY_BYTES = 512
    }
}
