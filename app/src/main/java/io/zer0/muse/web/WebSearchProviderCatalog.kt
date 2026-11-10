package io.zer0.muse.web

/**
 * 联网搜索供应商目录：类型清单、默认端点、按类型需要哪些字段的**单一真源**。
 *
 * 此前 `WebSearchConfig.SUPPORTED_PROVIDERS`、`AUTO_CHAIN_PROVIDER_KEYS`、
 * 各 provider 构造默认参数、`buildDelegate` 的 `.ifBlank{}` 各自维护一份端点，
 * 加一家或改端点极易漏一处。这里集中成一张只读表；执行层仍按字符串键分派，
 * 不引入枚举/sealed class，避免改动 `providerOrder` 里已持久化的引擎名。
 *
 * 本对象只描述元数据，不持有配置、不发请求、不读写持久化。
 */
object WebSearchProviderCatalog {

    /** 一家供应商的填写契约。 */
    data class Meta(
        val key: String,
        /** 是否需要 API Key（可多把）。 */
        val needsKey: Boolean = false,
        /** 默认端点；可自定义端点的类型用这个值作输入框 hint。 */
        val defaultEndpoint: String? = null,
        /** 是否允许覆盖端点（URL 可选，留空用默认）。 */
        val supportsCustomUrl: Boolean = false,
        /** 是否必须自填实例 URL（自托管类型）。 */
        val needsInstanceUrl: Boolean = false,
        /** 是否需要填模型名。 */
        val needsModel: Boolean = false,
        /** 该类型额外字段的键（供动态表单按声明渲染）。 */
        val extraFields: List<String> = emptyList(),
    )

    /**
     * Auto 回退链的内置顺序，也是设置页可选类型清单。
     * `Auto` 是编排模式、不是具体 provider，单独在 [selectableTypes] 里补上。
     * 顺序与旧的 `AUTO_CHAIN_PROVIDER_KEYS` 完全一致，改这里不会让存量 `providerOrder` 产生幽灵键。
     */
    val chainKeys: List<String> = listOf(
        "Tavily",
        "Perplexity",
        "Brave",
        "Serper",
        "Zhipu",
        "Bocha",
        "Metaso",
        "Exa",
        "Firecrawl",
        "Jina",
        "DeepSeek",
        "SearXNG",
        "Custom API",
        "Bing",
        "Baidu",
    )

    /** 下拉/网格里可选的类型：Auto 在首，其后是链上各家。 */
    val selectableTypes: List<String> = listOf("Auto") + chainKeys

    private val entries: Map<String, Meta> = listOf(
        Meta("Auto"),
        Meta("Tavily", needsKey = true, defaultEndpoint = "https://api.tavily.com", supportsCustomUrl = true),
        Meta("Perplexity", needsKey = true, defaultEndpoint = "https://api.perplexity.ai", supportsCustomUrl = true, needsModel = true),
        Meta("Brave", needsKey = true, defaultEndpoint = "https://api.search.brave.com/res/v1", supportsCustomUrl = true),
        Meta("Serper", needsKey = true, defaultEndpoint = "https://google.serper.dev", supportsCustomUrl = true),
        Meta("Zhipu", needsKey = true, defaultEndpoint = "https://open.bigmodel.cn/api/paas/v4", supportsCustomUrl = true),
        Meta("Bocha", needsKey = true, defaultEndpoint = "https://api.bochaai.com/v1", supportsCustomUrl = true),
        Meta("Metaso", needsKey = true, defaultEndpoint = "https://metaso.cn/api/v1", supportsCustomUrl = true),
        Meta("Exa", needsKey = true, defaultEndpoint = "https://api.exa.ai", supportsCustomUrl = true),
        Meta("Firecrawl", needsKey = true, defaultEndpoint = "https://api.firecrawl.dev/v1", supportsCustomUrl = true),
        Meta("Jina", needsKey = true, defaultEndpoint = "https://s.jina.ai/", supportsCustomUrl = true),
        Meta("DeepSeek", needsKey = true, defaultEndpoint = "https://api.deepseek.com/v1/tools/search", supportsCustomUrl = true),
        Meta(
            "SearXNG",
            defaultEndpoint = "https://searx.be",
            needsInstanceUrl = true,
            supportsCustomUrl = true,
            extraFields = listOf("engines", "language", "username", "password"),
        ),
        Meta("Custom API", needsKey = true, defaultEndpoint = "https://api.tavily.com", supportsCustomUrl = true),
        Meta("Bing", defaultEndpoint = "https://cn.bing.com/search"),
        Meta("Baidu", defaultEndpoint = "https://www.baidu.com/s"),
    ).associateBy { it.key }

    /** 未知类型返回空 Meta（不抛异常，沿用 `orderWebSearchChain` 的容错语义）。 */
    fun meta(key: String): Meta = entries[key] ?: Meta(key)

    fun defaultEndpoint(key: String): String? = entries[key]?.defaultEndpoint

    fun needsKey(key: String): Boolean = entries[key]?.needsKey == true

    fun supportsCustomUrl(key: String): Boolean = entries[key]?.supportsCustomUrl == true

    fun needsInstanceUrl(key: String): Boolean = entries[key]?.needsInstanceUrl == true

    fun needsModel(key: String): Boolean = entries[key]?.needsModel == true

    /** 需要 API Key 的类型集合，供 UI 与校验共用；含两种自定义 API 历史写法。 */
    val keysNeedingApiKey: Set<String> =
        entries.values.filter { it.needsKey }.map { it.key }.toSet() +
            setOf(WebSearchConfig.CUSTOM_PROVIDER_NAME, WebSearchConfig.CUSTOM_PROVIDER_NAME_EN)
}
