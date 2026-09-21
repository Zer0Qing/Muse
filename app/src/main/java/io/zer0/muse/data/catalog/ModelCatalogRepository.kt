package io.zer0.muse.data.catalog

import android.content.Context
import io.zer0.ai.core.ModelCatalog
import io.zer0.ai.core.ModelCatalogLoader
import io.zer0.common.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型能力目录仓库 —— 三层数据源 + 降级链。
 *
 * 数据源优先级(高 → 低):
 *  1. [refresh] 从远程拉下来的缓存(filesDir/model-catalog.json)
 *  2. 打进 APK 的内置目录(assets/model-catalog.json)
 *
 * 任何一环失败都不影响 App 运行:读不到缓存用内置,拉取失败**绝不动现有缓存**
 * (与上游"失败保留旧 snapshot"同一取向)。
 *
 * 远程地址固定指向 Muse 自建目录服务;发布新目录只需替换服务器上的 JSON,
 * 客户端启动时静默比对 [ModelCatalog.publishedAt],有新版才落盘。
 */
class ModelCatalogRepository(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ModelCatalogRepo"
        const val REMOTE_URL = "https://museai.ltd/api/model-catalog.json"
        private const val CACHE_FILE = "model-catalog.json"
        private const val ASSET_FILE = "model-catalog.json"
        private const val TIMEOUT_MS = 10_000
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /** 内置目录(APK 自带,保底)。解析失败返回空库。 */
    fun builtIn(): ModelCatalog = runCatching {
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
    }.map { ModelCatalogLoader.loadOrEmpty(it) }
        .getOrElse { ModelCatalog() }

    /** 本地缓存(上次拉取成功的目录);不存在返回 null。 */
    fun cached(): ModelCatalog? {
        val f = cacheFile
        if (!f.isFile) return null
        return runCatching { ModelCatalogLoader.loadOrEmpty(f.readText()) }
            .getOrElse { null }
            ?.takeIf { it.providers.isNotEmpty() }
    }

    /** 当前生效目录:缓存优先,否则内置。 */
    fun current(): ModelCatalog = cached() ?: builtIn()

    /**
     * 拉取远程目录。仅当远程 [ModelCatalog.publishedAt] 与当前生效目录不同才落盘。
     *
     * @return 拉取结果描述(供设置页展示)。
     */
    fun refresh(): RefreshResult {
        val remoteText = runCatching { httpGet(REMOTE_URL) }
            .onFailure { Logger.w(TAG, "拉取目录失败: ${it.message}") }
            .getOrNull()
            ?: return RefreshResult(ok = false, message = "网络请求失败")

        val remote = ModelCatalogLoader.loadOrEmpty(remoteText)
        if (remote.providers.isEmpty()) {
            return RefreshResult(ok = false, message = "远程目录为空或格式不合法")
        }

        val currentPublished = current().publishedAt
        if (currentPublished.isNotBlank() && currentPublished == remote.publishedAt) {
            return RefreshResult(ok = true, updated = false, message = "已是最新(${remote.publishedAt})")
        }

        return runCatching {
            cacheFile.writeText(remoteText)
            Logger.i(TAG, "目录已更新: ${remote.publishedAt}, ${remote.providers.size} 个供应商")
            RefreshResult(ok = true, updated = true, message = "已更新到 ${remote.publishedAt}")
        }.getOrElse {
            Logger.w(TAG, "写入目录缓存失败: ${it.message}")
            RefreshResult(ok = false, message = "写入缓存失败")
        }
    }

    /** 当前目录的摘要信息(设置页展示用)。 */
    fun status(): CatalogStatus {
        val cachedCatalog = cached()
        val catalog = cachedCatalog ?: builtIn()
        val models = catalog.providers.values.sumOf { it.size }
        return CatalogStatus(
            publishedAt = catalog.publishedAt,
            providerCount = catalog.providers.size,
            modelCount = models,
            fromCache = cachedCatalog != null,
        )
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** 拉取结果。 */
data class RefreshResult(
    val ok: Boolean,
    val updated: Boolean = false,
    val message: String,
)

/** 目录状态摘要。 */
data class CatalogStatus(
    val publishedAt: String,
    val providerCount: Int,
    val modelCount: Int,
    /** true = 来自缓存(曾成功拉取);false = 内置目录。 */
    val fromCache: Boolean,
)
