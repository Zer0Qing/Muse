package io.zer0.muse.license

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 13: 开源许可数据加载器。
 *
 * 职责:
 *  1. 从 assets/licenses/manifest.json 加载依赖清单
 *  2. 按需从 assets 加载协议全文(如 "licenses/apache-2.0.txt")
 *  3. 解析失败时降级为空 manifest,不让 App 启动崩溃
 *
 * 性能:
 *  - manifest 缓存在内存(只解析一次)
 *  - licenseTexts 文本按需懒加载(Apache 2.0 全文 ~9KB,懒加载不挡 UI)
 *  - 用 Dispatchers.IO 不阻塞主线程
 */
class LicenseRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true   // 兼容未来字段扩展
        isLenient = true
    }

    @Volatile
    private var cached: LicenseManifest? = null

    /** 加载 manifest,失败返回空 manifest(空 dependencies 列表)。 */
    suspend fun loadManifest(): LicenseManifest {
        // L7: 已缓存时直接返回,避免多余的线程切换
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // 双重检查:进入 IO 后再确认一次,防止并发场景重复解析
            cached?.let { return@withContext it }
            val loaded = resultOf {
                val text = context.assets.open(MANIFEST_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString(LicenseManifest.serializer(), text)
            }.onError { msg, t ->
                Logger.e("LicenseRepository", "loadManifest failed: $msg", t)
            }.getOrNull() ?: LicenseManifest(
                schemaVersion = 0,
                generatedAt = "",
                appName = "",
                appVersion = "",
                licenseTexts = emptyMap(),
                dependencies = emptyList(),
            )
            cached = loaded
            loaded
        }
    }

    /** 加载协议全文(按需),找不到返回 null。 */
    suspend fun loadLicenseText(licenseId: String): String? = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        val relPath = manifest.licenseTexts[licenseId] ?: return@withContext null
        resultOf {
            context.assets.open(relPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onError { msg, t ->
            Logger.w("LicenseRepository", "loadLicenseText($licenseId) failed: $msg", t)
        }.getOrNull()
    }

    companion object {
        /** manifest.json 在 assets 内的相对路径。 */
        const val MANIFEST_PATH = "licenses/manifest.json"
    }
}
