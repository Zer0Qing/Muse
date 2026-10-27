package io.zer0.muse.web

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 通用 HTTP 搜索引擎 —— 按用户填的模板发请求、按路径取结果。
 *
 * 设计目标是「不改代码就能接一个检索服务」，覆盖三类常见形态：
 *  1. GET 查询参数：`https://host/search?q=...`（自托管 SearXNG 一类）
 *  2. POST 固定 JSON 体：`{"query":"...","count":5}`（Tavily / Exa 一类）
 *  3. 结果包在对象里：`{"data":{"results":[...]}}`，用 [CustomSearchEngine.resultsPath] 指定
 *
 * 刻意不做的事：
 *  - 不猜认证头。只有 apiKey 非空时注入 `Authorization: Bearer`，其他方式用自建网关自己转。
 *  - 不做 HTML 解析。抓 HTML 结果页需要针对每个站点写选择器，那是内置 Provider 的事。
 */
internal class GenericSearchProvider(
    private val client: OkHttpClient,
    private val engine: CustomSearchEngine,
) : WebSearchService {
    override val name: String = engine.name.ifBlank { "Custom" }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf { requestOnce(query, maxResults) }
            .onError { msg, t -> Logger.w(TAG, "$name 搜索失败: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun requestOnce(query: String, maxResults: Int): List<WebSearchResult> {
        val endpoint = engine.endpoint.trim()
        if (endpoint.isEmpty()) {
            Logger.w(TAG, "$name 未配置 endpoint，跳过")
            return emptyList()
        }

        val isPost = engine.method.equals("POST", ignoreCase = true)
        val url =
            if (isPost) {
                endpoint
            } else {
                val separator = if (endpoint.contains('?')) "&" else "?"
                val param = engine.queryParam.ifBlank { "q" }
                "$endpoint$separator$param=${encode(query)}&count=$maxResults"
            }

        val bodyText = if (isPost) buildBody(query, maxResults) else null
        val builder =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .apply { if (engine.apiKey.isNotBlank()) header("Authorization", "Bearer ${engine.apiKey}") }

        val request =
            if (isPost) {
                builder
                    .header("Content-Type", "application/json")
                    .post((bodyText ?: "{}").toRequestBody(JSON_MEDIA))
                    .build()
            } else {
                builder.get().build()
            }

        return client.newCall(request).execute().use { response ->
            when {
                !response.isSuccessful -> {
                    Logger.w(TAG, "$name HTTP ${response.code}")
                    emptyList()
                }
                else -> {
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) emptyList() else parseResults(raw, maxResults)
                }
            }
        }
    }

    /** 把 {query} / {count} 替换进用户写的请求体模板。 */
    private fun buildBody(query: String, maxResults: Int): String {
        val template = engine.bodyTemplate.trim()
        if (template.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("count", maxResults)
            }.toString()
        }
        return template
            .replace("{query}", escapeJsonString(query))
            .replace("{count}", maxResults.toString())
    }

    /**
     * 按 resultsPath 取结果数组。
     *
     * resultsPath 支持点号分层（data.results），也支持数组下标（data.0.items）。
     * 留空时把根节点当数组处理。
     */
    private fun parseResults(raw: String, maxResults: Int): List<WebSearchResult> {
        val root = json.parseToJsonElement(raw)
        val array = locateArray(root) ?: run {
            Logger.w(TAG, "$name 响应里找不到结果数组（resultsPath='${engine.resultsPath}'）")
            return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.readString(engine.titlePath.ifBlank { "title" })
            val url = obj.readString(engine.urlPath.ifBlank { "url" })
            if (url.isBlank()) return@mapNotNull null
            WebSearchResult(
                title = title.ifBlank { url },
                url = url,
                snippet = obj.readString(engine.snippetPath.ifBlank { "content" }),
                source = name,
            )
        }.take(maxResults)
    }

    private fun locateArray(root: JsonElement): JsonArray? {
        val path = engine.resultsPath.trim()
        if (path.isEmpty()) return root as? JsonArray
        return descend(root, path.split('.'), 0) as? JsonArray
    }

    /** 沿点号路径逐层下降；任一层缺失返回 null。 */
    private fun descend(node: JsonElement, segments: List<String>, index: Int): JsonElement? {
        if (index >= segments.size) return node
        val next =
            when (node) {
                is JsonObject -> node[segments[index]]
                is JsonArray -> node.getOrNull(segments[index].toIntOrNull() ?: -1)
                else -> null
            }
        return next?.let { descend(it, segments, index + 1) }
    }

    private fun JsonObject.readString(key: String): String {
        val node = this[key] ?: return ""
        return when (node) {
            is JsonPrimitive -> node.contentOrNull.orEmpty()
            is JsonArray ->
                node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
            else -> ""
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** JSON 字符串转义：只处理必须转义的字符，避免破坏用户模板里的结构。 */
    private fun escapeJsonString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        private const val TAG = "GenericSearch"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Muse/1.0 (Android) SearchClient"
    }
}
