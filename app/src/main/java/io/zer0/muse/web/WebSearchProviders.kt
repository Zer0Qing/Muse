package io.zer0.muse.web

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.util.stripHtmlSimple
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
    private val endpoint: String = "https://searx.be",
) : WebSearchService {
    override val name: String = "SearXNG"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            // H-WS1: 用 resultOf 替代 runCatching,避免吞 CancellationException
            resultOf {
                val url = "$endpoint/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    "&format=json&categories=general&language=zh-CN"
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
    private val endpoint: String = "https://api.tavily.com",
) : WebSearchService {
    override val name: String = "Tavily"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
 * 按 既有实现 的 web-search.ts 设计:
 *  - 按优先级依次尝试多个 provider,遇到空结果/低质量/异常则自动换下一个
 *  - 检测日期计算器、词典/百科释义等低质量结果并跳过
 *  - 最终结果去重、按查询相关性排序
 *
 * Provider 优先级:
 *  1. 用户配置了 API key 的商用 provider(Tavily/Brave/Serper/Zhipu/Bocha/Metaso/Exa/Firecrawl/Perplexity)
 *  2. 免费/免 key provider: Bing → Jina → SearXNG
 */
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

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
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
        var firstLowQualityResults: List<WebSearchResult>? = null

        for (provider in chain) {
            val providerName = provider.name
            try {
                // 单 provider 限速预检查
                if (!SearchRateLimiter.await(providerName)) {
                    attempts.add("$providerName: rate-limited")
                    continue
                }
                val results = provider.search(query, maxResults)
                if (results.isEmpty()) {
                    attempts.add("$providerName: empty")
                    continue
                }
                if (isLikelyLowQualityResults(query, results)) {
                    attempts.add("$providerName: low-quality")
                    if (firstLowQualityResults == null) {
                        firstLowQualityResults = results
                    }
                    continue
                }
                attempts.add("$providerName: ok(${results.size})")
                Logger.i("AutoWebSearch", "query='$query' attempts=$attempts")
                return normalizeResults(query, results, maxResults)
            } catch (e: SearchRateLimitException) {
                attempts.add("$providerName: rate-limit")
            } catch (e: Exception) {
                attempts.add("$providerName: error(${e.message})")
                Logger.w("AutoWebSearch", "$providerName failed", e)
            }
        }

        // 全部 fallback 均失败或低质量:返回第一次拿到的低质量结果兜底,避免完全空手
        if (firstLowQualityResults != null) {
            Logger.w("AutoWebSearch", "query='$query' all low-quality, returning first attempt. attempts=$attempts")
            return normalizeResults(query, firstLowQualityResults, maxResults)
        }

        Logger.w("AutoWebSearch", "query='$query' all failed. attempts=$attempts")
        return emptyList()
    }

    private fun buildProviderChain(cfg: WebSearchConfig): List<WebSearchService> {
        val chain = mutableListOf<WebSearchService>()

        // 用户配置的搜索 API 优先于免费抓取。apiKeys 是新格式，apiKey 是旧格式；
        // 旧格式仅在 providerName 指向该 provider 时回退，避免把一个 key 误发给其他供应商。
        fun keyFor(provider: String): String =
            cfg.apiKeys[provider].orEmpty().ifBlank {
                cfg.apiKey.takeIf { cfg.providerName.equals(provider, ignoreCase = true) }.orEmpty()
            }
        fun addIfConfigured(provider: String, factory: (String) -> WebSearchService) {
            keyFor(provider).takeIf { it.isNotBlank() }?.let { chain.add(factory(it)) }
        }
        addIfConfigured("Tavily") { TavilyProvider(client, it) }
        addIfConfigured("Perplexity") { PerplexitySearchProvider(client, it) }
        addIfConfigured("Brave") { BraveSearchProvider(client, it) }
        addIfConfigured("Serper") { SerperSearchProvider(client, it) }
        addIfConfigured("Zhipu") { ZhipuSearchProvider(client, it) }
        addIfConfigured("Bocha") { BochaSearchProvider(client, it) }
        addIfConfigured("Metaso") { MetasoSearchProvider(client, it) }
        addIfConfigured("Exa") { ExaSearchProvider(client, it) }
        addIfConfigured("Firecrawl") { FirecrawlProvider(client, it) }
        addIfConfigured("Jina") { JinaProvider(client, it) }
        // 自托管 SearXNG 属于用户自己的搜索服务，优先于公共免费抓取。
        if (cfg.endpoint.isNotBlank() && cfg.providerName.equals("SearXNG", ignoreCase = true)) {
            chain.add(SearXNGProvider(client, cfg.endpoint))
        }
        if (cfg.providerName.equals("Custom API", ignoreCase = true) && cfg.apiKey.isNotBlank()) {
            chain.add(TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" }))
        }

        // 最后才走免费 HTTP 抓取。这里不启动 Android WebView，也不使用手机浏览器内核。
        chain.add(BingProvider(client))
        chain.add(BaiduProvider(client))

        // 允许高级用户关闭自动 fallback；默认开启，普通用户无需配置即可使用整条免费链。
        return if (cfg.fallbackEnabled) chain else chain.take(1)
    }

    companion object {
        /** 已知低质量域名(日期计算器、单位换算等工具站)。 */
        private val LOW_QUALITY_DOMAINS = setOf(
            "timeanddate.com", "datecalculator.org", "calculator.net", "calculatorsoup.com",
            "unitconverters.net", "rapidtables.com", "datetime360.com", "timedatecalc.com",
        )

        /** 中文词典/释义类站点关键词。 */
        private val DICTIONARY_PATTERNS = listOf("zdic.net", "汉典", "字典", "词典", "基本解释", "汉语意思")

        /** 日期计算器类关键词。 */
        private val DATE_CALC_PATTERNS = listOf(
            "date calculator", "days calculator", "age calculator", "day calculator",
            "datecalculator", "dayscalculator", "hours calculator", "time calculator",
        )

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
         * 规范化结果:过滤空字段、去重、移除低质量、按相关性排序、截断。
         */
        private fun normalizeResults(
            query: String,
            results: List<WebSearchResult>,
            maxResults: Int,
        ): List<WebSearchResult> = results
            .asSequence()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .filter { !isLowQualityResult(query, it) }
            .distinctBy { it.url.lowercase().trimEnd('/') }
            .sortedByDescending { scoreResult(query, it) }
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

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        // 限速预检查：退避期内直接抛 SearchRateLimitException，不发起请求，避免反复撞 429/402 被封禁
        if (!SearchRateLimiter.await(name)) {
            throw SearchRateLimitException(
                cause = "$name rate-limited (await=false)",
                retryAfterMs = SearchRateLimiter.getRetryAfterMs(name),
            )
        }
        return delegate.search(query, maxResults)
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
            return when (cfg.providerName) {
        // v1.135: Auto 多引擎 fallback
        "Auto" -> AutoWebSearchService(client, cfg)
        "Bing" -> BingProvider(client)
        "Jina" -> JinaProvider(client, cfg.apiKey)
        // M-WS3: 自定义 API — 按 apiKey 是否非空二次分发:
        //  有 apiKey 走 Tavily 兼容接口,无 apiKey 走 SearXNG 兼容接口(原先无条件映射到 SearXNG,忽略 apiKey)
        "Custom API" ->
            if (cfg.apiKey.isNotBlank()) {
                TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" })
            } else {
                SearXNGProvider(client, cfg.endpoint.ifBlank { "https://searx.be" })
            }
        "SearXNG" -> SearXNGProvider(client, cfg.endpoint.ifBlank { "https://searx.be" })
        "Tavily" -> TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" })
        // v1.97: 新增搜索 provider
        "Zhipu" -> ZhipuSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://open.bigmodel.cn/api/paas/v4" })
        "Brave" -> BraveSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.search.brave.com/res/v1" })
        "Serper" -> SerperSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://google.serper.dev" })
        "Bocha" -> BochaSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.bochaai.com/v1" })
        "Metaso" -> MetasoSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://metaso.cn/api/v1" })
        "Exa" -> ExaSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.exa.ai" })
        "Firecrawl" -> FirecrawlProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.firecrawl.dev/v1" })
        // Perplexity AI 搜索 API(sonar-pro 模型 + citations),既有实现 PerplexityService
        "Perplexity" -> PerplexitySearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.perplexity.ai" })
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
    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
) : WebSearchService {
    override val name: String = "Jina"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            val endpoints = listOf("https://s.jinaai.cn/", "https://s.jina.ai/")
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
    private val endpoint: String = "https://open.bigmodel.cn/api/paas/v4",
) : WebSearchService {
    override val name: String = "Zhipu"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
    private val endpoint: String = "https://api.search.brave.com/res/v1",
) : WebSearchService {
    override val name: String = "Brave"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            if (apiKey.isBlank()) {
                Logger.w("Brave", "no API key configured, skip")
                return@withContext emptyList()
            }
            resultOf {
                val url = "$endpoint/web/search?q=" +
                    java.net.URLEncoder.encode(query, "UTF-8") + "&count=$maxResults"
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
    private val endpoint: String = "https://google.serper.dev",
) : WebSearchService {
    override val name: String = "Serper"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            if (apiKey.isBlank()) {
                Logger.w("Serper", "no API key configured, skip")
                return@withContext emptyList()
            }
            resultOf {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("q", kotlinx.serialization.json.JsonPrimitive(query))
                    put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
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
    private val endpoint: String = "https://api.bochaai.com/v1",
) : WebSearchService {
    override val name: String = "Bocha"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
    private val endpoint: String = "https://metaso.cn/api/v1",
) : WebSearchService {
    override val name: String = "Metaso"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
    private val endpoint: String = "https://api.exa.ai",
) : WebSearchService {
    override val name: String = "Exa"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            if (apiKey.isBlank()) {
                Logger.w("Exa", "no API key configured, skip")
                return@withContext emptyList()
            }
            resultOf {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("query", kotlinx.serialization.json.JsonPrimitive(query))
                    put("numResults", kotlinx.serialization.json.JsonPrimitive(maxResults))
                    put("contents", kotlinx.serialization.json.buildJsonObject {
                        put("text", kotlinx.serialization.json.buildJsonObject {
                            put("maxCharacters", kotlinx.serialization.json.JsonPrimitive(300))
                        })
                    })
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
 * Firecrawl 搜索 provider(既有实现 Firecrawl 实现版)。
 *
 * 协议: POST {endpoint}/search,请求体为 JSON。
 * 需要 API key。支持网页抓取与搜索。
 */
class FirecrawlProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.firecrawl.dev/v1",
) : WebSearchService {
    override val name: String = "Firecrawl"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
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
 * Perplexity Search Provider — Perplexity AI 搜索 API(既有实现 PerplexityService)。
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
    private val endpoint: String = "https://api.perplexity.ai",
) : WebSearchService {
    override val name: String = "Perplexity"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(AppDispatchers.io) {
            if (apiKey.isBlank()) {
                Logger.w("Perplexity", "no API key configured, skip")
                return@withContext emptyList()
            }
            // H-WS1: 用 resultOf 替代 runCatching,避免吞 CancellationException
            resultOf {
                // 用 buildJsonObject 构造请求体,字符串转义由序列化库处理
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
