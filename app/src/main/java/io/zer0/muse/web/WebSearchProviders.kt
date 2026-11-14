package io.zer0.muse.web

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.util.stripHtmlSimple
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * 百度网页搜索兜底。
 *
 * 国内 Android 网络环境下，Bing/Jina/SearXNG 可能同时被重定向、鉴权或路由阻断；
 * 百度页面在这类网络里通常仍可访问。这里仅解析公开结果页，不上传用户查询之外的数据。
 */
private fun Response.searchDiagnostic(): String {
    var redirects = 0
    var previous = priorResponse
    while (previous != null) {
        redirects++
        previous = previous.priorResponse
    }
    return "code=$code, finalHost=${request.url.host}, redirects=$redirects, " +
        "contentType=${header("Content-Type")?.substringBefore(';') ?: "unknown"}, " +
        "contentLength=${header("Content-Length") ?: "unknown"}"
}

class BaiduProvider(
    private val client: OkHttpClient,
) : WebSearchService {
    override val name: String = "Baidu"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf {
            val url = "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            client.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    Logger.w(name, "search failed: ${response.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(response.body.string(), maxResults)
            }
        }.onError { msg, t -> Logger.w(name, "search error: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun parseResults(body: String, maxResults: Int): List<WebSearchResult> {
        val document = Jsoup.parse(body)
        return document.select("div.result, div.c-container").asSequence()
            .mapNotNull { item ->
                val anchor = item.selectFirst("h3 a, a[data-click]") ?: return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return@mapNotNull null
                val snippet = item.selectFirst(".c-span-last, .c-color-text, .c-font-normal, .content-right_8Zs40")
                    ?.text()?.trim().orEmpty()
                WebSearchResult(
                    title = title,
                    url = href,
                    snippet = snippet.take(300),
                    source = name,
                )
            }
            .distinctBy { it.url }
            .take(maxResults)
            .toList()
    }
}

class SearXNGProvider(
    private val client: OkHttpClient,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("SearXNG")!!,
) : WebSearchService {
    override val name: String = "SearXNG"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        // H-WS1: 用 resultOf 替代 runCatching,避免吞 CancellationException
        resultOf {
            // 语言不再写死 zh-CN：中文查询用 zh-CN，其余留空让引擎按查询自己判断。
            // 写死中文会让英文资料与专有名词被严重限流（用户看到的就是「搜不到」）。
            val langParam = if (query.any { it in '㐀'..'鿿' }) "&language=zh-CN" else ""
            val timeParam = dateRange.toTimeRangeWord()?.let { "&time_range=$it" }.orEmpty()
            val url = "$endpoint/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&format=json&categories=general$langParam$timeParam"
            val req = Request.Builder().url(url)
                .header("User-Agent", "muse/1.0 (Android LLM client)")
                .header("Accept", "application/json")
                .get().build()
            // M-WS2: 用 executeAsync(enqueue + suspendCancellableCoroutine)替代阻塞 execute()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("SearXNG", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                val body = resp.body.string()
                parseResults(body, maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("SearXNG", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * Tavily provider — AI 搜索 API(https://tavily.com),需 API key。
 *
 * 协议: POST https://api.tavily.com/search
 * 请求体: { "api_key": "...", "query": "...", "max_results": N, "search_depth": "basic" }
 * 返回: { results: [{ title, url, content, score }] }
 *
 * API key 在 settings 配置。无 key 时直接返回空列表。
 */
class TavilyProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Tavily")!!,
) : WebSearchService {
    override val name: String = "Tavily"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        if (apiKey.isBlank()) {
            Logger.w("Tavily", "no API key configured, skip")
            return@withContext emptyList()
        }
        // H-WS1: 用 resultOf 替代 runCatching,避免吞 CancellationException
        resultOf {
            // Phase 8.5 修复: 用 buildJsonObject 构造请求体,避免手动拼接 JSON 字符串的转义 bug。
            // 原实现只对 query 的双引号做了 replace,但:
            //  1. apiKey 未转义(若含 " 或 \ 会破坏 JSON)
            //  2. query 的反斜杠未转义(原 `\` 会被吃掉)
            //  3. 换行/制表符等控制字符未转义(JSON 字符串不允许字面控制字符)
            // 改用 kotlinx.serialization 的 buildJsonObject,所有字符串转义由序列化库处理。
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("api_key", kotlinx.serialization.json.JsonPrimitive(apiKey))
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("max_results", kotlinx.serialization.json.JsonPrimitive(maxResults))
                put("search_depth", kotlinx.serialization.json.JsonPrimitive("basic"))
                dateRange.toTimeRangeWord()?.let { put("time_range", kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            // M-WS2: 用 executeAsync(enqueue + suspendCancellableCoroutine)替代阻塞 execute()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Tavily", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                val body = resp.body.string()
                parseResults(body, maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Tavily", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.135: Auto 搜索 Provider — 多引擎 fallback + 低质量结果检测。
 *
 * 设计:
 *  - 按优先级依次尝试多个 provider,遇到空结果/低质量/异常则自动换下一个
 *  - 检测日期计算器、词典/百科释义等低质量结果并跳过
 *  - 最终结果去重、按查询相关性排序
 *
 * Provider 优先级:
 *  1. 用户配置了 API key 的商用 provider(Tavily/Brave/Serper/Zhipu/Bocha/Metaso/Exa/Firecrawl/Perplexity)
 *  2. 免费/免 key provider: Bing → Jina → SearXNG
 */
/**
 * DeepSeek 搜索 API 的预设描述。
 *
 * POST /v1/tools/search，返回 { results: [{ title, url, summary, ... }] }。
 * 形态与 [GenericSearchProvider] 完全吻合，所以不单独写类，直接复用通用适配器。
 *
 * need_content=false：官方 summary 本身已有 600-800 字，再取整页 content 会撑爆上下文。
 */
internal val DEEPSEEK_SEARCH_ENGINE =
    CustomSearchEngine(
        id = "preset_deepseek_search",
        name = "DeepSeek",
        endpoint = WebSearchProviderCatalog.defaultEndpoint("DeepSeek")!!,
        method = "POST",
        bodyTemplate = "{\"query\":\"{query}\",\"count\":{count},\"need_content\":false}",
        resultsPath = "results",
        titlePath = "title",
        urlPath = "url",
        // summary 比 snippet 更完整，取不到时再回落
        snippetPath = "summary",
        enabled = true,
    )

class AutoWebSearchService(
    private val client: OkHttpClient,
    config: WebSearchConfig,
) : WebSearchService {
    override val name: String = "Auto"

    @Volatile
    private var config: WebSearchConfig = config

    /** 运行时更新配置,同步替换内部状态。 */
    fun updateConfig(newConfig: WebSearchConfig) {
        synchronized(this) {
            config = newConfig
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, emptyMap())

    /** 把 date_range 等选项转发给链内每个 provider，不再静默丢弃。 */
    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options)

    private suspend fun searchInternal(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> {
        // 全局限速预检查
        if (!SearchRateLimiter.await(name)) {
            throw SearchRateLimitException(
                cause = "$name rate-limited (await=false)",
                retryAfterMs = SearchRateLimiter.getRetryAfterMs(name),
            )
        }

        val currentConfig = config
        val chain = buildProviderChain(currentConfig)
        if (chain.isEmpty()) {
            Logger.w("AutoWebSearch", "no provider available")
            return emptyList()
        }

        val attempts = mutableListOf<String>()

        for (provider in chain) {
            val providerName = provider.name
            try {
                // 单 provider 限速预检查
                if (!SearchRateLimiter.await(providerName)) {
                    attempts.add("$providerName: rate-limited")
                    continue
                }
                val results = if (options.isEmpty()) {
                    provider.search(query, maxResults)
                } else {
                    provider.searchWithOptions(query, maxResults, options)
                }
                if (results.isEmpty()) {
                    attempts.add("$providerName: empty")
                    continue
                }
                if (isLikelyLowQualityResults(query, results)) {
                    attempts.add("$providerName: low-quality")
                    continue
                }
                attempts.add("$providerName: ok(${results.size})")
                Logger.i("AutoWebSearch", "query='$query' attempts=$attempts")
                return normalizeResults(query, results, maxResults)
            } catch (e: SearchRateLimitException) {
                // 该家配了 Key 池时，429 把当前 Key 降优先级；下一次建链（含本轮后续查询）
                // 会由 resolveProviderKey → KeyRoulette.pick 拿到另一把。
                currentConfig.providerEntries
                    .firstOrNull { it.enabled && it.type.equals(providerName, ignoreCase = true) }
                    ?.let { entry ->
                        val pool = entry.apiKeys.filter { it.isNotBlank() }
                        if (pool.size > 1) {
                            val current = WebSearchKeyRoulette.pick(entry.id, pool)
                            WebSearchKeyRoulette.pickNext(entry.id, pool, current)
                        }
                    }
                attempts.add("$providerName: rate-limit")
            } catch (e: Exception) {
                attempts.add("$providerName: error(${e.message})")
                Logger.w("AutoWebSearch", "$providerName failed", e)
            }
        }

        // 全部 fallback 均失败或低质量：返回空，由上层给出明确原因。
        // 不再把噪声结果兜底返回——不相关材料会诱导 LLM 基于错误事实作答。
        Logger.w("AutoWebSearch", "query='$query' all failed or low-quality, returning empty. attempts=$attempts")
        return emptyList()
    }

    private fun buildProviderChain(cfg: WebSearchConfig): List<WebSearchService> = buildSearchChain(client, cfg)

    companion object {
        /** 已知低质量域名(日期计算器、单位换算等工具站)。 */
        private val LOW_QUALITY_DOMAINS = setOf(
            "timeanddate.com",
            "datecalculator.org",
            "calculator.net",
            "calculatorsoup.com",
            "unitconverters.net",
            "rapidtables.com",
            "datetime360.com",
            "timedatecalc.com",
        )

        /** 中文词典/释义类站点关键词。 */
        private val DICTIONARY_PATTERNS = listOf("zdic.net", "汉典", "字典", "词典", "基本解释", "汉语意思")

        /** 日期计算器类关键词。 */
        private val DATE_CALC_PATTERNS = listOf(
            "date calculator",
            "days calculator",
            "age calculator",
            "day calculator",
            "datecalculator",
            "dayscalculator",
            "hours calculator",
            "time calculator",
        )

        /** 低质量结果的降权分：足够让它沉到相关结果之后，但不至于把唯一的结果也抹掉。 */
        internal const val LOW_QUALITY_PENALTY = 100

        private fun hasCjk(text: String): Boolean = text.any { it in '\u3400'..'\u9fff' }

        private fun isLowQualityResult(query: String, result: WebSearchResult): Boolean {
            val lowerUrl = result.url.lowercase()
            val lowerTitle = result.title.lowercase()
            val lowerSnippet = result.snippet.lowercase()
            val combined = "$lowerTitle $lowerSnippet $lowerUrl"

            // 日期/时间/年龄等计算器站点
            if (DATE_CALC_PATTERNS.any { combined.contains(it) }) return true
            if (LOW_QUALITY_DOMAINS.any { lowerUrl.contains(it) }) return true

            // 中文查询时,词典/汉典/释义类结果通常不相关(除非用户明确查词)
            if (hasCjk(query) && DICTIONARY_PATTERNS.any { combined.contains(it) }) return true

            return false
        }

        /**
         * 判断一组结果是否整体低质量。
         * 启发式:top 3 条中至少有 2 条命中低质量规则,则认为该 provider 当前查询结果不可用。
         */
        private fun isLikelyLowQualityResults(query: String, results: List<WebSearchResult>): Boolean {
            if (results.isEmpty()) return true
            val topN = results.take(3)
            val lowQualityCount = topN.count { isLowQualityResult(query, it) }
            return lowQualityCount >= 2
        }

        /** 计算结果与查询的相关性得分,用于排序。 */
        /** 排序用的最终分：相关性分减去低质量惩罚。低质量只降权、不剔除。 */
        internal fun rankingScore(query: String, result: WebSearchResult): Int =
            scoreResult(query, result) - if (isLowQualityResult(query, result)) LOW_QUALITY_PENALTY else 0

        private fun scoreResult(query: String, result: WebSearchResult): Int {
            val terms = query.split(Regex("\\s+")).filter { it.length > 1 }
            val lowerTitle = result.title.lowercase()
            val lowerSnippet = result.snippet.lowercase()
            var score = 0
            terms.forEach { term ->
                val lowerTerm = term.lowercase()
                if (lowerTitle.contains(lowerTerm)) score += 3
                if (lowerSnippet.contains(lowerTerm)) score += 1
            }
            if (result.snippet.isBlank()) score -= 3
            if (result.title.isBlank()) score -= 5
            return score
        }

        /**
         * 规范化结果:过滤空字段、去重、按相关性排序（低质量**降权而不是丢弃**）、截断。
         *
         * 为什么不再整条丢弃：低质量判定靠的是「标题/摘要里出现了『词典』『字典』这类词」这种子串规则。
         * 中文查询时正文里出现这些词是常态，一刀切会把真正相关的结果也删光 ——
         * 用户看到的就是「搜了 4.7 秒、零结果」。降权之后坏结果自然沉底，
         * 好结果只要存在就还能被选中；全都很差时也不至于空手而归。
         */
        private fun normalizeResults(query: String, results: List<WebSearchResult>, maxResults: Int): List<WebSearchResult> = results
            .asSequence()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.url.lowercase().trimEnd('/') }
            .sortedByDescending { result -> rankingScore(query, result) }
            .take(maxResults)
            .toList()
    }
}

/**
 * 复合搜索服务 — 根据 provider 名分发到具体实现。
 *
 * 由 [WebSearchConfig] 驱动:settings 里配置 providerName / apiKey / endpoint,
 * 切换 provider 时重新构造底层实例(避免持有 stale client)。
 */
class CompositeWebSearchService(
    private val client: OkHttpClient,
    config: WebSearchConfig,
) : WebSearchService {
    override val name: String get() = config.providerName

    /**
     * 当前生效的 config 与具体 provider。
     * Phase 8.5 修复:用 `@Volatile var config` + 同步 `updateConfig`,
     * 替代原 `by lazy` 只算一次 + updateConfig 空实现,使运行时切换 provider 生效。
     */
    @Volatile
    private var config: WebSearchConfig = config

    @Volatile
    private var delegate: WebSearchService = buildDelegate(config)

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, emptyMap())

    /** 把 date_range 等选项转发给实际 provider，避免编排层把它静默丢弃。 */
    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options)

    private suspend fun searchInternal(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> {
        // 限速预检查：退避期内直接抛 SearchRateLimitException，不发起请求，避免反复撞 429/402 被封禁
        if (!SearchRateLimiter.await(name)) {
            throw SearchRateLimitException(
                cause = "$name rate-limited (await=false)",
                retryAfterMs = SearchRateLimiter.getRetryAfterMs(name),
            )
        }
        return if (options.isEmpty()) {
            delegate.search(query, maxResults)
        } else {
            delegate.searchWithOptions(query, maxResults, options)
        }
    }

    /** 切换配置(运行时改 provider/apiKey/endpoint)。同步重建 delegate,避免 stale client。 */
    fun updateConfig(newConfig: WebSearchConfig) {
        synchronized(this) {
            config = newConfig
            delegate = buildDelegate(newConfig)
        }
    }

    private fun buildDelegate(cfg: WebSearchConfig): WebSearchService = Companion.buildDelegate(client, cfg)

    companion object {
        /**
         * 根据配置构造对应的搜索 Provider(供测试按钮直接调用,绕过限速与 stale config)。
         * 使用 UI 中最新的 WebSearchConfig,直接实例化对应 Provider,不经过 Composite 层。
         */
        fun buildDelegate(client: OkHttpClient, cfg: WebSearchConfig): WebSearchService {
            val providerName = WebSearchProviderCatalog.canonicalKey(cfg.providerName)
            return when (providerName) {
                // v1.135: Auto 多引擎 fallback
                "Auto" -> AutoWebSearchService(client, cfg)
                "Bing" -> BingProvider(client)
                "Jina" -> JinaProvider(
                    client,
                    resolveProviderKey(cfg, "Jina"),
                    resolveProviderEndpoint(cfg, "Jina"),
                )
                // M-WS3: 自定义 API — 按 apiKey 是否非空二次分发:
                //  有 apiKey 走 Tavily 兼容接口,无 apiKey 走 SearXNG 兼容接口(原先无条件映射到 SearXNG,忽略 apiKey)
                "Custom API" ->
                    if (resolveProviderKey(cfg, providerName).isNotBlank()) {
                        TavilyProvider(
                            client,
                            resolveProviderKey(cfg, providerName),
                            resolveProviderEndpoint(cfg, providerName, "Tavily"),
                        )
                    } else {
                        SearXNGProvider(client, resolveProviderEndpoint(cfg, providerName, "SearXNG"))
                    }
                "SearXNG" -> SearXNGProvider(client, resolveProviderEndpoint(cfg, "SearXNG"))
                "Tavily" -> TavilyProvider(
                    client,
                    resolveProviderKey(cfg, "Tavily"),
                    resolveProviderEndpoint(cfg, "Tavily"),
                )
                // v1.97: 新增搜索 provider
                "Zhipu" -> ZhipuSearchProvider(
                    client,
                    resolveProviderKey(cfg, "Zhipu"),
                    resolveProviderEndpoint(cfg, "Zhipu"),
                )
                // v2.6.10: DeepSeek 搜索 —— 复用通用适配器
                "DeepSeek" ->
                    GenericSearchProvider(
                        client,
                        DEEPSEEK_SEARCH_ENGINE.copy(apiKey = resolveProviderKey(cfg, "DeepSeek")),
                    )
                "Brave" -> BraveSearchProvider(
                    client,
                    resolveProviderKey(cfg, "Brave"),
                    resolveProviderEndpoint(cfg, "Brave"),
                )
                "Serper" -> SerperSearchProvider(
                    client,
                    resolveProviderKey(cfg, "Serper"),
                    resolveProviderEndpoint(cfg, "Serper"),
                )
                "Bocha" -> BochaSearchProvider(
                    client,
                    resolveProviderKey(cfg, "Bocha"),
                    resolveProviderEndpoint(cfg, "Bocha"),
                )
                "Metaso" -> MetasoSearchProvider(
                    client,
                    resolveProviderKey(cfg, "Metaso"),
                    resolveProviderEndpoint(cfg, "Metaso"),
                )
                "Exa" -> ExaSearchProvider(client, resolveProviderKey(cfg, "Exa"), resolveProviderEndpoint(cfg, "Exa"))
                "Firecrawl" -> FirecrawlProvider(
                    client,
                    resolveProviderKey(cfg, "Firecrawl"),
                    resolveProviderEndpoint(cfg, "Firecrawl"),
                )
                // Perplexity AI 搜索 API(sonar-pro 模型 + citations)
                "Perplexity" -> PerplexitySearchProvider(
                    client,
                    resolveProviderKey(cfg, "Perplexity"),
                    resolveProviderEndpoint(cfg, "Perplexity"),
                )
                // 默认用 Bing HTTP(免费,无需 API key)
                else -> BingProvider(client)
            }
        }
    }
}

/**
 * v0.33: Bing HTML 搜索 Provider (v1.131 优化版)。
 *
 * 实现:GET https://www.bing.com/search?q={query} (真实 Bing HTML 抓取)
 * - 无需 API key,通过 HTML 抓取 + Jsoup 解析
 * - 关键技巧:
 *   1) Cookie `SRCHHPGUSR=ULSR=1&SRCHLANG=zh-CN` + `_SS=...` 让 Bing 返回稳定可解析的 HTML
 *   2) URL 加 `mkt=zh-CN&setlang=zh-CN&ensearch=1` 避免地区重定向到 cn.bing.com
 *   3) 使用桌面 Chrome UA + 完整请求头,模拟桌面浏览器以拿到带 b_algo 结果块的 HTML
 *   4) 多级 snippet 选择器兜底(.b_caption p → .b_lineclamp4 → .b_focusTextLarge),
 *      应对 Bing HTML 多种结果块结构
 *   5) 跳过非自然结果块(b_algo b_algo_default 之外的 b_ans / b_card 旁路)
 * - 用 Jsoup 解析(OkHttp 请求以保持与现有 client 一致的代理/超时配置)
 *
 * 解析规则:
 *  - 主选择器: `li.b_algo:not(.b_algo_default):not(.b_card)` 取自然结果块
 *  - 标题链接: `h2 > a`(主) / `h2 a`(兜底)
 *  - snippet: `.b_caption p` / `.b_caption .b_paractl` / `.b_lineclamp4` / `.b_focusTextLarge`
 */
class BingProvider(
    private val client: OkHttpClient,
) : WebSearchService {
    override val name: String = "Bing"

    /** 使用中国区入口的 HTTP HTML 搜索；不启动 Android WebView。 */
    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://cn.bing.com/search?q=$encoded&mkt=zh-CN&setlang=zh-CN"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            client.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    Logger.w(name, "HTTP search failed: ${response.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseHttpResults(response.body.string(), maxResults)
            }
        }.onError { msg, t -> Logger.w(name, "HTTP search error: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun parseHttpResults(body: String, maxResults: Int): List<WebSearchResult> {
        val document = Jsoup.parse(body)
        return document.select("li.b_algo").asSequence()
            .mapNotNull { item ->
                val anchor = item.selectFirst("h2 a") ?: return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = anchor.absUrl("href").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val snippet = item.selectFirst(".b_caption p, .b_lineclamp4, .b_focusTextLarge")
                    ?.text()?.trim().orEmpty()
                WebSearchResult(title, url, snippet.take(300), name)
            }
            .distinctBy { it.url }
            .take(maxResults)
            .toList()
    }
}

/**
 * Jina AI Reader Search Provider — 基于 Jina AI Reader Search API(https://s.jina.ai/)。
 *
 * 协议: POST https://s.jina.ai/
 *  - 请求头: Authorization: Bearer {apiKey}(可选,无 key 走免费层但有速率限制)
 *  - 请求体 JSON: {"q": query, "num": maxResults}
 *  - 响应 JSON: {"data": [{"title", "url", "description", "content"}]}
 *
 * snippet 取 description,为空则用 content 前 200 字兜底。
 * 无需 API key 也能用(免费层有速率限制),apiKey 为空时不带 Authorization 头。
 */
class JinaProvider(
    private val client: OkHttpClient,
    private val apiKey: String = "",
    private val configuredEndpoint: String? = null,
) : WebSearchService {
    override val name: String = "Jina"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        val endpoints = listOfNotNull(
            configuredEndpoint?.trim()?.takeIf { it.isNotBlank() },
            "https://s.jinaai.cn/",
            "https://s.jina.ai/",
        ).distinct()
        for (endpoint in endpoints) {
            val result = resultOf {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("q", kotlinx.serialization.json.JsonPrimitive(query))
                    put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
                }.toString()
                val reqBuilder = Request.Builder().url(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
                val req = reqBuilder.post(payload.toRequestBody("application/json".toMediaType())).build()
                client.executeAsync(req).use { resp ->
                    SearchRateLimiter.assertNotRateLimited(name, resp)
                    if (!resp.isSuccessful) {
                        Logger.w("Jina", "search failed: ${resp.searchDiagnostic()} endpoint=$endpoint")
                        emptyList()
                    } else {
                        parseResults(resp.body.string(), maxResults)
                    }
                }
            }.onError { _, t ->
                if (t is SearchRateLimitException) throw t
                Logger.w("Jina", "search error endpoint=$endpoint", t)
            }.getOrNull().orEmpty()
            if (result.isNotEmpty()) return@withContext result
        }
        emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["data"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // snippet 优先取 description,为空则用 content 前 200 字兜底
            val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = if (description.isNotBlank()) description else content.take(200)
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: 智谱搜索 Provider — 智谱 AI web_search API。
 *
 * 协议: POST https://open.bigmodel.cn/api/paas/v4/web_search
 *  - 请求头: Authorization: Bearer {apiKey}
 *  - 请求体: {"search_query": query, "num": maxResults}
 *  - 响应: {"search_result": [{"title", "link", "content"}]}
 */
class ZhipuSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Zhipu")!!,
) : WebSearchService {
    override val name: String = "Zhipu"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Zhipu", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("search_query", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
            }.toString()
            val req = Request.Builder().url("$endpoint/web_search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Zhipu", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Zhipu", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["search_result"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Brave Search Provider — Brave 搜索 API。
 *
 * 协议: GET https://api.search.brave.com/res/v1/web/search?q={query}&count={max}
 *  - 请求头: X-Subscription-Token: {apiKey}
 *  - 响应: {"web": {"results": [{"title", "url", "description"}]}}
 */
class BraveSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Brave")!!,
) : WebSearchService {
    override val name: String = "Brave"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        if (apiKey.isBlank()) {
            Logger.w("Brave", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val freshness = when (dateRange) {
                "past_day" -> "pd"
                "past_week" -> "pw"
                "past_month" -> "pm"
                "past_year" -> "py"
                else -> null
            }?.let { "&freshness=$it" }.orEmpty()
            val url = "$endpoint/web/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&count=$maxResults$freshness"
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .get().build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Brave", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Brave", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val webObj = root["web"] as? JsonObject ?: return emptyList()
        val arr = webObj["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Serper Search Provider — Google 搜索 API(serper.dev)。
 *
 * 协议: POST https://google.serper.dev/search
 *  - 请求头: X-API-KEY: {apiKey}
 *  - 请求体: {"q": query, "num": maxResults}
 *  - 响应: {"organic": [{"title", "link", "snippet"}]}
 */
class SerperSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Serper")!!,
) : WebSearchService {
    override val name: String = "Serper"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        if (apiKey.isBlank()) {
            Logger.w("Serper", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val tbs = when (dateRange) {
                "past_day" -> "qdr:d"
                "past_week" -> "qdr:w"
                "past_month" -> "qdr:m"
                "past_year" -> "qdr:y"
                else -> null
            }
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("q", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
                tbs?.let { put("tbs", kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Serper", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Serper", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["organic"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: 博查搜索 Provider — 博查 AI 搜索 API。
 *
 * 协议: POST https://api.bochaai.com/v1/web-search
 *  - 请求头: Authorization: Bearer {apiKey}
 *  - 请求体: {"query": query, "count": maxResults, "summary": true}
 *  - 响应: {"data": {"webPages": {"value": [{"name", "url", "summary"}]}}}
 */
class BochaSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Bocha")!!,
) : WebSearchService {
    override val name: String = "Bocha"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Bocha", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("count", kotlinx.serialization.json.JsonPrimitive(maxResults))
                put("summary", kotlinx.serialization.json.JsonPrimitive(true))
            }.toString()
            val req = Request.Builder().url("$endpoint/web-search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Bocha", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Bocha", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList()
        val webPagesObj = dataObj["webPages"] as? JsonObject ?: return emptyList()
        val arr = webPagesObj["value"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["summary"]?.jsonPrimitive?.contentOrNull
                ?: obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: 秘塔搜索 Provider — 秘塔 AI 搜索 API。
 *
 * 协议: POST https://metaso.cn/api/v1/search
 *  - 请求头: Authorization: Bearer {apiKey}
 *  - 请求体: {"q": query, "num": maxResults}
 *  - 响应: {"data": {"results": [{"title", "url", "content"}]}}
 */
class MetasoSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Metaso")!!,
) : WebSearchService {
    override val name: String = "Metaso"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Metaso", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("q", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Metaso", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Metaso", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList()
        val arr = dataObj["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Exa Search Provider — Exa AI 搜索 API。
 *
 * 协议: POST https://api.exa.ai/search
 *  - 请求头: Authorization: Bearer {apiKey}
 *  - 请求体: {"query": query, "numResults": maxResults, "contents": {"text": {"maxCharacters": 300}}}
 *  - 响应: {"results": [{"title", "url", "text"}]}
 */
class ExaSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Exa")!!,
) : WebSearchService {
    override val name: String = "Exa"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        if (apiKey.isBlank()) {
            Logger.w("Exa", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            // Exa 用 ISO 起始日期表达时敏，需把相对范围换算成绝对日期。
            val startPublishedDate = dateRange?.let { range ->
                val days = when (range) {
                    "past_day" -> 1L
                    "past_week" -> 7L
                    "past_month" -> 31L
                    "past_year" -> 366L
                    else -> null
                }
                days?.let { d ->
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                    sdf.format(java.util.Date(System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(d)))
                }
            }
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("numResults", kotlinx.serialization.json.JsonPrimitive(maxResults))
                startPublishedDate?.let { put("startPublishedDate", kotlinx.serialization.json.JsonPrimitive(it)) }
                put(
                    "contents",
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "text",
                            kotlinx.serialization.json.buildJsonObject {
                                put("maxCharacters", kotlinx.serialization.json.JsonPrimitive(300))
                            },
                        )
                    },
                )
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Exa", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Exa", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * Firecrawl 搜索 provider。
 *
 * 协议: POST {endpoint}/search,请求体为 JSON。
 * 需要 API key。支持网页抓取与搜索。
 */
class FirecrawlProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Firecrawl")!!,
) : WebSearchService {
    override val name: String = "Firecrawl"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        // H-WS1: 用 resultOf 替代 try/catch,避免吞 CancellationException,与其他 provider 一致
        resultOf {
            val payload = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("limit", JsonPrimitive(maxResults))
                put("lang", JsonPrimitive("en"))
            }.toString()

            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body.string()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body) as? JsonObject ?: return@use emptyList()
                val data = root["data"]?.let { it as? JsonArray } ?: return@use emptyList()
                data.take(maxResults).mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["metadata"]?.let { (it as? JsonObject)?.get("title") }
                            ?.jsonPrimitive?.contentOrNull ?: url
                    val snippet = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["markdown"]?.jsonPrimitive?.contentOrNull?.take(300) ?: ""
                    WebSearchResult(
                        title = stripHtmlSimple(title),
                        url = url,
                        snippet = if (snippet.length > 300) snippet.take(300) + "\u2026" else snippet,
                        source = name,
                    )
                }
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Firecrawl", "search error", t)
        }.getOrNull() ?: emptyList()
    }
}

/**
 * Perplexity Search Provider — Perplexity AI 搜索 API。
 *
 * 协议: POST https://api.perplexity.ai/chat/completions
 *  - 请求头: Authorization: Bearer {apiKey},Content-Type: application/json
 *  - 请求体: { "model": "sonar-pro",
 *              "messages": [{ "role": "user", "content": query }],
 *              "return_citations": true,
 *              "return_images": false }
 *  - 响应: { "choices": [{ "message": { "content": "..." } }],
 *           "citations": ["url1", "url2", ...] }
 *
 * Perplexity 返回 AI 生成答案 + 引用 URL 列表,本 provider 把每个引用 URL 包装为一条
 * [WebSearchResult],snippet 取 message.content(截断 300 字),source = "Perplexity"。
 * 无引用但 AI 给出答案时降级为单条结果(标题 "Perplexity AI"),保留答案作为 snippet。
 * 需要 API Key(在 settings 配置),无 key 时直接返回空列表。
 */
class PerplexitySearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = WebSearchProviderCatalog.defaultEndpoint("Perplexity")!!,
) : WebSearchService {
    override val name: String = "Perplexity"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = searchInternal(query, maxResults, null)

    override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
        searchInternal(query, maxResults, options.dateRangeOrNull())

    private suspend fun searchInternal(query: String, maxResults: Int, dateRange: String?): List<WebSearchResult> = withContext(
        AppDispatchers.io,
    ) {
        if (apiKey.isBlank()) {
            Logger.w("Perplexity", "no API key configured, skip")
            return@withContext emptyList()
        }
        // H-WS1: 用 resultOf 替代 runCatching,避免吞 CancellationException
        resultOf {
            // 用 buildJsonObject 构造请求体,字符串转义由序列化库处理
            val recency = when (dateRange) {
                "past_day" -> "day"
                "past_week" -> "week"
                "past_month" -> "month"
                "past_year" -> "year"
                else -> null
            }
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive("sonar-pro"))
                put(
                    "messages",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(query))
                            },
                        ),
                    ),
                )
                put("return_citations", kotlinx.serialization.json.JsonPrimitive(true))
                put("return_images", kotlinx.serialization.json.JsonPrimitive(false))
                recency?.let { put("search_recency_filter", kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString()
            val req = Request.Builder().url("$endpoint/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            // M-WS2: 用 executeAsync(enqueue + suspendCancellableCoroutine)替代阻塞 execute()
            client.executeAsync(req).use { resp ->
                // 429/402 限速检测：标记 provider 并抛 SearchRateLimitException（由 onError 重抛到 UI）
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Perplexity", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // 限速异常向上抛，供 UI 给用户友好提示；其余异常照旧吞掉返回空列表
            if (t is SearchRateLimitException) throw t
            Logger.w("Perplexity", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        // 取 AI 生成的答案内容(choices[0].message.content)
        val choices = root["choices"] as? JsonArray ?: return emptyList()
        val firstChoice = choices.firstOrNull() as? JsonObject ?: return emptyList()
        val messageObj = firstChoice["message"] as? JsonObject ?: return emptyList()
        val content = messageObj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val snippet = if (content.length > 300) content.take(300) + "…" else content
        // 引用 URL 列表(citations[].url 在响应里直接是字符串数组)
        val citations = (root["citations"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return when {
            // 每条引用 URL 包装为一条结果,snippet 共用 AI 答案(截断 300 字)
            citations.isNotEmpty() -> citations.take(max).map { url ->
                WebSearchResult(
                    title = extractTitle(url),
                    url = url,
                    snippet = snippet,
                    source = name,
                )
            }
            // 无引用时直接返回空列表,避免构造 url="" 的无效结果污染下游
            else -> {
                Logger.d("WebSearchService", "Perplexity 无引用结果")
                emptyList()
            }
        }
    }

    /** 从 URL 提取 host 作为标题(无 host 时回退为 "Perplexity Citation")。 */
    private fun extractTitle(url: String): String {
        return runCatching {
            val host = java.net.URI(url).host
            if (host.isNullOrBlank()) "Perplexity Citation" else host
        }.getOrElse { "Perplexity Citation" }
    }
}

/**
 * Auto 链的**内置顺序**（引擎名，按尝试先后）。
 *
 * 这张表是**唯一的一份**：链的构造按它逐个建 provider，设置页的「搜索顺序」列表也按它渲染。
 * 以前顺序只以"代码书写顺序"的形式存在，界面想给用户调序就得自己抄一份 —— 抄的那一份
 * 一旦和这里不同步，用户看到的就是假顺序（这条比"没有排序功能"更坏）。
 *
 * 自定义引擎不在表里：它们由用户自己创建，排在链首（见 `customSearchOrderKey`），
 * 顺序表在 UI 侧动态拼在最前面。
 */
/** 单个引擎的验证结果（只报事实，不改配置、不自造结论文案）。 */
internal data class WebSearchProbeResult(
    val providerKey: String,
    val ok: Boolean,
    val results: Int,
    val elapsedMs: Long,
    /** 失败原因类别；文案由界面按用户语言取，主源码里不留中文字面量。 */
    val reason: WebSearchProbeReason = WebSearchProbeReason.OK,
    /** 只承载底层自带的信息（服务端/网络原文），不是我自己编的句子。 */
    val detail: String? = null,
)

/** 探测失败的原因分类：界面要能对每一类给出不同的可读说明。 */
internal enum class WebSearchProbeReason {
    OK,

    /** 没配 Key 或键不存在：这种情况**不该发出请求**。 */
    NOT_USABLE,

    /** 请求通了但零结果：多半是 Key 权限或配额问题。 */
    EMPTY,

    /** 请求本身失败（网络 / HTTP / 解析）。 */
    REQUEST_FAILED,
}

/** 单独验证时用的探测词：只要"能不能出结果"，不需要真实语料。 */
internal const val PROBE_QUERY = "hello"

/** 单独验证只取一条，避免为一次健不健康的检查把对方打满。 */
internal const val PROBE_MAX_RESULTS = 1

/**
 * 把"界面上刚输入、还没保存的 Key"叠进配置用于验证。
 *
 * 两个刻意的点：
 *  - 草稿为空时**原样返回**：那一行显示的是已保存的 Key，用户没改就不该被空串顶掉；
 *  - 只覆盖这一家，其它引擎的 Key 保持不动（一次验证不该顺手改别人的配置）。
 */
internal fun verifyConfigFor(cfg: WebSearchConfig, providerKey: String, apiKeyDraft: String): WebSearchConfig {
    val key = apiKeyDraft.trim()
    if (key.isEmpty()) return cfg
    return cfg.copy(apiKeys = cfg.apiKeys + (providerKey to key))
}

/**
 * 只验一家引擎（用户点「验证」时用）。
 *
 * 复用 `AUTO_CHAIN_BUILDERS` 而不是另写一遍"每家怎么建"：
 * 那份重复正是这一片代码一直出问题的根因（顺序、能力、上限都因为抄第二份而漂移过）。
 * 自定义引擎走 `custom:<名字>` 键，与设置页列表同一套标识。
 */
internal suspend fun probeSearchProvider(
    client: OkHttpClient,
    cfg: WebSearchConfig,
    providerKey: String,
    apiKeyDraft: String,
): WebSearchProbeResult {
    val effective = verifyConfigFor(cfg, providerKey, apiKeyDraft)
    val service: WebSearchService? = if (providerKey.startsWith("custom:")) {
        val name = providerKey.removePrefix("custom:")
        effective.customEngines.firstOrNull { it.name == name }
            ?.let { GenericSearchProvider(client, it) }
    } else {
        AUTO_CHAIN_BUILDERS[providerKey]?.invoke(client, effective, { key -> resolveProviderKey(effective, key) })
    }
    if (service == null) {
        // 没配 Key / 键不存在：直接判不可用，且**刻意不发请求** ——
        // 否则打开设置页就可能对外打出十几次无效请求（配额与隐私都不该这么花）。
        return WebSearchProbeResult(
            providerKey = providerKey,
            ok = false,
            results = 0,
            elapsedMs = 0L,
            reason = WebSearchProbeReason.NOT_USABLE,
        )
    }
    val started = System.currentTimeMillis()
    val attempt = runCatching { service.search(PROBE_QUERY, PROBE_MAX_RESULTS) }
    val elapsed = System.currentTimeMillis() - started
    return attempt.fold(
        onSuccess = { found ->
            WebSearchProbeResult(
                providerKey = providerKey,
                ok = found.isNotEmpty(),
                results = found.size,
                elapsedMs = elapsed,
                reason = if (found.isEmpty()) WebSearchProbeReason.EMPTY else WebSearchProbeReason.OK,
            )
        },
        onFailure = { cause ->
            // runCatching 连取消也吞，必须显式抛回：验证按钮随页面回收时，
            // 不能把协程取消伪装成一次"网络失败"留给用户看。
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            WebSearchProbeResult(
                providerKey = providerKey,
                ok = false,
                results = 0,
                elapsedMs = elapsed,
                reason = WebSearchProbeReason.REQUEST_FAILED,
                detail = cause.message ?: cause.javaClass.simpleName,
            )
        },
    )
}

/** 单一真源：[WebSearchProviderCatalog.chainKeys]；顺序与存量 `providerOrder` 里的引擎名保持一致。 */
internal val AUTO_CHAIN_PROVIDER_KEYS: List<String> = WebSearchProviderCatalog.chainKeys

/**
 * 取某家 provider 当前应使用的 Key，供建链与「测试连接」共用，避免两处口径漂移。
 *
 * 优先级：v3 `providerEntries` 的 Key 池首把 → 旧 `apiKeys` 映射 → 旧全局 `apiKey`
 * （仅当 providerName 指向该家）。旧配置（未迁移或只在旧 UI 改了旧字段）因此零回归。
 * 429/401 时的换 key 由 [WebSearchKeyRoulette] 在请求层处理，这里只负责「首把」。
 */
internal fun resolveProviderKey(cfg: WebSearchConfig, provider: String): String {
    val canonicalProvider = WebSearchProviderCatalog.canonicalKey(provider)
    val entry = cfg.providerEntries
        .firstOrNull {
            it.enabled && WebSearchProviderCatalog.canonicalKey(it.type)
                .equals(canonicalProvider, ignoreCase = true)
        }
    val poolKeys = entry?.apiKeys.orEmpty().filter { it.isNotBlank() }
    if (poolKeys.isNotEmpty()) {
        // 走轮换器：429 后 pickNext 会把当前 Key 加入软黑名单，下一次建链时这里自动拿到另一把。
        // 单把 Key 时 pick 原样返回，行为与旧版一致。
        return WebSearchKeyRoulette.pick(entry?.id ?: provider, poolKeys)
    }
    val mappedKey = cfg.apiKeys.entries.firstOrNull { (key, value) ->
        value.isNotBlank() && WebSearchProviderCatalog.canonicalKey(key)
            .equals(canonicalProvider, ignoreCase = true)
    }?.value.orEmpty()
    return mappedKey.ifBlank {
        cfg.apiKey.takeIf {
            WebSearchProviderCatalog.canonicalKey(cfg.providerName)
                .equals(canonicalProvider, ignoreCase = true)
        }.orEmpty()
    }
}

/** 返回该供应商明确配置过的端点；没有配置时返回 null，让调用方决定是否启用该供应商。 */
internal fun resolveConfiguredProviderEndpoint(cfg: WebSearchConfig, provider: String): String? {
    val canonicalProvider = WebSearchProviderCatalog.canonicalKey(provider)
    // 普通内置供应商的地址由目录托管；旧配置里的 customUrl/endpoint 不能绕过这个契约。
    // 只有明确支持自定义地址的类型才读取用户端点，避免 UI 只读而执行层仍走旧地址。
    if (!WebSearchProviderCatalog.supportsCustomUrl(canonicalProvider)) return null
    val entryEndpoint = cfg.providerEntries
        .firstOrNull {
            it.enabled && WebSearchProviderCatalog.canonicalKey(it.type)
                .equals(canonicalProvider, ignoreCase = true)
        }
        ?.customUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (entryEndpoint != null) return entryEndpoint
    return cfg.endpoint.trim().takeIf {
        it.isNotBlank() && WebSearchProviderCatalog.canonicalKey(cfg.providerName)
            .equals(canonicalProvider, ignoreCase = true)
    }
}

/** 返回供应商实际使用的端点：配置值优先，旧字段次之，最后使用目录默认值。 */
internal fun resolveProviderEndpoint(cfg: WebSearchConfig, provider: String, defaultKey: String = provider): String =
    resolveConfiguredProviderEndpoint(cfg, provider)
        ?: WebSearchProviderCatalog.defaultEndpoint(defaultKey).orEmpty()

/**
 * 按配置构造 Auto 回退链，返回 `(引擎名, 服务)`，顺序 = 用户顺序（未设置时 = 内置顺序）。
 *
 * 提成顶层函数是为了**可测**：类里的版本要走 Koin 与运行时配置，
 * 而"配了哪几家、按什么顺序试、关掉回退时取谁"这三条判据不该靠真机或联网验证。
 */
internal fun buildSearchChain(client: OkHttpClient, cfg: WebSearchConfig): List<WebSearchService> {
    val chain = mutableListOf<Pair<String, WebSearchService>>()

    // v2.6.10: 用户自定义引擎排最前。
    // 这是他自己配的检索服务，比任何内置抓取都更贴近需求；停用的引擎保留配置但不进链路。
    cfg.customEngines
        .filter { it.enabled && it.endpoint.isNotBlank() }
        .forEach { engine -> chain.add(customSearchOrderKey(engine.name) to GenericSearchProvider(client, engine)) }

    // 用户配置的搜索 API 优先于免费抓取。
    // v3: 先读 providerEntries 里该家的 Key 池首把；旧配置（未迁移/只在 UI 里改了旧字段）
    // 仍按 apiKeys → apiKey 两级回退，保证存量配置零回归。
    // 旧格式 apiKey 仅在 providerName 指向该 provider 时回退，避免把一把 key 误发给别家。
    fun keyFor(provider: String): String = resolveProviderKey(cfg, provider)

    AUTO_CHAIN_PROVIDER_KEYS.forEach { key ->
        // 工厂可以返回 null（该引擎这次不进链），所以先取值再判空，
        // 不能在 let 里直接组 Pair —— 那会把 WebSearchService? 塞进非空的 Pair。
        val built = AUTO_CHAIN_BUILDERS[key]?.invoke(client, cfg, ::keyFor)
        if (built != null) chain.add(key to built)
    }

    // 用户顺序作用于整条链（含自定义引擎与免费抓取）：谁家的 API 更稳、更快，用户比硬编码清楚。
    val ordered = orderWebSearchChain(chain, cfg.providerOrder).map { it.second }
    // 允许高级用户关闭自动 fallback；默认开启，普通用户无需配置即可使用整条免费链。
    // 关掉时取的是"用户排第一的那个"，不再是硬编码的第一位。
    return if (cfg.fallbackEnabled) ordered else ordered.take(1)
}

/**
 * 每个引擎键怎么建：一张表，而不是一个 15 支的 `when`。
 *
 * 写成表有两个理由：
 *  - 复杂度：`when` 版把 `buildSearchChain` 顶到 39/20（detekt 的圈复杂度判据），
 *    说明它已经不是一个能一眼看完的函数了；
 *  - 顺序与构造**同源**：表里有哪些键、`AUTO_CHAIN_PROVIDER_KEYS` 就是什么顺序，
 *    不会出现"表里加了新引擎，链里忘加"或反过来。
 *
 * 返回 null 表示"这次不进链"（没配密钥的内置 API 不该空转一次请求）。
 */
internal val AUTO_CHAIN_BUILDERS: Map<
    String,
    (OkHttpClient, WebSearchConfig, (String) -> String) -> WebSearchService?,
    > = mapOf(
    "Tavily" to { client, cfg, keyFor ->
        keyFor("Tavily").takeIf { it.isNotBlank() }?.let {
            TavilyProvider(client, it, resolveProviderEndpoint(cfg, "Tavily"))
        }
    },
    "Perplexity" to { client, cfg, keyFor ->
        keyFor("Perplexity").takeIf { it.isNotBlank() }?.let {
            PerplexitySearchProvider(client, it, resolveProviderEndpoint(cfg, "Perplexity"))
        }
    },
    "Brave" to { client, cfg, keyFor ->
        keyFor("Brave").takeIf {
            it.isNotBlank()
        }?.let { BraveSearchProvider(client, it, resolveProviderEndpoint(cfg, "Brave")) }
    },
    "Serper" to { client, cfg, keyFor ->
        keyFor("Serper").takeIf {
            it.isNotBlank()
        }?.let { SerperSearchProvider(client, it, resolveProviderEndpoint(cfg, "Serper")) }
    },
    "Zhipu" to { client, cfg, keyFor ->
        keyFor("Zhipu").takeIf {
            it.isNotBlank()
        }?.let { ZhipuSearchProvider(client, it, resolveProviderEndpoint(cfg, "Zhipu")) }
    },
    "Bocha" to { client, cfg, keyFor ->
        keyFor("Bocha").takeIf {
            it.isNotBlank()
        }?.let { BochaSearchProvider(client, it, resolveProviderEndpoint(cfg, "Bocha")) }
    },
    "Metaso" to { client, cfg, keyFor ->
        keyFor("Metaso").takeIf {
            it.isNotBlank()
        }?.let { MetasoSearchProvider(client, it, resolveProviderEndpoint(cfg, "Metaso")) }
    },
    "Exa" to { client, cfg, keyFor ->
        keyFor("Exa").takeIf {
            it.isNotBlank()
        }?.let { ExaSearchProvider(client, it, resolveProviderEndpoint(cfg, "Exa")) }
    },
    "Firecrawl" to { client, cfg, keyFor ->
        keyFor("Firecrawl").takeIf {
            it.isNotBlank()
        }?.let { FirecrawlProvider(client, it, resolveProviderEndpoint(cfg, "Firecrawl")) }
    },
    "Jina" to { client, cfg, keyFor ->
        keyFor("Jina").takeIf { it.isNotBlank() }?.let {
            JinaProvider(client, it, resolveProviderEndpoint(cfg, "Jina"))
        }
    },
    "DeepSeek" to { client, cfg, keyFor ->
        keyFor("DeepSeek").takeIf { it.isNotBlank() }?.let {
            GenericSearchProvider(
                client,
                DEEPSEEK_SEARCH_ENGINE.copy(
                    apiKey = it,
                    endpoint = resolveProviderEndpoint(cfg, "DeepSeek"),
                ),
            )
        }
    },
    // 自托管 SearXNG 属于用户自己的搜索服务，优先于公共免费抓取。
    "SearXNG" to { client, cfg, _ ->
        resolveConfiguredProviderEndpoint(cfg, "SearXNG")?.let { SearXNGProvider(client, it) }
    },
    "Custom API" to { client, cfg, _ ->
        val selected = WebSearchProviderCatalog.canonicalKey(cfg.providerName)
        val hasEntry = cfg.providerEntries.any {
            it.enabled && WebSearchProviderCatalog.canonicalKey(it.type)
                .equals("Custom API", ignoreCase = true)
        }
        if ((selected.equals("Custom API", ignoreCase = true) || hasEntry) &&
            resolveProviderKey(cfg, "Custom API").isNotBlank()
        ) {
            TavilyProvider(
                client,
                resolveProviderKey(cfg, "Custom API"),
                resolveProviderEndpoint(cfg, "Custom API", "Tavily"),
            )
        } else {
            null
        }
    },
    // 最后才走免费 HTTP 抓取。这里不启动 Android WebView，也不使用手机浏览器内核。
    "Bing" to { client, _, _ -> BingProvider(client) },
    "Baidu" to { client, _, _ -> BaiduProvider(client) },
)

/**
 * 按用户给的顺序重排回退链。**稳定**且**容错**：
 *
 *  - `order` 里出现且链上有的键，按 `order` 的先后排前面；
 *  - 其余项保持原本的相对顺序跟在后面（包括用户没提到的引擎）；
 *  - `order` 里写了链上不存在/没配置的键直接忽略——用户改了别家的配置不该让搜索整条挂掉；
 *  - `order` 为空 ⇒ 原样返回（等于旧行为，不需要任何数据迁移）。
 *
 * 抽成纯函数是为了能被单独测：链的构造要拉 OkHttp 与真实 provider，
 * 而"顺序到底有没有被尊重"这条判据不该靠联网测试来证明。
 */
internal fun orderWebSearchChain(chain: List<Pair<String, WebSearchService>>, order: List<String>): List<Pair<String, WebSearchService>> {
    if (order.isEmpty() || chain.size <= 1) return chain
    val byKey = chain.toMap()
    val wanted = order.mapNotNull { key -> byKey.entries.firstOrNull { it.key == key }?.toPair() }
        .distinctBy { it.second }
    val wantedServices = wanted.map { it.second }.toSet()
    return wanted + chain.filterNot { wantedServices.contains(it.second) }
}

/** 自定义引擎在顺序表里的键：带前缀，避免和内置引擎同名时互相顶掉。 */
internal fun customSearchOrderKey(engineName: String): String = "custom:$engineName"
