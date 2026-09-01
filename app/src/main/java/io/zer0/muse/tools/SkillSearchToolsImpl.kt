package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.web.SearchRateLimitException
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.WebSearchRequest
import io.zer0.muse.web.WebSearchPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * P1-3b 拆域：Skill 搜索/HTTP 工具实现（从 SkillExecutor.kt 迁移）。
 * 由 SkillExecutor 委托调用。
 */
class SkillSearchToolsImpl(
    private val context: Context,
    private val client: OkHttpClient,
    private val webSearchService: WebSearchService?,
    private val knowledgeDocDao: KnowledgeDocDao?,
    private val ragService: RagService?,
    private val ragConfigProvider: suspend () -> RagConfig = { RagConfig() },
    private val webSearchCoordinator: io.zer0.muse.web.WebSearchCoordinator? = null,
    private val webSearchPolicyProvider: suspend () -> WebSearchPolicy = { WebSearchPolicy() },
) {

    fun validatePublicUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // 解析 DNS 后二次校验 IP(防 DNS rebinding):只要任一解析结果指向内网就拒绝
        val addresses = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.all { addr ->
            // IPv4 私网/回环/链路本地等由 InetAddress 内置方法覆盖
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 私网 fc00::/7(InetAddress.isSiteLocalAddress 对 IPv6 返回 false,需手动判断)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 的前 7 位是 1111110,即首字节范围 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /**
     * A-07: 带 SSRF 逐跳防护的 OkHttp 执行。关闭自动跟随重定向(client 默认 followRedirects=true),
     * 手动跟随 30x,每一跳都重新过 [validatePublicUrl] — 防止初始公网 URL 30x 重定向到
     * 127.0.0.1 / 169.254.169.254 等内网地址(DNS rebinding / open-redirect 组合攻击)。
     * 约束:跳数上限 MAX_REDIRECTS(与 FileTools.parse_link 相同),防止无限重定向环。
     *
     * @param request 已构造但未执行的请求
     * @param clientBuilder 基于注入 chat client 的 newBuilder(用于覆盖超时等)
     * @return 最终命中的响应;访问方负责 use/close
     */
    private fun executeWithHopGuard(
        request: Request.Builder,
        clientBuilder: OkHttpClient.Builder,
    ): okhttp3.Response {
        var redirects = 0
        var redirectReq = request
        while (true) {
            val hopUrl = redirectReq.build().url
            // 逐跳校验:任一跳指向内网即拒绝(含最初的 URL)
            val hopStr = hopUrl.toString()
            if (!validatePublicUrl(hopStr)) {
                // 抛业务异常,由调用方的 IOException 分支统一兜底(带上跳信息)
                throw java.io.IOException("重定向目标指向内网地址,已拒绝: $hopStr")
            }
            // 每个 hop 都新建 followRedirects=false 的 client(复用共享 client 的连接配置与超时覆盖)
            val hopClient = clientBuilder.followRedirects(false).build()
            val resp = hopClient.newCall(redirectReq.build()).execute()
            // location 为局部 val,下方 if 早退后 Kotlin 可智能转为非空
            val location = resp.header("Location")
            if (resp.code !in 300..399 || location == null) {
                return resp // 非重定向(或重定向缺 Location):直接返回最终响应(即使是非 2xx)
            }
            resp.close() // 手动跟随,关闭本次重定向响应(不读 body,节约连接)
            redirects++
            if (redirects > MAX_REDIRECTS) {
                throw java.io.IOException("重定向次数超过上限($MAX_REDIRECTS),已终止")
            }
            // 相对 Location 按当前 URL 解析
            val next = hopUrl.resolve(location)
                ?: throw java.io.IOException("重定向 Location 无法解析: $location")
            val nextStr = next.toString()
            // 保留原方法/body/header,仅替换 URL(与 OkHttp 默认重定向语义一致:
            // 307/308 保留 body,301/302/303 由接受 GET 的服务端兼容处理)
            val prev = redirectReq.build()
            redirectReq = Request.Builder()
                .url(nextStr)
                .method(prev.method, prev.body)
                .apply { prev.headers.forEach { (k, v) -> header(k, v) } }
        }
    }

    private companion object {
        /** 手动跟随重定向的最大跳数(防无限重定向环)。 */
        const val MAX_REDIRECTS = 10
    }

    /** HTTP GET 请求。失败时(404/超时/连接失败)降级到搜索摘要;401/403 等业务错误不降级。 */
    suspend fun execHttpGet(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:初始 URL 校验由 executeWithHopGuard 的逐跳校验覆盖(首跳即校验)
        // timeout: 默认 30 秒;max_size: 默认 1MB,限制响应体大小
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val maxSize = args["max_size"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1_048_576
        val req = Request.Builder().url(url).get()
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout 与 followRedirects=false(逐跳 SSRF 校验,见 A-07)
        val timeoutClient = client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                val body = resp.body.string()
                if (resp.isSuccessful) {
                    "HTTP ${resp.code}\n${body.take(maxSize)}"
                } else {
                    // 降级条件:仅 404(资源不存在,搜索可能有相关摘要);401/403 等业务错误不降级
                    if (resp.code == 404) {
                        val degraded = degradeToSearchSummary(url, resp.code)
                        if (degraded != null) return@use degraded
                    }
                    // HTTP 错误响应:返回状态码 + body 前 200 字
                    "HTTP ${resp.code}: ${body.take(200)}"
                }
            }
        } catch (e: java.io.IOException) {
            // 超时/连接失败/逐跳 SSRF 拒绝均降级到搜索摘要;若降级不可用则返回原错误
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            context.getString(R.string.skill_connect_failed, e.message ?: "")
        }
    }

    /** HTTP POST 请求。 */
    fun execHttpPost(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:初始 URL 校验由 executeWithHopGuard 的逐跳校验覆盖(首跳即校验)
        val body = args["body"] ?: ""
        // v1.52: 默认 Content-Type 带 charset=utf-8,避免中文 body 乱码
        val rawContentType = args["content_type"] ?: "application/json"
        val contentType = if (rawContentType.contains("charset", ignoreCase = true)) {
            rawContentType
        } else {
            "$rawContentType; charset=utf-8"
        }
        // timeout: 默认 30 秒
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout(followRedirects=false 由 executeWithHopGuard 设置,见 A-07)
        val timeoutClient = client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                val respBody = resp.body.string().take(1_000_000)
                return "HTTP ${resp.code}\n$respBody"
            }
        } catch (e: java.io.IOException) {
            // 连接失败或逐跳 SSRF 拒绝
            "HTTP 请求失败: ${e.message ?: "网络异常"}"
        }
    }

    /** 解析沙盒路径(限定 filesDir / cacheDir 下,防止路径穿越)。 */

    // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
    fun applyHeaders(req: Request.Builder, headersJson: String) {
        resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), headersJson)
            obj.forEach { (k, v) ->
                (v as? JsonPrimitive)?.content?.let { req.header(k, it) }
            }
        }.onError { msg, _ ->
            Logger.w("SkillExecutor", "applyHeaders 解析失败: $msg(原始: $headersJson)")
        }
    }

    // ── v0.24: 搜索与信息获取 ──────────────────────────────────────────────

    /**
     * web_search — 用配置好的 WebSearchService(SearXNG/Tavily)搜索。
     * 让 LLM 主动决定何时搜索,而非每次对话都注入。
     */
    suspend fun execWebSearch(args: Map<String, String>, turnKey: String = "default"): String {
        val service = webSearchService
            ?: return context.getString(R.string.skill_web_search_not_configured)
        // v1.0.81: 对齐 Hana web-search，先清理 LLM 偶发生成的畸形引号/不可见字符。
        val rawQuery = args["query"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return context.getString(R.string.skill_missing_param_query)
        val query = io.zer0.muse.web.WebSearchQueryNormalizer.normalize(rawQuery)
            .takeIf { it.isNotEmpty() }
            ?: return context.getString(R.string.skill_missing_param_query)
        if (rawQuery != query) {
            Logger.i("SkillExecutor", "web_search query normalized: '$rawQuery' -> '$query'")
        }
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val searchPolicy = webSearchPolicyProvider()
        val response = webSearchCoordinator?.search(
            WebSearchRequest(query = query, maxResults = searchPolicy.maxResults, dateRange = args["date_range"]),
            turnKey = turnKey,
            policy = searchPolicy,
        )
        if (response != null) {
            return formatCoordinatedSearchResponse(response, maxResults)
        }
        // 兼容测试环境/旧注入链：没有 coordinator 时直接调用 service。
        // date_range / time_period: 时间范围(可选,二选一,time_period 兼容同义)
        val dateRange = args["date_range"]?.takeIf { it.isNotBlank() }
        val timePeriod = args["time_period"]?.takeIf { it.isNotBlank() }
        val options = buildMap<String, String> {
            dateRange?.let { put("date_range", it) }
            timePeriod?.let { put("time_period", it) }
        }
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
        val results = resultOf {
            service.searchWithOptions(query, maxResults, options)
        }.onError { msg, t ->
            // search-rate-limiter: 被限速时返回友好提示给 LLM，避免反复撞 429/402
            if (t is SearchRateLimitException) {
                val secs = (t.retryAfterMs / 1000).coerceAtLeast(1)
                return context.getString(R.string.skill_search_no_result, query) +
                    "\n（搜索服务被限速，请 ${secs} 秒后重试）"
            }
            Logger.w("SkillExecutor", "web_search 失败: $msg")
        }.getOrNull() ?: emptyList()
        if (results.isEmpty()) return context.getString(R.string.skill_search_no_result, query)
        val sb = StringBuilder(context.getString(R.string.skill_search_result_header, query, maxResults))
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title}")
            sb.appendLine("    URL: ${r.url}")
            sb.appendLine("    摘要: ${r.snippet}")
        }
        // 结果正文只保留搜索事实；回答策略属于模型内部系统提示，不混入用户可见的工具结果。
        return sb.toString().trimEnd()
    }

    private fun formatCoordinatedSearchResponse(
        response: io.zer0.muse.web.WebSearchResponse,
        maxResults: Int,
    ): String {
        if (response.status != io.zer0.muse.web.WebSearchStatus.RESULTS || response.results.isEmpty()) {
            return when (response.status) {
                io.zer0.muse.web.WebSearchStatus.BUDGET_EXCEEDED -> "搜索预算已用尽：本轮最多搜索 ${WebSearchPolicy().maxSearchesPerTurn} 次，请基于已有结果回答。"
                io.zer0.muse.web.WebSearchStatus.DUPLICATE_QUERY -> "这个搜索词本轮已经搜过了，请不要重复搜索。"
                io.zer0.muse.web.WebSearchStatus.RATE_LIMITED -> "搜索服务被限速了，请稍后再试。"
                io.zer0.muse.web.WebSearchStatus.FAILED -> "搜索服务失败：${response.attempts.lastOrNull()?.message ?: "网络异常"}"
                else -> "搜索“${response.normalizedQuery}”没有找到结果。"
            }
        }
        val sb = StringBuilder(context.getString(R.string.skill_search_result_header, response.normalizedQuery, response.results.size))
        response.results.take(maxResults).forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title}")
            sb.appendLine("    URL: ${r.url}")
            sb.appendLine("    摘要: ${r.snippet}")
        }
        sb.appendLine("\n搜索来源：${response.provider ?: "未知"}；本轮状态：${response.status}")
        return sb.toString().trimEnd()
    }

    /**
     * web_fetch — 抓取指定 URL 的网页正文(用 Jsoup 解析 HTML,移除噪声元素后取 body 纯文本)。
     * HTTP 失败或非 2xx 时降级:用 webSearchService 搜索该 URL 域名,返回前 3 条结果摘要。
     */
    suspend fun execWebFetch(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:初始 URL 校验由 executeWithHopGuard 的逐跳校验覆盖(首跳即校验)
        // max_length: 字符数上限,默认 50000;truncate: 默认 true,超出截断
        val maxLength = args["max_length"]?.toIntOrNull()?.coerceAtLeast(1) ?: 50_000
        val truncate = args["truncate"]?.toBoolean() ?: true
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "Mozilla/5.0 (Android LLM client)")
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout(约束:HTTP 请求 30 秒超时);followRedirects=false 由逐跳 guard 设置
        val timeoutClient = client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                if (!resp.isSuccessful) {
                    // 降级:搜索该站点域名,返回前 3 条结果摘要
                    val degraded = degradeToSearchSummary(url, resp.code)
                    if (degraded != null) return@use degraded
                    return@use "HTTP ${resp.code}"
                }
                val html = resp.body.string().take(200_000) // 上限 20 万字符
                // 用 Jsoup 解析,移除噪声元素后取 body 纯文本
                val doc = Jsoup.parse(html)
                doc.select("script, style, noscript, nav, footer, header, aside").remove()
                val bodyEl = doc.body()
                val text = bodyEl.text()
                // 折叠连续空白
                val cleaned = text.replace(Regex("\\s{3,}"), "\n\n").trim()
                val finalText = if (truncate) cleaned.take(maxLength) else cleaned
                "HTTP ${resp.code}\n$finalText"
            }
        } catch (e: java.io.IOException) {
            // 网络异常(超时/连接失败/逐跳 SSRF 拒绝)降级到搜索摘要
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            throw e
        }
    }

    /**
     * 网页抓取/请求失败降级:用 webSearchService 搜索 URL 的域名,返回前 3 条结果摘要。
     *
     * @param url 原始请求 URL(用于提取域名作为搜索词)
     * @param httpCode HTTP 状态码(>0 表示收到响应,<0 表示网络异常)
     * @param errorMsg 网络异常时的错误信息(httpCode < 0 时使用)
     * @return 降级摘要文本;若搜索服务未配置、搜索失败或无结果,返回 null(由调用方返回原错误)
     */
    suspend fun degradeToSearchSummary(
        url: String,
        httpCode: Int,
        errorMsg: String = "",
    ): String? {
        val service = webSearchService ?: return null
        val domain = resultOf { java.net.URI(url).host }
            .onError { msg, _ -> Logger.w("SkillExecutor", "降级搜索域名解析失败: $msg") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val results = resultOf { service.search(domain, 3) }
            .onError { msg, t ->
                // search-rate-limiter: 降级搜索被限速时仅记录日志，返回 null 让调用方展示原抓取错误
                if (t is SearchRateLimitException) {
                    Logger.w("SkillExecutor", "降级搜索被限速: ${t.retryAfterMs}ms")
                } else {
                    Logger.w("SkillExecutor", "降级搜索失败: $msg")
                }
            }
            .getOrNull() ?: return null
        if (results.isEmpty()) return null
        val fallbackMsg = if (httpCode > 0) {
            "网页抓取失败(HTTP $httpCode),以下是该站点相关搜索摘要:"
        } else {
            "网页抓取失败: ${errorMsg.ifBlank { "网络异常" }},以下是该站点相关搜索摘要:"
        }
        val sb = StringBuilder(fallbackMsg)
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title} - ${r.url}")
            sb.appendLine("    ${r.snippet}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * knowledge_search — 在用户知识库中全文搜索(标题 + 内容)。
     * 让 LLM 主动查知识库,而非依赖用户手动 @。
     *
     * v1.97: 新增 include_internal 参数控制是否返回内部开发文档(devdoc)。
     * 默认 false,仅搜索用户自建文档;用户问 muse app 功能时 LLM 应传 true。
     * 修复"内部 devdoc 被暴露给普通查询"的问题。
     */
    suspend fun execKnowledgeSearch(args: Map<String, String>): String {
        val dao = knowledgeDocDao ?: return context.getString(R.string.skill_knowledge_not_configured)
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        if (query.isBlank()) return context.getString(R.string.skill_query_blank)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 50) ?: 5
        val threshold = args["threshold"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.3f
        // v1.97: include_internal — 是否包含内部开发文档(devdoc),默认 false
        val includeInternal = args["include_internal"]?.toBoolean() ?: false

        // v1.54: 优先用向量检索(语义匹配),无索引时降级到 LIKE 子串匹配
        val rs = ragService
        if (rs != null) {
            // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
            val ragConfig = resultOf { ragConfigProvider() }
                .onError { msg, _ -> Logger.w("SkillExecutor", "ragConfigProvider 失败: $msg") }
                .getOrNull() ?: io.zer0.muse.rag.RagConfig()
            val vectorResults = resultOf {
                rs.retrieve(query, topK, threshold, ragConfig)
            }.onError { msg, _ ->
                Logger.w("SkillExecutor", "向量检索失败,降级到 LIKE: $msg")
            }.getOrNull()
            if (vectorResults != null) {
                // v1.97: 过滤内部 devdoc(include_internal=false 时排除内部文档)
                // v1.133: 已统一用 SearchResult.isInternal 字段判断(RagService.retrieve 回填,
                // 数据源为 KnowledgeDocEntity.isInternal)。替代原 `docId.startsWith("devdoc-")` 硬编码,
                // 避免内部文档 id 命名变更后过滤失效。
                val filtered = if (includeInternal) {
                    vectorResults
                } else {
                    vectorResults.filterNot { it.isInternal }
                }
                if (filtered.isNotEmpty()) {
                    val sb = StringBuilder(context.getString(R.string.skill_knowledge_vector_header, query, filtered.size, threshold.toString(), topK))
                    filtered.forEachIndexed { idx, r ->
                        sb.appendLine("[${idx + 1}] 来源: ${r.docTitle} (相似度 ${"%.2f".format(r.score)})")
                        sb.appendLine("    片段: ${r.chunkContent.take(300)}")
                    }
                    return sb.toString().trimEnd()
                }
            }
            // 向量检索无结果,继续尝试 LIKE 搜索(兼容未索引的旧文档)
        }

        // 降级:LIKE 子串匹配(旧文档未分块索引)
        // M-KB1: 转义 LIKE 通配符(% _ \),配合 DAO 的 ESCAPE '\' 子句
        val allResults = dao.search(io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(query)).first()
        // v1.97: 过滤内部 devdoc(include_internal=false 时排除内部文档)
        // v1.133: 改用 isInternal 字段(与 MIGRATION_38_39 标记一致,替代原 fileType="devdoc" 硬编码)
        val visibleResults = if (includeInternal) {
            allResults
        } else {
            allResults.filterNot { it.isInternal }
        }
        val scored = visibleResults.map { doc ->
            // v1.97: 改进评分 — 标题完全匹配=1.0,标题包含=0.8,内容多次命中提升分数
            val titleMatch = doc.title.contains(query, ignoreCase = true)
            val contentMatches = doc.content.split(query, ignoreCase = true).size - 1
            val score = when {
                doc.title.equals(query, ignoreCase = true) -> 1.0f
                titleMatch -> 0.8f
                contentMatches > 0 -> (0.4f + minOf(contentMatches * 0.1f, 0.3f)).coerceAtMost(0.7f)
                else -> 0.0f
            }
            doc to score
        }.filter { it.second >= threshold }
        if (scored.isEmpty()) return context.getString(R.string.skill_knowledge_no_match, query, threshold.toString())
        val results = scored.sortedByDescending { it.second }.take(topK)
        val sb = StringBuilder(context.getString(R.string.skill_knowledge_like_header, query, results.size, threshold.toString(), topK))
        results.forEachIndexed { idx, (doc, score) ->
            sb.appendLine("[${idx + 1}] ${doc.title} (${doc.fileType}, ${doc.content.length} 字, score=$score)")
            val matchIdx = doc.content.indexOf(query, ignoreCase = true)
            val snippet = if (matchIdx < 0) {
                doc.content.take(200)
            } else {
                val start = (matchIdx - 80).coerceAtLeast(0)
                val end = (matchIdx + query.length + 120).coerceAtMost(doc.content.length)
                "..." + doc.content.substring(start, end) + "..."
            }
            sb.appendLine("    片段: $snippet")
        }
        return sb.toString().trimEnd()
    }

    /**
     * arxiv_search — arXiv 学术论文搜索。
     * 用 http://export.arxiv.org/api/query?search_query=all:查询&max_results=N
     * 返回 Atom XML,正则解析 entry/title/summary/link。
     */
    fun execArxivSearch(args: Map<String, String>): String {
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // category: 学科分类(如 cs.AI/cs.CL);date_from/date_to: 日期范围(YYYY-MM-DD)
        // M-SE10: category 用 URLEncoder 编码;date_from/date_to 校验 YYYY-MM-DD 格式
        val category = args["category"]?.takeIf { it.isNotBlank() }
            ?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val dateFrom = args["date_from"]?.takeIf { it.isNotBlank() }
            ?.let { if (dateRegex.matches(it)) it else return "date_from 格式错误,应为 YYYY-MM-DD: $it" }
        val dateTo = args["date_to"]?.takeIf { it.isNotBlank() }
            ?.let { if (dateRegex.matches(it)) it else return "date_to 格式错误,应为 YYYY-MM-DD: $it" }
        val searchQuery = buildString {
            append("all:").append(encoded)
            category?.let { append("+AND+cat:").append(it) }
            if (dateFrom != null || dateTo != null) {
                val from = dateFrom?.replace("-", "")?.let { it + "0000" } ?: "000000000000"
                val to = dateTo?.replace("-", "")?.let { it + "2359" } ?: "999912312359"
                // submittedDate:[YYYYMMDD0000 TO YYYYMMDD2359](方括号预编码为 %5B/%5D)
                append("+AND+submittedDate:%5B").append(from).append("+TO+").append(to).append("%5D")
            }
        }
        // v1.71: 使用 HTTPS,避免明文传输的中间人风险
        // v1.109 修复: 显式指定 sortBy=relevance,避免复合查询被日期序覆盖
        val url = "https://export.arxiv.org/api/query?search_query=$searchQuery&max_results=$maxResults&sortBy=relevance&sortOrder=descending"
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "muse/1.0 (Android LLM client)")
        // 复用连接池,仅覆盖 callTimeout(约束:HTTP 请求 30 秒超时)
        val timeoutClient = client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        timeoutClient.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) return context.getString(R.string.skill_arxiv_search_failed, resp.code)
            val xml = resp.body.string()
            // Atom XML entry 块:<entry>...<title>标题</title><summary>摘要</summary><link href="URL"/>...</entry>
            val entries = Regex(
                pattern = """<entry>([\s\S]*?)</entry>""",
                options = setOf(RegexOption.IGNORE_CASE),
            ).findAll(xml).take(maxResults).toList()
            if (entries.isEmpty()) return context.getString(R.string.skill_arxiv_no_result, query)
            val sb = StringBuilder(context.getString(R.string.skill_arxiv_result_header, query, entries.size))
            entries.forEachIndexed { idx, e ->
                val block = e.groupValues[1]
                val title = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                    ?: "(无标题)"
                val summary = Regex("""<summary>([\s\S]*?)</summary>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                    ?.take(300) ?: "(无摘要)"
                val link = Regex("""<link[^>]*href="([^"]+)"[^>]*/>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1) ?: "(无链接)"
                val published = Regex("""<published>([^<]+)</published>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.substringBefore("T") ?: ""
                sb.appendLine("[${idx + 1}] $title${if (published.isNotBlank()) " ($published)" else ""}")
                sb.appendLine("    URL: $link")
                sb.appendLine("    摘要: $summary")
            }
            return sb.toString().trimEnd()
        }
    }

    /**
     * install_skill — LLM 自己生成 skill 定义并入库(Phase 2 自我扩展)。
     *
     * LLM 输出 .skill.json 格式的 skill 定义字符串,经 [SkillImporter.parse] 校验后入库。
     * 安全约束:implementationKotlin 必须是 4 个内置实现之一,不支持任意代码执行。
     */
}
