<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 联网搜索指南 完整版

> 触发场景: 用户问"联网搜索怎么开""怎么配置搜索引擎""搜索结果不准""搜索超时""web_search 和 web_fetch 什么区别""Bing/Tavily/SearXNG 怎么选""要不要 API key"时,参考本文档据实回答。
> 本文档基于源码 WebSearchService.kt / SkillExecutor.kt 的真实实现,禁止编造不存在的 provider 或参数。

## 一、概述

Muse 的联网搜索由两个工具承载:
- **web_search**: 关键词搜索,返回标题 + URL + 摘要列表(给 LLM 挑选)
- **web_fetch**: 抓取指定 URL 的网页正文(读全文)

搜索由三种 provider 实现,按用户配置切换:Bing(默认,免 key)/ SearXNG(自托管,免 key)/ Tavily(需 API key)。

在聊天中输入栏"+"号菜单开启"联网搜索"后,LLM 自主决定何时调用这两个工具;不开这个开关,LLM 也可以按需调用(工具始终在 schema 里),开关只是让模型"更倾向搜索"的提示。

## 二、三种 Provider 详解

### 1. BingProvider(默认)

| 项 | 值 |
|---|---|
| URL | `https://cn.bing.com/search?q={编码后的query}&count={maxResults*2}` |
| 实现 | HTML 抓取 + 正则解析 |
| API key | 不需要 |
| 解析 | 匹配 `<li class="b_algo">` 块;块内 `<h2><a href="URL">title</a></h2>` 取标题和链接;摘要优先 `<p class="b_lineclamp...">`,回退 `<div class="b_caption">` 内的 `<p>` |
| User-Agent | `Mozilla/5.0 (Linux; Android 12) muse/1.0` |
| 特点 | 零配置开箱即用;解析依赖 Bing 页面结构,页面改版可能失效(此时回退空结果,不会崩溃) |

### 2. SearXNGProvider(自托管)

| 项 | 值 |
|---|---|
| URL | `{endpoint}/search?q={query}&format=json&categories=general&language=zh-CN` |
| 默认 endpoint | `https://searx.be`(公共实例,可能不稳定) |
| 实现 | JSON 解析 `results` 数组,取 `title` / `url` / `content` |
| API key | 不需要(自托管实例;公共实例可能限流) |
| 配置 | 设置 → 模型与服务 → Web 搜索,填自建实例 endpoint |
| 特点 | 隐私友好、可自控;需自己部署实例才稳定 |

### 3. TavilyProvider(API)

| 项 | 值 |
|---|---|
| URL | `POST {endpoint}/search`,默认 `https://api.tavily.com` |
| Body | `{ api_key, query, max_results, search_depth="basic" }` |
| API key | **必需**,无 key 返回空列表 |
| 配置 | 设置 → 模型与服务 → Web 搜索,填 API key |
| 特点 | 结果质量高、稳定;但需要去 tavily.com 申请 key(免费额度) |

## 三、Provider 切换与配置

### 配置入口
1. **设置 → 模型与服务 → Web 搜索**: 配置 provider / apiKey / endpoint(高级配置)
2. **设置 → 聊天 → 默认搜索引擎**: 快速切换 auto / searxng / tavily / bing

### 分发机制
- `CompositeWebSearchService` 根据 `WebSearchConfig.providerName` 构造对应 delegate
- 支持名: `"SearXNG"` / `"Tavily"` / `"Bing"`(默认 `"Bing"`)
- 运行时切换 provider 会重建 delegate(synchronized 同步,切换即时生效)

## 四、web_fetch 实现细节(抓网页正文)

- 用 OkHttpClient(named("chat")) 发 GET,User-Agent: `Mozilla/5.0 (Android LLM client)`
- **抓取上限 20 万字符**(take(200_000))
- **返回上限 5 万字符**(take(50_000))
- htmlToText 处理链: 先移除 script/style/noscript 块 → 再去所有 HTML 标签 → 最后 Html.fromHtml 处理实体(&amp; &lt; 等)
- 不用 Jsoup(避免 APK 体积增加)
- 折叠连续空白

## 五、超时与网络参数

| 客户端 | connectTimeout | readTimeout | writeTimeout | 用途 |
|---|---|---|---|---|
| named("chat") | 30s | 120s | 30s | web_fetch / 一般 LLM 请求 |
| named("webSearch") | 8s | 15s | - | web_search 专用 |

web_search 用 8s 连接 + 15s 读,搜索慢的公共实例容易超时(表现为返回空结果),此时建议换 provider 或自建 SearXNG。

## 六、用户常见问题 Q&A

1. **"怎么开联网搜索"**: 聊天输入栏 + 号菜单 → 开启"联网搜索"。之后问需要实时信息的问题(新闻/价格/政策/版本)时,LLM 会自动调用 web_search。
2. **"搜索结果不准/搜不到"**: ①确认用的是 Bing(默认)且网络正常;②Bing 页面改版可能影响解析,等更新;③可切换到 Tavily(需 key)或自建 SearXNG 更稳定;④搜索词尽量具体(中文关键词,避免口语整句)。
3. **"要不要 API key"**: Bing 和自托管 SearXNG 不需要;Tavily 需要。
4. **"web_search 和 web_fetch 什么区别"**: web_search 返回标题/URL/摘要列表(定位用);web_fetch 抓指定 URL 全文(精读用)。正确姿势是"先搜后读":web_search 找到候选 URL → web_fetch 读内容。
5. **"搜索结果是不是实时"**: 是,每次调用都是实时请求搜索引擎,不是缓存。
6. **"搜索会不会泄露我的隐私"**: 搜索请求走系统网络配置;Bing 查询会发给微软,介意隐私可用自托管 SearXNG。
7. **"为什么搜出来英文结果"**: Bing 走 cn 域名,大部分中文 query 有中文结果;SearXNG 默认 language=zh-CN。Tavily 结果偏英文为主。
8. **"搜索超时怎么办"**: 检查网络/代理;公共 SearXNG 实例不稳定建议换;Tavily 确认 key 有效且额度未超。

## 七、LLM 调用要点

- **何时用 web_search**: 用户问实时信息(新闻/价格/政策/天气/最新版本/人物近况)、需要外部验证的事实、用户明确要求"查一下/搜一下"时。
- **何时用 web_fetch**: web_search 返回的候选 URL 中,标题/摘要不足以回答时;用户给了具体网址要求读内容时。
- **参数**: query 用精炼关键词(不是整句口语);max_results 默认 5,最多 10。
- **边界**: 单次搜索最多返回 10 条;网页正文超 5 万字符截断;抓取失败(超时/404/反爬)时如实说明,不要编造内容。
- **先后顺序**: 先 web_search 定位 → 再 web_fetch 精读,不要跳过搜索直接猜 URL。
- **失败降级**: 搜索返回空/超时,不要编造结果;告诉用户"当前搜索渠道不可用",建议检查网络或换 provider。

## 八、故障排查表

| 症状 | 可能原因 | 排查 |
|---|---|---|
| web_search 一直空结果 | Bing 解析失效 / 网络不通 / 代理拦截 | 开 debug 日志看 WebSearchService;试切换 provider |
| 切换 provider 不生效 | 配置保存失败 | 确认设置页保存成功;CompositeWebSearchService 是运行时重建,无需重启 |
| web_fetch 抓不到正文 | 目标站反爬 / JS 渲染(内容动态加载) | 静态 HTML 可抓;JS 渲染页面抓不到是预期;告知用户 |
| Tavily 空结果 | key 无效 / 额度超 / endpoint 填错 | 检查 key;Tavily 控制台看用量 |
| 搜索慢 | 公共 SearXNG 实例拥塞 | 自建实例;或换 Bing(默认,较快) |
