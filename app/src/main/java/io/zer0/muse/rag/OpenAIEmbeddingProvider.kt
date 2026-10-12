package io.zer0.muse.rag

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v1.54: 云端 Embedding Provider(OpenAI 兼容接口)。
 *
 * 复用 [ProviderConfig] 的 baseUrl + apiKey + embeddingsPath,调用 POST /embeddings。
 * 支持 OpenAI / 智谱 / 通义 / DeepSeek / 月之暗面 / 豆包等所有 OpenAI 兼容的 embedding API。
 *
 * 请求格式:
 * ```
 * POST {baseUrl}/embeddings
 * Authorization: Bearer {apiKey}
 * {"model": "...", "input": ["text1", "text2"]}
 * ```
 *
 * 响应格式(OpenAI 兼容):
 * ```json
 * {"data": [{"embedding": [0.01, ...], "index": 0}, ...]}
 * ```
 *
 * @param config Provider 配置(含 baseUrl + apiKey + embeddingsPath)
 * @param model embedding 模型名(空串时用 Provider 默认)
 * @param client 复用 Koin 注入的 OkHttpClient(named("chat"))
 */
class OpenAIEmbeddingProvider(
    private val config: ProviderConfig,
    private val model: String,
    private val client: OkHttpClient,
) : EmbeddingProvider {

    override val id: String = "cloud-${config.id}"
    override val displayName: String = "${config.displayName} (Cloud)"
    override val modelName: String = model.ifBlank { defaultModel() }

    /** 维度在首次 embed 后从响应动态获取,之前用 -1 占位。 */
    @Volatile
    private var cachedDimension: Int = -1

    /**
     * v2.6.0 (AUD-RAG-F3): 真实维度是否已实测。
     *
     * [dimension] 此前在未实测时返回 [guessDimension] 的猜测值,而 RagService 的
     * HNSW embedding 指纹包含该维度 —— 猜测值与真实值不一致时,指纹会先按猜测写入、
     * 首次 embed 后又变成真实维度,导致每次冷启动都判定指纹变化并重建索引。
     * 现在指纹侧改用 [stableDimensionOrNull]:未实测时返回 null,调用方以稳定占位
     * 参与指纹,避免猜测值污染指纹。
     */
    override val dimension: Int
        get() = if (cachedDimension > 0) cachedDimension else guessDimension(modelName)

    /** 已实测的真实维度;未实测返回 null。 */
    val measuredDimension: Int?
        get() = cachedDimension.takeIf { it > 0 }

    /**
     * 指纹用维度:已实测用真实值,未实测用稳定占位 "unknown"(而非猜测值)。
     * 未实测/已实测两种状态指纹不同,但各自稳定 —— 实测完成后指纹固定为真实维度,
     * 不再因猜测值跳变导致每次冷启动重建索引。
     */
    override val fingerprintDimension: String
        get() = measuredDimension?.toString() ?: "unknown"

    private val embeddingsPath: String by lazy {
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.OpenAI) {
            specific.embeddingsPath.ifBlank { "/embeddings" }
        } else {
            "/embeddings"
        }
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val url = config.resolvedBaseUrl().trimEnd('/') + embeddingsPath
        val body = buildJsonObject {
            put("model", modelName)
            put("input", JsonArray(texts.map { JsonPrimitive(it) }))
        }.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()

        coroutineContext.ensureActive()
        // v1.0.30: 加 10s 超时，防止 embedding API 挂了阻塞整个生成链路
        val response = kotlinx.coroutines.withTimeout(10_000L) {
            client.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(500) ?: ""
            throw RuntimeException("Embedding API failed ${response.code}: $errBody")
        }
        val raw = response.body?.string()
            ?: throw RuntimeException("Embedding API returned empty body")
        val json = AppJson.parseToJsonElement(raw).jsonObject
        val dataArray = json["data"]?.jsonArray
            ?: throw RuntimeException("Embedding API response missing data field: ${raw.take(200)}")
        // 按 index 排序确保顺序与输入一致
        val sorted = dataArray.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
        val result = sorted.map { item ->
            val embArray = item.jsonObject["embedding"]?.jsonArray
                ?: throw RuntimeException("Embedding response item missing embedding field")
            FloatArray(embArray.size) { i -> embArray[i].jsonPrimitive.floatOrNull ?: 0f }
        }
        if (result.isNotEmpty()) cachedDimension = result[0].size
        result
    }

    /** 根据 model 名猜测维度(首次调用前 UI 展示用)。 */
    private fun guessDimension(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("text-embedding-3-large") -> 3072
            m.contains("text-embedding-3-small") -> 1536
            m.contains("text-embedding-ada") -> 1536
            m.contains("embedding-2") -> 1024 // 智谱 embedding-2
            m.contains("text-embedding-v3") -> 1024 // 通义 text-embedding-v3
            m.contains("bge-large") -> 1024
            m.contains("bge-small") -> 512
            m.contains("minilm") -> 384
            else -> 1024 // 默认猜测
        }
    }

    /** 根据 Provider 类型返回默认 embedding 模型(cloudModel 为空时调用)。 */
    private fun defaultModel(): String = when (config.type) {
        io.zer0.ai.core.ProviderType.OPENAI, io.zer0.ai.core.ProviderType.OPENAI_RESPONSES -> "text-embedding-3-small"
        io.zer0.ai.core.ProviderType.ANTHROPIC ->
            throw IllegalStateException(
                "Anthropic does not provide an OpenAI-compatible embedding API. Please specify an embedding model in RAG settings or switch to an OpenAI-compatible Provider",
            )
        io.zer0.ai.core.ProviderType.GEMINI ->
            throw IllegalStateException(
                "Gemini's embedding API is not fully compatible with OpenAI's. Please specify an embedding model in RAG settings or switch to an OpenAI-compatible Provider",
            )
    }
}
