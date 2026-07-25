package io.zer0.muse.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp

/**
 * v1.134: 本地 ONNX Cross-Encoder Rerank Provider。
 *
 * 用 ONNX 运行时执行 cross-encoder 重排序(bge-reranker-base / ms-marco-MiniLM 等),
 * 输入 `[CLS] query [SEP] candidate [SEP]`,输出相关性 logit,经 sigmoid 归一化为 0-1 分数。
 *
 * 模型文件约定:
 *  - [modelPath]:绝对路径或 filesDir 相对路径,指向 `.onnx` rerank 模型
 *  - [vocabPath]:BERT 风格 vocab.txt(空串=自动推断)
 *  - 模型未配置 → [rerank] 抛 [IllegalStateException],
 *    [RagService] 会捕获并降级到 [LocalRerankProvider](维持原序)
 *
 * 推荐模型(用户自行下载到 filesDir/muse_onnx/):
 *  - `bge-reranker-base`(768 维,中英文,~1.1GB,质量最好)
 *  - `bge-reranker-small`(可选,体积更小)
 *  - `ms-marco-MiniLM-L-12-v2`(英文,~420MB)
 *
 * @param modelPath ONNX rerank 模型路径
 * @param vocabPath BERT vocab.txt 路径(空串=自动推断)
 * @param seqLen 最大序列长度(默认 512,query+candidate 截断)
 */
class OnnxRerankProvider(
    private val modelPath: String,
    private val vocabPath: String = "",
    private val seqLen: Int = MAX_SEQ_LEN,
) : RerankProvider {

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var vocab: Map<String, Int>? = null
    @Volatile private var cachedInputNames: List<String>? = null
    @Volatile private var cachedOutputNames: List<String>? = null
    private val mutex = Mutex()

    override suspend fun rerank(
        query: String,
        candidates: List<RerankCandidate>,
        topK: Int,
    ): List<RerankResult> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext emptyList()

        if (modelPath.isBlank() || !File(modelPath).exists()) {
            throw IllegalStateException(
                "ONNX rerank model not found: '$modelPath'. " +
                    "Please place a rerank .onnx file at filesDir/muse_onnx/rerank.onnx, " +
                    "or switch rerankProvider to '' (local heuristic) / 'cohere' / 'jina'.",
            )
        }
        ensureInitialized()
        val activeSession = session ?: throw IllegalStateException("ONNX rerank session init failed")
        val activeVocab = vocab ?: throw IllegalStateException("ONNX rerank vocab load failed")
        val activeEnv = env ?: throw IllegalStateException("ONNX rerank env not available")
        val inputNames = cachedInputNames ?: throw IllegalStateException("input names not cached")
        val outputNames = cachedOutputNames ?: throw IllegalStateException("output names not cached")

        // 批量评分
        val scored = candidates.map { c ->
            val score = scoreSingle(activeEnv, activeSession, activeVocab, inputNames, outputNames, query, c.content)
            RerankResult(c.chunkId, c.docId, c.docTitle, c.content, c.chunkIndex, score)
        }
        scored.sortedByDescending { it.score }.take(topK)
    }

    /** 单个 (query, candidate) 评分:拼接 → 分词 → ONNX 推理 → sigmoid 归一化。 */
    private fun scoreSingle(
        env: OrtEnvironment,
        session: OrtSession,
        vocab: Map<String, Int>,
        inputNames: List<String>,
        outputNames: List<String>,
        query: String,
        candidate: String,
    ): Float {
        // 1. 拼接为 BERT cross-encoder 输入:[CLS] query [SEP] candidate [SEP]
        val tokenIds = encodePair(query, candidate, vocab)
        // 2. 截断 / padding
        val padded = padOrTruncate(tokenIds, seqLen)
        val inputIds = padded.first
        val attentionMask = padded.second
        // token_type_ids:前段(query)为 0,后段(candidate)为 1
        val tokenTypeIds = buildTokenTypeIds(tokenIds, seqLen)

        // 3. ONNX 推理
        val inputs = mutableMapOf<String, OnnxTensor>()
        var inputIdsTensor: OnnxTensor? = null
        var attnMaskTensor: OnnxTensor? = null
        var tokenTypeTensor: OnnxTensor? = null
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            attnMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            tokenTypeTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

            assignInput(inputNames, "input_ids", inputIdsTensor, inputs)
            assignInput(inputNames, "attention_mask", attnMaskTensor, inputs)
            assignInput(inputNames, "token_type_ids", tokenTypeTensor, inputs)
            assignInput(inputNames, "token_type_ids.1", tokenTypeTensor, inputs)

            val result = session.run(inputs)
            try {
                val logit = extractLogit(result, outputNames)
                // 4. sigmoid 归一化到 [0, 1]
                return sigmoid(logit)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor?.close()
            attnMaskTensor?.close()
            tokenTypeTensor?.close()
        }
    }

    private suspend fun ensureInitialized() {
        if (session != null && vocab != null) return
        mutex.withLock {
            if (session != null && vocab != null) return@withLock
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val newSession = ortEnv.createSession(modelPath, opts)
            val newVocab = loadVocab(resolveVocabPath())

            session = newSession
            env = ortEnv
            vocab = newVocab
            cachedInputNames = newSession.inputNames.toList()
            cachedOutputNames = newSession.outputNames.toList()
            Logger.i(
                "OnnxRerankProvider",
                "ONNX rerank session 已加载: model=${File(modelPath).name}, vocab=${newVocab.size} tokens",
            )
        }
    }

    private fun loadVocab(path: String): Map<String, Int> {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("ONNX rerank vocab.txt not found: '$path'")
        }
        val map = HashMap<String, Int>(21128)
        file.useLines { lines ->
            lines.forEachIndexed { idx, line -> map[line.trimEnd()] = idx }
        }
        return map
    }

    private fun resolveVocabPath(): String {
        if (vocabPath.isNotBlank()) return vocabPath
        if (modelPath.isBlank()) return ""
        val modelFile = File(modelPath)
        val sameNameVocab = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".vocab.txt")
        if (sameNameVocab.exists()) return sameNameVocab.absolutePath
        val defaultVocab = File(modelFile.parentFile, "vocab.txt")
        if (defaultVocab.exists()) return defaultVocab.absolutePath
        return sameNameVocab.absolutePath
    }

    /** 拼接 query + candidate → token ids:[CLS] q_tokens [SEP] c_tokens [SEP]。 */
    private fun encodePair(query: String, candidate: String, vocab: Map<String, Int>): List<Long> {
        val queryTokens = WordPieceTokenizer.tokenize(query, vocab)
        val candidateTokens = WordPieceTokenizer.tokenize(candidate, vocab)
        // 截断:留出 [CLS] [SEP] [SEP] 3 个特殊位
        val maxEach = (seqLen - 3) / 2
        val qTrunc = queryTokens.take(maxEach)
        val cTrunc = candidateTokens.take(maxEach)

        val ids = mutableListOf<Long>()
        ids.add(vocab["[CLS]"]?.toLong() ?: 101L)
        qTrunc.forEach { ids.add(vocab[it]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L)) }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        cTrunc.forEach { ids.add(vocab[it]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L)) }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        return ids
    }

    /** token_type_ids:query 段为 0,candidate 段为 1。 */
    private fun buildTokenTypeIds(tokenIds: List<Long>, maxLen: Int): LongArray {
        val typeIds = LongArray(maxLen) { 0 }
        // 找到第二个 [SEP](=102)的位置,之后的 token 改为 1
        var sepCount = 0
        for (i in tokenIds.indices) {
            if (i >= maxLen) break
            if (tokenIds[i] == 102L) {
                sepCount++
                if (sepCount == 1) {
                    // 下一个 token 开始是 candidate 段
                    for (j in (i + 1) until minOf(tokenIds.size, maxLen)) {
                        typeIds[j] = 1
                    }
                    break
                }
            }
        }
        return typeIds
    }

    private fun padOrTruncate(tokenIds: List<Long>, maxLen: Int): Pair<LongArray, LongArray> {
        val inputIds = LongArray(maxLen) { 0 }
        val attentionMask = LongArray(maxLen) { 0 }
        val take = minOf(tokenIds.size, maxLen)
        for (i in 0 until take) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }
        return inputIds to attentionMask
    }

    private fun assignInput(
        inputNames: List<String>,
        candidate: String,
        tensor: OnnxTensor,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        if (inputNames.contains(candidate)) inputs[candidate] = tensor
    }

    /** 从结果中提取 logit(典型 shape [1, 2] 或 [1, 1])。 */
    private fun extractLogit(result: OrtSession.Result, outputNames: List<String>): Float {
        val name = outputNames.firstOrNull { it.contains("logits", ignoreCase = true) }
            ?: outputNames.first()
        val onnxValue = result.get(name).orElse(null)
            ?: throw IllegalStateException("output '$name' not found in rerank result")
        val value = onnxValue.value
        return when (value) {
            is FloatArray -> {
                // [2] → 取 [1](true 类分数);[1] → 直接取
                if (value.size >= 2) value[1] else value[0]
            }
            is Array<*> -> {
                // [1, 2] 或 [1, 1]
                val row = value[0]
                when (row) {
                    is FloatArray -> if (row.size >= 2) row[1] else row[0]
                    else -> (row as Number).toFloat()
                }
            }
            is Number -> value.toFloat()
            else -> throw IllegalStateException("unexpected logits shape: ${value::class}")
        }
    }

    /** sigmoid:1 / (1 + e^-x),把 logit 压缩到 [0, 1]。 */
    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).coerceIn(0f, 1f)

    /** 关闭 session,释放 native 资源。 */
    fun close() {
        resultOf {
            session?.close()
            session = null
            vocab = null
            cachedInputNames = null
            cachedOutputNames = null
        }.onError { msg, e -> Logger.w("OnnxRerankProvider", "close 失败: $msg", e) }
    }

    /** 检查本地 ONNX rerank 是否可用。 */
    fun isAvailable(): Boolean {
        if (modelPath.isBlank() || !File(modelPath).exists()) return false
        val vPath = resolveVocabPath()
        return File(vPath).exists()
    }

    companion object {
        const val MAX_SEQ_LEN = 512
    }
}
