package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** 当前可用目录快照；[signedCatalog] 为 null 时 [entries] 必为空。 */
internal data class CatalogSnapshot(
    val signedCatalog: SignedPluginCatalog?,
    val entries: List<PluginCatalogEntry>,
    /** 缓存存在但不可用（过期/验签失败/损坏）时的原因，供 UI 展示。 */
    val error: String? = null,
)

/** 已记录的 sequence：[byCatalog] 为分目录记录，[legacy] 为旧版全局值（归属待定）。 */
internal data class RecordedSequences(
    val byCatalog: Map<String, Long>,
    val legacy: Long?,
)

/** 刷新结果；[Rejected] 不会改动缓存，UI 应保留上一次可用条目并展示原因。 */
internal sealed class CatalogRefreshResult {
    /** 目录 URL 未配置。 */
    data object NotConfigured : CatalogRefreshResult()

    /** 拉取并通过验签，缓存已推进；sequence 回退与过期都到不了这里。 */
    data class Updated(val signedCatalog: SignedPluginCatalog) : CatalogRefreshResult()

    /** 拉取或校验失败，缓存与已接受 sequence 保持不变。 */
    data class Rejected(val reason: String) : CatalogRefreshResult()
}

/**
 * 插件目录仓库：负责目录缓存、已接受 sequence 记录与过期/回退拒绝。
 *
 * 安全边界：
 *  - 所有条目只来自 [PluginCatalogVerifier] 验证通过的签名目录；
 *  - 已接受 sequence 与目录一起持久化在 Android 私有目录，重启后仍拒绝回退；
 *  - 刷新失败/被拒时绝不覆盖上一份已验证缓存（失败关闭，而不是清空或降级）；
 *  - 信任根公钥由 [trustRootKeys] 提供（应用/用户显式配置），绝不从目录自身读取。
 */
internal class PluginCatalogRepository(
    private val client: PluginCatalogClient,
    private val cacheDir: File,
    private val trustRootKeys: () -> Map<String, String>,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val catalogFile = File(cacheDir, CATALOG_FILE_NAME)
    private val sequenceFile = File(cacheDir, SEQUENCE_FILE_NAME)

    @Volatile
    private var cacheLoaded = false

    /** null 表示无任何已验证目录（未配置/缓存不可用）。 */
    private var verifiedCatalog: SignedPluginCatalog? = null

    /** 每个目录 id 已接受的最大 sequence；缺失表示该目录尚未接受过。 */
    private val acceptedSequences = mutableMapOf<String, Long>()

    /** 上次缓存不可用的原因，用于 UI 解释“为什么市场是空的”。 */
    private var cacheError: String? = null

    /** 读取本地缓存（幂等）；返回缓存中的可用条目或不可用原因。 */
    suspend fun loadCache(): CatalogSnapshot = withContext(Dispatchers.IO) {
        ensureCacheLoaded()
        synchronized(lock) { snapshotLocked() }
    }

    /**
     * 拉取并校验目录。
     *
     * 成功后写入缓存并推进已接受 sequence；失败时缓存与 sequence 保持不变。
     * 调用方需自行保证 [catalogUrl] 来自设置项。
     */
    suspend fun refresh(catalogUrl: String): CatalogRefreshResult = withContext(Dispatchers.IO) {
        val url = catalogUrl.trim()
        if (url.isEmpty()) return@withContext CatalogRefreshResult.NotConfigured
        ensureCacheLoaded()
        val keys = trustRootKeys()
        if (keys.isEmpty()) {
            return@withContext CatalogRefreshResult.Rejected(REASON_NO_TRUST_ROOT)
        }
        val fetched = client.fetch(url)
        val signed = fetched.signedCatalog
            ?: return@withContext CatalogRefreshResult.Rejected(fetched.error ?: "目录拉取失败")
        // P2-25: 校验与提交必须原子(同一锁内) —
        //  原实现先锁内读 sequence → 锁外校验 → 再锁内写回,两个并发 refresh 时,
        //  后提交的一方可能把 sequence 从高值倒回低值(校验基于已过期的 lastAccepted)。
        //  这里把「读最新 accepted + 校验 + 写缓存 + 推进 sequence」整体放进锁内:
        //  两个并发 refresh 串行化,sequence 严格单调不回退。
        val outcome = synchronized(lock) {
            val catalogId = signed.payload.catalogId
            val lastAccepted = acceptedSequences[catalogId]
            val verification = PluginCatalogVerifier.verify(
                signed = signed,
                trustedKeys = keys,
                nowEpochMs = nowEpochMs(),
                lastAcceptedSequence = lastAccepted,
            )
            if (!verification.valid) {
                val reason = translateReason(verification.reason)
                Logger.w(TAG, "目录校验被拒绝: ${verification.reason}")
                return@synchronized CatalogRefreshResult.Rejected(reason)
            }
            val updated = acceptedSequences.toMutableMap().apply { this[catalogId] = signed.payload.sequence }
            try {
                persist(signed, updated)
            } catch (error: Exception) {
                Logger.e(TAG, "目录缓存写入失败", error)
                return@synchronized CatalogRefreshResult.Rejected(
                    "目录缓存写入失败: ${error.message ?: "unknown"}",
                )
            }
            verifiedCatalog = signed
            acceptedSequences.clear()
            acceptedSequences.putAll(updated)
            cacheError = null
            CatalogRefreshResult.Updated(signed)
        }
        return@withContext outcome
    }

    /** 最近一次通过验签的目录；[loadCache]/[refresh] 之前返回 null。 */
    fun currentCatalog(): SignedPluginCatalog? = synchronized(lock) { verifiedCatalog }

    /** 指定目录已接受的 sequence；null 表示该目录尚未接受过。 */
    fun lastAcceptedSequence(catalogId: String): Long? = synchronized(lock) { acceptedSequences[catalogId] }

    /**
     * P0-9: 清空市场缓存条目与已接受 sequence。
     *
     * 撤销目录信任根后调用:旧缓存是依据已被移除的信任根验证的,继续保留会误导用户
     * (界面仍显示旧条目)。清空后下次 [refresh] 用当前信任根重新验证,拒绝则无条目。
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                catalogFile.delete()
                sequenceFile.delete()
            }.onFailure { error -> Logger.w(TAG, "市场缓存清理失败(忽略)", error) }
            verifiedCatalog = null
            acceptedSequences.clear()
            cacheError = null
            cacheLoaded = true
        }
    }

    private fun ensureCacheLoaded() {
        if (cacheLoaded) return
        synchronized(lock) {
            if (cacheLoaded) return
            loadCacheLocked()
            cacheLoaded = true
        }
    }

    private fun loadCacheLocked() {
        val recorded = readRecordedSequences()
        acceptedSequences.clear()
        acceptedSequences.putAll(recorded.byCatalog)
        cacheError = null
        verifiedCatalog = null
        if (!catalogFile.isFile) return
        val signed = try {
            AppJson.decodeFromString(SignedPluginCatalog.serializer(), catalogFile.readText())
        } catch (error: Exception) {
            Logger.w(TAG, "目录缓存解析失败，已隔离损坏文件", error)
            AtomicFileStore.quarantine(catalogFile, "plugin_catalog_parse")
            cacheError = "目录缓存已损坏，请刷新目录"
            return
        }
        val catalogId = signed.payload.catalogId
        // 旧格式只记录单个全局 sequence（当时只可能有一个目录），归属到缓存里那一个目录 id。
        recorded.legacy?.let { legacy ->
            if (!acceptedSequences.containsKey(catalogId)) acceptedSequences[catalogId] = legacy
        }
        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = trustRootKeys(),
            nowEpochMs = nowEpochMs(),
            lastAcceptedSequence = acceptedSequences[catalogId],
        )
        if (!verification.valid) {
            // 缓存过期/密钥变更时保留该目录的 sequence（继续拒绝回退），但不再对外提供条目。
            cacheError = translateReason(verification.reason)
            return
        }
        verifiedCatalog = signed
        acceptedSequences[catalogId] = signed.payload.sequence
    }

    private fun snapshotLocked(): CatalogSnapshot {
        // 快照时重新按当前时间/信任根验签，避免进程存活期间目录过期仍继续可用。
        val signed = verifiedCatalog
        if (signed != null) {
            val verification = PluginCatalogVerifier.verify(
                signed = signed,
                trustedKeys = trustRootKeys(),
                nowEpochMs = nowEpochMs(),
                lastAcceptedSequence = acceptedSequences[signed.payload.catalogId],
            )
            if (!verification.valid) {
                cacheError = translateReason(verification.reason)
                verifiedCatalog = null
            }
        }
        val current = verifiedCatalog
        return if (current == null) {
            CatalogSnapshot(signedCatalog = null, entries = emptyList(), error = cacheError)
        } else {
            CatalogSnapshot(
                signedCatalog = current,
                entries = current.payload.entries,
                error = null,
            )
        }
    }

    /**
     * 读取各目录已接受的 sequence。
     *
     * 旧版本只记录单个全局 sequence（当时只有一个目录），该值无法自证归属，
     * 由调用方按缓存里的目录 id 归档（见 [loadCacheLocked]）；未知格式按未记录处理。
     */
    private fun readRecordedSequences(): RecordedSequences {
        val text = runCatching { sequenceFile.takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            .orEmpty()
        if (text.isEmpty()) return RecordedSequences(emptyMap(), null)
        text.toLongOrNull()?.let { legacy ->
            return RecordedSequences(emptyMap(), legacy.takeIf { it >= 0 })
        }
        val byCatalog = runCatching { AppJson.decodeFromString(SEQUENCE_SERIALIZER, text) }
            .getOrElse { error ->
                Logger.w(TAG, "目录 sequence 记录解析失败，按未记录处理", error)
                emptyMap()
            }
            .filterValues { it >= 0 }
        return RecordedSequences(byCatalog, null)
    }

    private fun persist(signed: SignedPluginCatalog, sequences: Map<String, Long>) {
        // 先写目录再写 sequence：中途崩溃只会少记一次推进，不会出现“已接受高于缓存”的死锁。
        AtomicFileStore.writeText(
            catalogFile,
            AppJson.encodeToString(SignedPluginCatalog.serializer(), signed),
        )
        AtomicFileStore.writeText(sequenceFile, AppJson.encodeToString(SEQUENCE_SERIALIZER, sequences))
    }

    private fun translateReason(reason: String?): String = when (reason) {
        null -> "目录校验失败"
        "catalog too large" -> "目录体积超过限制"
        "unknown catalog key" -> "未知的目录签名密钥"
        "unsupported catalog metadata" -> "不支持的目录格式"
        "catalog expired" -> "目录已过期，请刷新"
        "catalog sequence rollback" -> "目录版本回退，已拒绝"
        "invalid catalog entry" -> "目录包含非法条目"
        "catalog signature mismatch" -> "目录签名不匹配"
        "catalog signature could not be verified" -> "目录签名无法验证"
        else -> reason
    }

    companion object {
        private const val TAG = "PluginCatalogRepository"

        internal const val CATALOG_FILE_NAME = "catalog.json"
        internal const val SEQUENCE_FILE_NAME = "catalog_sequence"

        /** 未配置目录信任根时的固定拒绝原因，UI 据此提示“配置信任根”。 */
        internal const val REASON_NO_TRUST_ROOT = "未配置目录信任根公钥"

        private val SEQUENCE_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
