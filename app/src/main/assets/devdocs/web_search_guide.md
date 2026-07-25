<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 联网搜索 web_search Bing tavily searxng 实现

当用户问"联网搜索怎么实现""用哪个搜索引擎""要不要 API key""web_fetch 怎么用""Bing 怎么抓"时参考本文档。

3 个 WebSearchService provider 实现(均在 WebSearchService.kt):

1. BingProvider(默认):
   - URL: https://cn.bing.com/search?q={encode(query)}&count={maxResults*2}
   - 实现: HTML 抓取 + regex 解析。块匹配 <li class="b_algo">,块内 <h2><a href="URL">title</a></h2> 取标题链接,摘要优先 <p class="b_lineclamp...">,回退 <div class="b_caption"> 内的 <p>。
   - 不需要 API key。User-Agent: Mozilla/5.0 (Linux; Android 12) muse/1.0。

2. SearXNGProvider:
   - URL: {endpoint}/search?q={encode(query)}&format=json&categories=general&language=zh-CN。默认 endpoint https://searx.be。
   - 实现: JSON 解析 results 数组,取 title/url/content。
   - 不需要 API key(自托管实例)。可自定义 endpoint。

3. TavilyProvider:
   - URL: POST {endpoint}/search,默认 https://api.tavily.com。
   - Body: { api_key, query, max_results, search_depth="basic" }。
   - 需要 API key,无 key 时返回空列表。

分发: CompositeWebSearchService 根据 WebSearchConfig.providerName 构造 delegate。支持名: "SearXNG"/"Tavily"/"Bing"(默认 "Bing")。运行时切换 provider 会重建 delegate(同步 synchronized)。

设置入口:
- 设置 → 模型与服务 → Web 搜索: 配置 provider/apiKey/endpoint。
- 设置 → 聊天 → 默认搜索引擎: 快速切换(auto/searxng/tavily/bing),映射到 CompositeWebSearchService。

web_fetch 实现(SkillExecutor.execWebFetch):
- 用 OkHttpClient(named("chat", 30s 超时))发 GET,User-Agent: Mozilla/5.0 (Android LLM client)。
- 抓取上限 20 万字符(take(200_000))。
- htmlToText: 先移除 script/style/noscript 块,再去所有 HTML 标签,最后 Html.fromHtml 处理实体(&amp; &lt; 等)。不用 Jsoup(避免 APK 体积增加)。
- 折叠连续空白,返回上限 5 万字符(take(50_000))。

HTTP 超时: SkillExecutor 用的 named("chat") client,connectTimeout=30s / readTimeout=120s / writeTimeout=30s。web search 专用 client(named("webSearch")) connectTimeout=8s / readTimeout=15s。

回答用户搜索实现类问题应基于上述真实实现,不要编造不存在的 provider 或参数。
