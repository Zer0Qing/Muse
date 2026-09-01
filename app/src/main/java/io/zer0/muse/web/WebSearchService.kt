package io.zer0.muse.web

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SecureKeyStore
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 8.4: Web 搜索结果条目。
 *
 * @param title 标题
 * @param url 原文链接(可点击)
 * @param snippet 摘要片段(已截断,便于注入 LLM 上下文)
 * @param source 来源(provider 名,如 "SearXNG"/"Tavily")
 */
@Serializable
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
)

/**
 * Phase 8.4: 联网搜索服务。
 *
 * 设计支持多家搜索 provider,
 * 统一抽象为 [search] 接口,返回 [WebSearchResult] 列表。
 *
 * 当前实现:
 *  - [SearXNGProvider]: 开源元搜索,自托管,无需 API key(默认 endpoint = https://searx.be)
 *  - [TavilyProvider]: 商用 AI 搜索 API,需 API key(https://tavily.com)
 *
 * 后续可扩展: Brave Search / Google CSE / Serper 等(留 Phase 8.4+)
 *
 * 用法:
 *  - ChatViewModel 在用户开启"联网搜索"时,先调 [search] 取前 N 条结果
 *  - 把结果格式化为 markdown 摘要,作为 SYSTEM 上下文注入(prompt engineering)
 *  - LLM 基于联网上下文回答,生成的回复中链接由 MarkdownText 自动渲染为可点击
 */
interface WebSearchService {
    /** provider 标识(用于 settings 显示 + 日志)。 */
    val name: String

    /**
     * 执行搜索。
     * @param query 查询词
     * @param maxResults 最大返回数(默认 5)
     * @return 结果列表;失败返回空列表(错误已 log,不抛异常)
     */
    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchResult>

    /**
     * 带额外选项的搜索(date_range / time_period 等时间范围过滤)。
     *
     * 默认实现忽略 options 直接委托给 [search],具体 provider 可按需覆写以支持
     * 时间范围等高级筛选。这样老 provider 无需改动即可兼容新调用。
     *
     * @param options 额外选项 map,可含 date_range / time_period 等 key
     */
    suspend fun searchWithOptions(
        query: String,
        maxResults: Int = 5,
        options: Map<String, String> = emptyMap(),
    ): List<WebSearchResult> = search(query, maxResults)
}

/**
 * SearXNG provider — 开源元搜索,默认公共实例 https://searx.be。
 *
 * 协议: GET {endpoint}/search?q={query}&format=json&categories=general
 * 返回: { results: [{ title, url, content, ... }] }
 *
 * 自托管实例可改 endpoint(在 settings 配置)。公共实例可能限流/不可用,
 * 故失败时降级返回空列表,不阻塞对话。
 */

/**
 * Web 搜索配置(持久化在 SettingsRepository)。
 *
 * @param providerName provider 名("SearXNG" / "Tavily")
 * @param apiKey API key(Tavily 必填,SearXNG 忽略)
 * @param endpoint 自定义 endpoint(SearXNG 自托管实例 / Tavily 代理)
 * @param enabled 是否启用"联网搜索"开关(InputBar 上的开关)
 */
@Serializable
enum class WebSearchMode {
    OFF,
    /** 自动优先：支持时使用模型原生搜索，否则走本地搜索链。 */
    AUTO,
    LOCAL,
    NATIVE,
}

@Serializable
data class WebSearchConfig(
    /** 搜索模式；默认自动优先模型原生搜索，再回退到本地 API/HTTP 链。 */
    val mode: WebSearchMode = WebSearchMode.AUTO,
    val providerName: String = "Auto",
    val apiKey: String = "",
    val endpoint: String = "",
    /** v1 兼容字段：mode=OFF 时同步视为关闭；旧调用方仍可读取 enabled。 */
    val enabled: Boolean = false,
    /** v2: 单轮搜索预算，防止模型在一次回答里无限重复调用。 */
    val maxSearchesPerTurn: Int = 5,
    /** v2: 单次最多返回结果数。 */
    val maxResults: Int = 5,
    /** v2: provider 失败后是否继续走免费备用链。 */
    val fallbackEnabled: Boolean = true,
    /** 搜索策略配置版本，旧配置缺失时由设置仓库迁移到当前默认值。 */
    val policyVersion: Int = 0,
    /**
     * v1.135: 多搜索引擎 API key 映射(providerName → key)。
     * 供 [AutoWebSearchService] 构建 provider fallback 链时使用。
     * 单 provider 模式下的 [apiKey] 会与当前 provider 的 key 保持同步。
     */
    val apiKeys: Map<String, String> = emptyMap(),
) {
    /**
     * M-04: 返回 apiKey / apiKeys 已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。按 WebServerConfig.encrypted 模式。
     */
    suspend fun encrypted(): WebSearchConfig = copy(
        apiKey = SecureKeyStore.encrypt(apiKey),
        apiKeys = apiKeys.mapValues { SecureKeyStore.encrypt(it.value) },
    )

    /**
     * M-04: 返回 apiKey / apiKeys 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。按 WebServerConfig.decrypted 模式。
     *
     * v1.135 兼容:旧版只保存了单 [apiKey],这里把它同步到 [apiKeys] 映射,
     * 使切换到 Auto 模式后仍能使用已配置的 key。
     */
    suspend fun decrypted(): WebSearchConfig {
        val decryptedKey = SecureKeyStore.decrypt(apiKey)
        val decryptedKeys = apiKeys.mapValues { SecureKeyStore.decrypt(it.value) }
        val base = copy(
            apiKey = decryptedKey,
            apiKeys = decryptedKeys,
        )
        return if (
            base.providerName != "Auto" &&
            decryptedKey.isNotBlank() &&
            !decryptedKeys.containsKey(base.providerName)
        ) {
            base.copy(apiKeys = decryptedKeys + (base.providerName to decryptedKey))
        } else {
            base
        }
    }

    companion object {
        /**
         * 支持的 provider 列表(SettingsScreen 下拉用)。
         * v1.28: 自带只保留 Bing(免费无需 API key),另保留一个"自定义 API"
         * 选项供用户接入自己的搜索 API(SearXNG/Tavily 兼容接口)。
         *
         * v1.98: 新增 Perplexity(sonar-pro + citations),与 Brave/Exa/Bocha 并列。
         */
        val SUPPORTED_PROVIDERS = listOf(
            "Auto",
            "Bing", "Jina", "Custom API",
            "SearXNG", "Tavily",
            "Zhipu", "Brave", "Serper", "Bocha", "Metaso", "Exa",
            "Perplexity",
        )

        /**
         * 需要 API Key 的 provider 集合(WebSearchSection 据此决定是否显示 API Key 输入框)。
         *
         * - "自定义 API" 在 UI 文案里显示为中文,这里两种写法都纳入以防遗漏
         * - Bing / SearXNG / Jina(无 key 走免费层)不需要 API Key,故不在集合内
         * - 同时覆盖 Brave / Perplexity / Exa / Bocha 等所有商用搜索 API
         */
        val PROVIDERS_NEEDING_API_KEY: Set<String> = setOf(
            "自定义 API", "Custom API",
            "Tavily",
            "Zhipu", "Brave", "Serper", "Bocha", "Metaso", "Exa",
            "Firecrawl", "Perplexity",
        )
    }
}

/**
 * Phase 8.4: 共享 OkHttpClient(供 web search 用,与 ChatService 的 client 独立,
 * 避免长连接 SSE 与短连接 search 互相影响)。
 */
fun createWebSearchClient(proxyConfig: ProxyConfig = ProxyConfig()): OkHttpClient = OkHttpClient.Builder()
    // MuMu/部分国内网络的 IPv6 路由经常直接 ECONNREFUSED；优先 IPv4，IPv6 仍保留为备用。
    .dns(object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    })
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .applyProxy(proxyConfig)
    .build()

/**
 * 根据 [ProxyConfig] 为 OkHttpClient.Builder 设置代理与代理认证。
 */
private fun OkHttpClient.Builder.applyProxy(config: ProxyConfig): OkHttpClient.Builder {
    if (!config.enabled || config.host.isBlank() || config.port <= 0) return this
    val address = InetSocketAddress.createUnresolved(config.host, config.port)
    val proxy = when (config.type.uppercase()) {
        "SOCKS", "SOCKS5" -> Proxy(Proxy.Type.SOCKS, address)
        else -> Proxy(Proxy.Type.HTTP, address)
    }
    proxy(proxy)
    if (config.username.isNotBlank() && config.password.isNotBlank()) {
        val credential = okhttp3.Credentials.basic(config.username, config.password)
        proxyAuthenticator { _, response ->
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }
    return this
}

/**
 * M-WS2: 把阻塞的 client.newCall(req).execute() 包装为可取消的 suspend 调用。
 *
 * 用 suspendCancellableCoroutine + enqueue + invokeOnCancellation{call.cancel()} 模式,
 * 协程取消时中断阻塞的网络调用(原各 provider 的 execute() 会阻塞线程且无法响应取消)。
 * 供 SearXNG/Tavily/Bing 三个 provider 复用。
 */
internal suspend fun OkHttpClient.executeAsync(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

/**
 * L-WS1: HTML 标签剥离已迁移到 io.zer0.muse.util.HtmlUtils#stripHtmlSimple。
 */
