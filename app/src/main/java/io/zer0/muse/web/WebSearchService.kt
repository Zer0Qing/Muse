package io.zer0.muse.web

import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
     * 默认实现委托给 [search]：纯抓取类 provider 没有原生时间参数，时间过滤由
     * [WebSearchResultPostProcessor] 在结果层统一兜底，不在这里假装生效。
     * 原生支持时敏的 provider 应覆写本方法把 date_range 映射成各自请求字段；
     * 编排层（Auto/Composite）必须覆写以把 options 转发给链内 provider。
     *
     * @param options 额外选项 map,可含 date_range / time_period 等 key
     */
    suspend fun searchWithOptions(query: String, maxResults: Int = 5, options: Map<String, String> = emptyMap()): List<WebSearchResult> =
        search(query, maxResults)
}

/** 从 options 里取时敏范围，兼容 date_range / time_period 两种 key。 */
internal fun Map<String, String>.dateRangeOrNull(): String? = (get("date_range") ?: get("time_period"))?.takeIf { it.isNotBlank() }
    ?.lowercase()?.takeIf { it in setOf("past_day", "past_week", "past_month", "past_year") }

/** date_range → 各引擎的时敏取值：day/week/month/year。 */
internal fun String?.toTimeRangeWord(): String? = when (this) {
    "past_day" -> "day"
    "past_week" -> "week"
    "past_month" -> "month"
    "past_year" -> "year"
    else -> null
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

/**
 * 用户自定义的搜索引擎。
 *
 * 面向「接自己的检索服务」：自托管 SearXNG、公司内部网关、任意返回 JSON 的搜索 API。
 * 不用改代码，填模板即可接入。
 *
 * @param id 稳定标识（列表增删用，不要用名称当 id，重命名会断）
 * @param name 展示名，同时作为链路里的 provider 名
 * @param endpoint 完整请求地址（含协议，不含 query string）
 * @param apiKey 可选，非空时以 Bearer 注入
 * @param method GET 或 POST
 * @param queryParam GET 时的查询参数名，默认 q
 * @param bodyTemplate POST 时的请求体（JSON 文本，{query} 会被替换，{count} 换结果数）
 * @param resultsPath 结果数组在响应 JSON 里的路径，如 data.results / web.pages，留空表示根是数组
 * @param titlePath 单条结果里标题的字段名，默认 title
 * @param urlPath 单条结果里链接的字段名，默认 url
 * @param snippetPath 单条结果里摘要的字段名，默认 content
 * @param enabled 是否启用（停用后保留配置但不进链路）
 */
@Serializable
data class CustomSearchEngine(
    val id: String = "",
    val name: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val method: String = "GET",
    val queryParam: String = "q",
    val bodyTemplate: String = "",
    val resultsPath: String = "",
    val titlePath: String = "title",
    val urlPath: String = "url",
    val snippetPath: String = "content",
    val enabled: Boolean = true,
)

/**
 * 一家联网搜索供应商的完整填写结果。
 *
 * 与旧的「全局单 endpoint + 每家一把 key」不同，这里每家自带端点与 Key 池，
 * 支持按类型动态表单、自定义 URL 可选、一个供应商多把 Key。
 * [apiKeys] 用 List 而非拼接字符串，避免解析歧义；空串项由调用方过滤。
 *
 * @param id 稳定标识（列表增删用，重命名不该断链）
 * @param type [WebSearchProviderCatalog] 的类型键，决定动态表单字段集
 * @param customUrl 用户覆盖的端点；留空表示用该类型默认端点
 * @param apiKeys 该供应商的 Key 池，可多把，按轮询使用
 * @param model 需要模型名的类型才填
 * @param extras 类型专属字段（引擎/地区/语言/时间/页码等），按 Catalog 声明的键存放
 * @param enabled 是否参与链路；停用保留配置但不进链
 */
@Serializable
data class WebSearchProviderEntry(
    val id: String = "",
    val type: String = "",
    val customUrl: String = "",
    val apiKeys: List<String> = emptyList(),
    val model: String = "",
    val extras: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class WebSearchConfig(
    /** 搜索模式；默认自动优先模型原生搜索，再回退到本地 API/HTTP 链。 */
    val mode: WebSearchMode = WebSearchMode.AUTO,
    val providerName: String = "Auto",
    val apiKey: String = "",
    val endpoint: String = "",
    /** v1 兼容字段：mode=OFF 时同步视为关闭；旧调用方仍可读取 enabled。 */
    val enabled: Boolean = false,
    /** v2: 单轮搜索预算，防止模型在一次回答里无限重复调用。默认 50(实际放开,仅防死循环)。 */
    val maxSearchesPerTurn: Int = 50,
    /** v2: 单次最多返回结果数。 */
    val maxResults: Int = 5,
    /** v2: provider 失败后是否继续走免费备用链。 */
    val fallbackEnabled: Boolean = true,

    /**
     * Auto 回退链的**用户顺序**（引擎名，按用户想要的先后排列）。
     *
     * 空 = 沿用内置顺序（旧行为，一条都不变），所以老配置不需要迁移。
     * 列出的引擎按这里的顺序排在前面；没列出的保持它们原本的相对顺序跟在后面；
     * 写了不存在或没配置的引擎名会被忽略（不是错误——用户改了插件配置不该让搜索挂掉）。
     *
     * 为什么值得做：内置顺序是"自定义引擎 → 配了 Key 的 API → 免费抓取"，
     * 但谁家的 API 更稳、谁更快，用户比代码清楚；以前这层只能看不能调。
     */
    val providerOrder: List<String> = emptyList(),
    /** 搜索策略配置版本，旧配置缺失时由设置仓库迁移到当前默认值。 */
    val policyVersion: Int = 0,
    /**
     * v1.135: 多搜索引擎 API key 映射(providerName → key)。
     * 供 [AutoWebSearchService] 构建 provider fallback 链时使用。
     * 单 provider 模式下的 [apiKey] 会与当前 provider 的 key 保持同步。
     */
    val apiKeys: Map<String, String> = emptyMap(),
    /**
     * v2.6.10: 用户自定义搜索引擎列表。
     *
     * 自定义引擎在 Auto 链路里排在最前 —— 那是用户自己配的服务，
     * 比任何内置免费抓取都更贴近他的需求。
     */
    val customEngines: List<CustomSearchEngine> = emptyList(),
    /**
     * v3: 按供应商类型组织的填写结果。旧字段全部保留做兼容读取，
     * 新写入以这里为主；[migrateToV3] 负责把旧值归并进来。
     */
    val providerEntries: List<WebSearchProviderEntry> = emptyList(),
) {
    /**
     * M-04: 返回所有密钥字段已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。按 WebServerConfig.encrypted 模式。
     *
     * v3: 覆盖 [providerEntries] 的 Key 池与 [customEngines] 的 apiKey —— 后者此前是明文落盘。
     */
    suspend fun encrypted(): WebSearchConfig {
        val encryptedEntries = buildList {
            providerEntries.forEach { entry ->
                val keys = buildList {
                    entry.apiKeys.forEach { add(SecureKeyStore.encrypt(it)) }
                }
                add(entry.copy(apiKeys = keys))
            }
        }
        val encryptedCustom = buildList {
            customEngines.forEach { add(it.copy(apiKey = SecureKeyStore.encrypt(it.apiKey))) }
        }
        return copy(
            apiKey = SecureKeyStore.encrypt(apiKey),
            apiKeys = apiKeys.mapValues { SecureKeyStore.encrypt(it.value) },
            providerEntries = encryptedEntries,
            customEngines = encryptedCustom,
        )
    }

    /**
     * M-04: 返回 apiKey / apiKeys 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。按 WebServerConfig.decrypted 模式。
     *
     * v1.135 兼容:旧版只保存了单 [apiKey],这里把它同步到 [apiKeys] 映射,
     * 使切换到 Auto 模式后仍能使用已配置的 key。
     */
    suspend fun decrypted(): WebSearchConfig {
        val decryptedEntries = buildList {
            providerEntries.forEach { entry ->
                val keys = buildList {
                    entry.apiKeys.forEach { add(SecureKeyStore.decrypt(it)) }
                }
                add(entry.copy(apiKeys = keys))
            }
        }
        val decryptedCustom = buildList {
            customEngines.forEach { add(it.copy(apiKey = SecureKeyStore.decrypt(it.apiKey))) }
        }
        val decryptedKey = SecureKeyStore.decrypt(apiKey)
        val decryptedKeys = apiKeys.mapValues { SecureKeyStore.decrypt(it.value) }
        val base = copy(
            apiKey = decryptedKey,
            apiKeys = decryptedKeys,
            providerEntries = decryptedEntries,
            customEngines = decryptedCustom,
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
        val SUPPORTED_PROVIDERS: List<String> = WebSearchProviderCatalog.selectableTypes

        /** v2.3.1: 自定义 API 引擎名 — 存于配置(providerName)的标识,不可随意改动。 */
        const val CUSTOM_PROVIDER_NAME: String = "自定义 API"

        /** v2.3.1: 自定义 API 的英文历史写法(旧配置可能存此值)。 */
        const val CUSTOM_PROVIDER_NAME_EN: String = "Custom API"

        /** v2.3.1: 自定义 API 的两种写法集合(UI 去重与回退链判定共用)。 */
        val CUSTOM_PROVIDER_NAMES: Set<String> = setOf(CUSTOM_PROVIDER_NAME, CUSTOM_PROVIDER_NAME_EN)

        /**
         * 需要 API Key 的 provider 集合(WebSearchSection 据此决定是否显示 API Key 输入框)。
         *
         * - "自定义 API" 在 UI 文案里显示为中文,这里两种写法都纳入以防遗漏
         * - Bing / SearXNG / Jina(无 key 走免费层)不需要 API Key,故不在集合内
         * - 同时覆盖 Brave / Perplexity / Exa / Bocha 等所有商用搜索 API
         */
        val PROVIDERS_NEEDING_API_KEY: Set<String> = WebSearchProviderCatalog.keysNeedingApiKey

        /**
         * 把 v2 及更早的「全局单 key / 单 endpoint / 每家一把 key」配置归并为 v3 的
         * [providerEntries]，并抬版本号。**纯函数、幂等**：已是 v3 直接返回原对象。
         *
         * 为什么必须显式迁移而不是依赖 `AppJson.coerceInputValues`：宽容解码对「新增字段」
         * 会给默认值，但旧值仍散落在 apiKeys/apiKey/endpoint/customEngines 里，不归并就等于
         * 用户升级后所有已配的 Key 和自定义端点凭空消失。旧字段一律保留做兼容读取。
         */
        fun migrateToV3(config: WebSearchConfig): WebSearchConfig {
            if (config.policyVersion >= 3) return config
            val budgetFixed = if (config.maxSearchesPerTurn < 50) 50 else config.maxSearchesPerTurn
            val merged = LinkedHashMap<String, MutableList<String>>()

            // 1. apiKeys 映射：每家一把 → 每家 Key 池的第一把。
            config.apiKeys.forEach { (provider, key) ->
                if (key.isNotBlank()) merged.getOrPut(provider) { mutableListOf() }.add(key)
            }
            // 2. 旧单 apiKey + providerName：并入该家（若该家还没有 key）。
            if (config.providerName != "Auto" && config.apiKey.isNotBlank()) {
                val pool = merged.getOrPut(config.providerName) { mutableListOf() }
                if (!pool.contains(config.apiKey)) pool.add(config.apiKey)
            }
            // 3. 旧全局 endpoint 归到当前生效那家（Auto 时不属于任何一家，不臆造）。
            val endpointOwner = config.providerName.takeIf { it != "Auto" && config.endpoint.isNotBlank() }
            // 4. customEngines 各自成条目，端点与 key 都带过去。
            val customEntries = config.customEngines.map { engine ->
                WebSearchProviderEntry(
                    id = engine.id.ifBlank { "custom:${engine.name}" },
                    type = "Custom API",
                    customUrl = engine.endpoint,
                    apiKeys = listOfNotNull(engine.apiKey.takeIf { it.isNotBlank() }),
                    enabled = engine.enabled,
                )
            }
            val apiEntries = merged.map { (provider, keys) ->
                WebSearchProviderEntry(
                    id = provider,
                    type = provider,
                    customUrl = if (provider == endpointOwner) config.endpoint else "",
                    apiKeys = keys.distinct(),
                )
            }
            // 自托管类型（如 SearXNG）可以不配 key 只填实例 URL；这类旧配置的端点
            // 不会出现在 merged 里，必须单独补一条，否则升级后实例地址凭空消失。
            val endpointOnlyEntry = endpointOwner
                ?.takeIf { owner -> apiEntries.none { it.type == owner } }
                ?.let { owner ->
                    WebSearchProviderEntry(
                        id = owner,
                        type = owner,
                        customUrl = config.endpoint,
                    )
                }
            // 自定义引擎与 apiKeys 里同名的条目去重：以自定义引擎（信息更全）为准。
            val customIds = customEntries.map { it.id }.toSet()
            val entries = customEntries + apiEntries.filter { it.id !in customIds } + listOfNotNull(endpointOnlyEntry)
            return config.copy(
                maxSearchesPerTurn = budgetFixed,
                policyVersion = 3,
                providerEntries = entries,
            )
        }
    }
}

/**
 * Phase 8.4: 共享 OkHttpClient(供 web search 用,与 ChatService 的 client 独立,
 * 避免长连接 SSE 与短连接 search 互相影响)。
 */
fun createWebSearchClient(): OkHttpClient = OkHttpClient.Builder()
    // MuMu/部分国内网络的 IPv6 路由经常直接 ECONNREFUSED；优先 IPv4，IPv6 仍保留为备用。
    .dns(object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    })
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    // v2.6.9: 改用动态 ProxyProvider（改代理立即生效）
    .also { io.zer0.ai.core.ProxyProvider.applyTo(it) }
    .build()

/**
 * v2.6.0: 根据 [ProxyConfig] 为 OkHttpClient.Builder 设置代理与代理认证。
 *
 * 公共工具 —— 原为 private 仅供 websearch 使用,导致 OAuth token 交换、模型请求等
 * 其余网络链路直连、走不了全局代理(用户实测:浏览器经代理授权成功,但应用 token
 * 交换失败,因直连 auth.openai.com 被地区拒绝)。
 */
fun OkHttpClient.Builder.applyProxy(config: ProxyConfig): OkHttpClient.Builder {
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
internal suspend fun OkHttpClient.executeAsync(request: Request): Response = suspendCancellableCoroutine { cont ->
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
