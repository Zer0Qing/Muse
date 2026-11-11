package io.zer0.ai.core

import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 免费模型配置来源：内置默认值 → 本地缓存 → 服务端派发。
 *
 * 取用顺序（每次请求前调用 [current]）：
 *  1. **服务端派发**：向 [dispatchUrl] 拉取，成功即写入本地缓存并立即生效；
 *  2. **本地缓存**：上次成功拉取的配置（进程重启后仍可用）；
 *  3. **内置默认值**：[builtInDefault]，保证完全离线时免费档仍能发请求。
 *
 * 失败策略：网络失败/解析失败/字段不全都**不抛出**，静默回退到下一层。
 * 这是有意为之——免费档的价值在于「打开就能用」，不能因为派发服务抖动就让用户无法对话；
 * 但回退事件必须留日志（不含密钥），否则故障会变成「莫名其妙不能用」。
 *
 * 线程安全：`@Volatile` 持有当前配置；写缓存用临时文件 + rename，避免半截 JSON。
 */
class FreeModelConfigSource(
    private val dispatchUrl: String,
    private val cacheFile: File?,
    private val builtInDefault: FreeModelRemoteConfig,
    private val fetch: (String) -> String? = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var cached: FreeModelRemoteConfig? = null

    @Volatile
    private var lastFetchAtMs: Long = 0L

    /** 当前生效配置（不会返回不可用对象）。 */
    fun current(): FreeModelRemoteConfig = cached ?: loadCache() ?: builtInDefault

    /** 是否需要重新向服务端拉取（进程内节流）。 */
    fun shouldRefresh(minIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS): Boolean =
        dispatchUrl.isNotBlank() && clock() - lastFetchAtMs >= minIntervalMs

    /**
     * 向服务端拉取并应用最新配置。
     *
     * @return 拉取结果；[FetchOutcome.Disabled] 表示未配置派发端点。
     */
    fun refresh(force: Boolean = false): FetchOutcome {
        if (dispatchUrl.isBlank()) return FetchOutcome.Disabled
        if (!force && !shouldRefresh()) return FetchOutcome.Throttled

        lastFetchAtMs = clock()
        val raw = try {
            fetch(dispatchUrl)
        } catch (e: Exception) {
            // 网络异常按回退处理；日志只带异常类型，不带 URL 查询串或密钥。
            return FetchOutcome.Failed("请求失败: ${e.javaClass.simpleName}")
        } ?: return FetchOutcome.Failed("空响应")

        val parsed = parse(raw)
            ?: return FetchOutcome.Failed("响应无法解析为免费模型配置")
        if (!parsed.isUsable) {
            return FetchOutcome.Failed("下发配置字段不全(baseUrl/model/apiKey 有空值)")
        }

        val previous = cached
        if (previous != null && parsed.version < previous.version) {
            // 服务端回滚配置：接受但记录，便于排查「刚换上去又变回去」。
            cached = parsed
            writeCache(parsed)
            return FetchOutcome.Applied(parsed, rolledBack = true)
        }
        cached = parsed
        writeCache(parsed)
        return FetchOutcome.Applied(parsed, rolledBack = false)
    }

    /** 直接注入配置（测试用；也供调用方在拿到远端结果后统一入口）。 */
    fun apply(config: FreeModelRemoteConfig) {
        if (!config.isUsable) return
        cached = config
        writeCache(config)
    }

    /** 清空进程内缓存（下一次 [current] 会重新读盘/回退）。 */
    fun invalidate() {
        cached = null
    }

    sealed interface FetchOutcome {
        /** 未配置派发端点，不发起网络请求。 */
        data object Disabled : FetchOutcome

        /** 距上次拉取未超过节流窗口，本次跳过。 */
        data object Throttled : FetchOutcome

        /** 拉取并应用成功。 */
        data class Applied(val config: FreeModelRemoteConfig, val rolledBack: Boolean) : FetchOutcome

        /** 拉取失败，已回退到缓存/内置默认。 */
        data class Failed(val reason: String) : FetchOutcome
    }

    // ---------- 解析 ----------

    private fun parse(raw: String): FreeModelRemoteConfig? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 兼容两种形态：信封 { "data": {...} } 与直接对象 {...}。
        return runCatching {
            val envelope = json.decodeFromString(FreeModelRemoteEnvelope.serializer(), text)
            envelope.data
        }.getOrNull() ?: runCatching {
            json.decodeFromString(FreeModelRemoteConfig.serializer(), text)
        }.getOrNull()
    }

    // ---------- 缓存 ----------

    private fun loadCache(): FreeModelRemoteConfig? {
        val file = cacheFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            val config = json.decodeFromString(FreeModelRemoteConfig.serializer(), file.readText())
            config.takeIf { it.isUsable }
        }.getOrNull()?.also { cached = it }
    }

    private fun writeCache(config: FreeModelRemoteConfig) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(FreeModelRemoteConfig.serializer(), config))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        /** 进程内最小拉取间隔：避免每条消息都打一次派发端点。 */
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 10 * 60 * 1000L

        private const val TIMEOUT_MS = 8_000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun httpGet(url: String): String? {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
