package io.zer0.muse.rag

import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P2-6（审计报告）: OpenAI 兼容的云端 rerank provider。
 *
 * 报告指出：rerank 完全没有云端方案，`LocalRerankProvider` 对中文基本空转
 * （`extractTerms` 把整句中文当一个 token 去 `contains`，几乎不可能命中，
 * 所有候选 score=0，UI 还会把「相似度」显示成 0.00）。
 *
 * 这里实现一套 **OpenAI 兼容的 `/rerank` 协议**，SiliconFlow / Jina / Cohere
 * 都能复用同一份实现，只需配 baseUrl + model + key：
 *
 * ```
 * POST {baseUrl}/rerank
 * {"model": "...", "query": "...", "documents": ["...","..."], "top_n": N}
 * →
 * {"results": [{"index": 0, "relevance_score": 0.93}, ...]}
 * ```
 *
 * 各家的差异靠 [responseShape] 容忍：Jina 用 `results[].relevance_score`，
 * Cohere 用 `results[].relevance_score`，部分实现用 `data[].score`。
 */
class RemoteRerankProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val httpPost: suspend (url: String, headers: Map<String, String>, body: String) -> String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : RerankProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun rerank(query: String, candidates: List<RerankCandidate>, topK: Int): List<RerankResult> {
        if (candidates.isEmpty()) return emptyList()
        if (query.isBlank()) {
            return candidates.take(topK).map {
                RerankResult(it.chunkId, it.docId, it.docTitle, it.content, it.chunkIndex, 0.5f)
            }
        }

        val url = baseUrl.trimEnd('/') + "/rerank"
        val requestBody = json.encodeToString(
            RerankRequest.serializer(),
            RerankRequest(
                model = model,
                query = query,
                documents = candidates.map { it.content },
                topN = topK,
            ),
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }

        val raw = runCatching { httpPost(url, headers, requestBody) }
            .onFailure { Logger.w(TAG, "云端 rerank 请求失败，降级本地重排: ${it.message}") }
            .getOrNull() ?: return LocalRerankProvider().rerank(query, candidates, topK)

        val parsed = runCatching { parseResponse(raw) }
            .onFailure { Logger.w(TAG, "云端 rerank 响应解析失败，降级本地重排: ${it.message}") }
            .getOrNull()
        if (parsed.isNullOrEmpty()) {
            Logger.w(TAG, "云端 rerank 返回空结果，降级本地重排")
            return LocalRerankProvider().rerank(query, candidates, topK)
        }

        // 按返回的 index 映射回候选；越界的 index 直接忽略（不同实现偶有 1-based）
        val mapped = parsed.mapNotNull { item ->
            val idx = item.index
            val candidate = candidates.getOrNull(idx)
                ?: candidates.getOrNull(idx - 1)
                ?: return@mapNotNull null
            RerankResult(
                chunkId = candidate.chunkId,
                docId = candidate.docId,
                docTitle = candidate.docTitle,
                content = candidate.content,
                chunkIndex = candidate.chunkIndex,
                score = item.score.coerceIn(0f, 1f),
            )
        }
        return if (mapped.isEmpty()) {
            LocalRerankProvider().rerank(query, candidates, topK)
        } else {
            mapped.sortedByDescending { it.score }.take(topK)
        }
    }

    /** 容忍多种响应结构：results[] / data[]，relevance_score / score。 */
    private fun parseResponse(raw: String): List<ScoredIndex> {
        val root = json.parseToJsonElement(raw)
        return runCatching {
            json.decodeFromJsonElement(RerankResponse.serializer(), root).let { resp ->
                val items = resp.results ?: resp.data ?: emptyList()
                items.mapNotNull { item ->
                    val score = item.relevanceScore ?: item.score ?: return@mapNotNull null
                    ScoredIndex(item.index, score.toFloat())
                }
            }
        }.getOrElse {
            // 宽松兜底：直接扫 results/data 数组里的 index + 任意分数字段
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            val arr = (obj["results"] ?: obj["data"]) as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val idx = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                val sc = (o["relevance_score"] ?: o["score"] ?: o["relevance"]) as? kotlinx.serialization.json.JsonPrimitive
                    ?: return@mapNotNull null
                ScoredIndex(idx, sc.content.toFloatOrNull() ?: return@mapNotNull null)
            }
        }
    }

    private data class ScoredIndex(val index: Int, val score: Float)

    companion object {
        private const val TAG = "RemoteRerank"
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

@Serializable
private data class RerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
    @SerialName("top_n") val topN: Int,
)

@Serializable
private data class RerankResponse(
    val results: List<RerankItem>? = null,
    val data: List<RerankItem>? = null,
)

@Serializable
private data class RerankItem(
    val index: Int = 0,
    @SerialName("relevance_score") val relevanceScore: Double? = null,
    val score: Double? = null,
)
