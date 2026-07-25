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
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * v1.134: 本地 ONNX Embedding Provider(完整实现)。
 *
 * 取代 v1.54 的占位骨架,提供真正的本地神经网络语义嵌入。
 *
 * 工作流程:
 *  1. 懒加载 [OrtSession] + vocab.txt(从 [modelPath] / [vocabPath])
 *  2. WordPiece 分词 → input_ids / attention_mask / token_type_ids
 *  3. ONNX 推理 → last_hidden_state(或 pooler_output)
 *  4. Mean Pooling(按 attention_mask 加权)+ L2 归一化
 *
 * 模型文件约定:
 *  - [modelPath]:绝对路径或 filesDir 相对路径,指向 `.onnx` 文件
 *  - [vocabPath]:BERT 风格 `vocab.txt`(每行一个 token),空串时尝试 `[modelPath].vocab.txt`
 *  - 模型未配置或文件缺失 → [embed] 抛 [IllegalStateException],
 *    [EmbeddingService] 会捕获并降级到 [LocalKeywordEmbeddingProvider]
 *
 * 推荐模型(用户自行下载到 filesDir/muse_onnx/):
 *  - `bge-small-zh-v1.5`(512 维,中文优化,~95MB)
 *  - `all-MiniLM-L6-v2`(384 维,多语言,~23MB)
 *  - `mteb-chinese-tiny`(768 维,中文,~25MB)
 *
 * @param modelPath ONNX 模型文件路径
 * @param vocabPath BERT vocab.txt 路径(空串=自动推断)
 * @param seqLen 最大序列长度(默认 512,长文本截断)
 */
class OnnxEmbeddingProvider(
    private val modelPath: String,
    private val vocabPath: String = "",
    private val seqLen: Int = MAX_SEQ_LEN,
) : EmbeddingProvider {

    override val id: String = "local-onnx"
    override val displayName: String = "Local ONNX"
    override val modelName: String = if (modelPath.isBlank()) "Not Configured"
        else File(modelPath).nameWithoutExtension

    /** 推断维度:根据模型文件名关键词匹配常见模型维度。 */
    override val dimension: Int by lazy {
        val name = modelPath.lowercase()
        when {
            name.contains("minilm") -> 384
            name.contains("bge-small") || name.contains("bge-tiny") -> 512
            name.contains("bge-base") || name.contains("bge-large") || name.contains("mteb") -> 768
            name.contains("e5-small") -> 384
            name.contains("e5-base") || name.contains("e5-large") -> 768
            name.contains("gte") -> 768
            else -> 768
        }
    }

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var vocab: Map<String, Int>? = null
    @Volatile private var cachedInputNames: List<String>? = null
    @Volatile private var cachedOutputNames: List<String>? = null
    private val mutex = Mutex()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (modelPath.isBlank() || !File(modelPath).exists()) {
            throw IllegalStateException(
                "ONNX embedding model not found: '$modelPath'. " +
                    "Please place a .onnx file at filesDir/muse_onnx/embedding.onnx " +
                    "and configure RagConfig.localModelPath, or switch to CLOUD/LOCAL_KEYWORD mode.",
            )
        }
        ensureInitialized()
        val activeSession = session ?: throw IllegalStateException("ONNX session init failed")
        val activeVocab = vocab ?: throw IllegalStateException("ONNX vocab load failed")
        val activeEnv = env ?: throw IllegalStateException("ONNX env not available")
        val inputNames = cachedInputNames ?: throw IllegalStateException("input names not cached")
        val outputNames = cachedOutputNames ?: throw IllegalStateException("output names not cached")

        texts.map { text ->
            embedSingle(activeEnv, activeSession, activeVocab, inputNames, outputNames, text)
        }
    }

    /** 单条文本 embedding:分词 → padding → ONNX 推理 → mean pool + L2 归一化。 */
    private fun embedSingle(
        env: OrtEnvironment,
        session: OrtSession,
        vocab: Map<String, Int>,
        inputNames: List<String>,
        outputNames: List<String>,
        text: String,
    ): FloatArray {
        // 1. 分词 → token ids(加 [CLS] / [SEP])
        val tokenIds = encode(text, vocab)
        // 2. 截断 / padding 到 seqLen
        val padded = padOrTruncate(tokenIds, seqLen)
        val inputIds = padded.first
        val attentionMask = padded.second
        val tokenTypeIds = LongArray(seqLen) { 0 }

        // 3. 构造 ONNX 输入张量(shape: [1, seqLen])
        val inputs = mutableMapOf<String, OnnxTensor>()
        var inputIdsTensor: OnnxTensor? = null
        var attnMaskTensor: OnnxTensor? = null
        var tokenTypeTensor: OnnxTensor? = null
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            attnMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            tokenTypeTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

            // 按模型实际输入名分配(兼容 input_ids / input_ids.1 / ids 等命名)
            assignInput(inputNames, "input_ids", inputIdsTensor, inputs)
            assignInput(inputNames, "attention_mask", attnMaskTensor, inputs)
            assignInput(inputNames, "token_type_ids", tokenTypeTensor, inputs)
            assignInput(inputNames, "token_type_ids.1", tokenTypeTensor, inputs)

            // 4. 运行推理
            val result = session.run(inputs)
            try {
                // 5. 提取输出 — 优先 pooler_output([1, hidden])否则 last_hidden_state([1, seq, hidden])
                val pooled = extractPooledOutput(result, outputNames, attentionMask)
                // 6. L2 归一化(余弦相似度要求)
                return l2Normalize(pooled)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor?.close()
            attnMaskTensor?.close()
            tokenTypeTensor?.close()
        }
    }

    /** 懒加载 session + vocab。线程安全。 */
    private suspend fun ensureInitialized() {
        if (session != null && vocab != null) return
        mutex.withLock {
            if (session != null && vocab != null) return@withLock
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            // 加载模型(可能耗时数百毫秒,但在 Dispatchers.Default 中安全)
            val newSession = ortEnv.createSession(modelPath, opts)
            val newVocab = loadVocab(resolveVocabPath())

            session = newSession
            env = ortEnv
            vocab = newVocab
            cachedInputNames = newSession.inputNames.toList()
            cachedOutputNames = newSession.outputNames.toList()
            Logger.i(
                "OnnxEmbeddingProvider",
                "ONNX session 已加载: model=${File(modelPath).name}, vocab=${newVocab.size} tokens, " +
                    "inputs=${newSession.inputNames}, outputs=${newSession.outputNames}",
            )
        }
    }

    /** 加载 BERT 风格 vocab.txt(每行一个 token,行号即 token id)。 */
    private fun loadVocab(path: String): Map<String, Int> {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("ONNX vocab.txt not found: '$path'")
        }
        val map = HashMap<String, Int>(21128)  // BERT-base-chinese vocab size
        file.useLines { lines ->
            lines.forEachIndexed { idx, line ->
                map[line.trimEnd()] = idx
            }
        }
        return map
    }

    /** 推断 vocab.txt 路径:显式指定 → 同目录同名 .vocab.txt → 同目录 vocab.txt。 */
    private fun resolveVocabPath(): String {
        if (vocabPath.isNotBlank()) return vocabPath
        if (modelPath.isBlank()) return ""
        val modelFile = File(modelPath)
        val sameNameVocab = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".vocab.txt")
        if (sameNameVocab.exists()) return sameNameVocab.absolutePath
        val defaultVocab = File(modelFile.parentFile, "vocab.txt")
        if (defaultVocab.exists()) return defaultVocab.absolutePath
        return sameNameVocab.absolutePath  // 返回默认路径,loadVocab 会抛异常
    }

    /**
     * 文本 → BERT token ids。
     * 加 [CLS] = 101 / [SEP] = 102 边界标记。
     */
    private fun encode(text: String, vocab: Map<String, Int>): List<Long> {
        val tokens = WordPieceTokenizer.tokenize(text, vocab)
        val ids = mutableListOf<Long>()
        ids.add(vocab["[CLS]"]?.toLong() ?: 101L)
        for (token in tokens) {
            ids.add(vocab[token]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L))
        }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        return ids
    }

    /** 截断 / padding 到固定长度,返回 (inputIds, attentionMask)。 */
    private fun padOrTruncate(tokenIds: List<Long>, maxLen: Int): Pair<LongArray, LongArray> {
        val inputIds = LongArray(maxLen) { 0 }
        val attentionMask = LongArray(maxLen) { 0 }
        val take = tokenIds.coerceAtMost(maxLen)
        for (i in 0 until take) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }
        return inputIds to attentionMask
    }

    private fun List<Long>.coerceAtMost(maxLen: Int): Int = minOf(size, maxLen)

    /** 按 inputNames 查找匹配的字段名,把张量加入 inputs map。 */
    private fun assignInput(
        inputNames: List<String>,
        candidate: String,
        tensor: OnnxTensor,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        if (inputNames.contains(candidate)) {
            inputs[candidate] = tensor
        }
    }

    /**
     * 从 OrtSession.Result 提取 pooling 后的 embedding。
     *
     * 优先策略:
     *  1. 输出名含 "pooler_output" / "pooled" → 直接返回 [hidden]
     *  2. 否则取 last_hidden_state([1, seq, hidden]),按 attention_mask 做 mean pool
     */
    private fun extractPooledOutput(
        result: OrtSession.Result,
        outputNames: List<String>,
        attentionMask: LongArray,
    ): FloatArray {
        // 优先 pooler_output
        val pooledName = outputNames.firstOrNull { name ->
            name.contains("pooler", ignoreCase = true) ||
                name.contains("pooled", ignoreCase = true) ||
                name == "sentence_embedding"
        }
        if (pooledName != null) {
            val onnxValue = result.get(pooledName).orElse(null)
                ?: throw IllegalStateException("output '$pooledName' not found in result")
            val value = onnxValue.value
            return when (value) {
                is FloatArray -> value  // [hidden]
                is Array<*> -> (value[0] as FloatArray)  // [1, hidden] → [hidden]
                else -> throw IllegalStateException("unexpected pooler_output shape: ${value::class}")
            }
        }

        // 回退:last_hidden_state mean pool
        val hiddenName = outputNames.firstOrNull { name ->
            name.contains("last_hidden_state", ignoreCase = true) ||
                name == "hidden_states" || name == "encoder_outputs"
        } ?: outputNames.first()
        val onnxValue = result.get(hiddenName).orElse(null)
            ?: throw IllegalStateException("output '$hiddenName' not found in result")
        // shape [1, seqLen, hiddenSize] → Array<FloatArray> of size seqLen
        val rawValue = onnxValue.value
        val hiddenStates: Array<FloatArray> = when (rawValue) {
            is Array<*> -> {
                // 3D: [1, seq, hidden] → rawValue[0] is Array<FloatArray>
                @Suppress("UNCHECKED_CAST")
                rawValue[0] as Array<FloatArray>
            }
            else -> throw IllegalStateException("unexpected last_hidden_state shape: ${rawValue::class}")
        }
        return meanPool(hiddenStates, attentionMask)
    }

    /** Mean Pooling:按 attention_mask 加权求平均,只统计 mask=1 的位置。 */
    private fun meanPool(hiddenStates: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hiddenSize = hiddenStates.firstOrNull()?.size ?: return FloatArray(0)
        val sum = FloatArray(hiddenSize)
        var validCount = 0
        for (i in hiddenStates.indices) {
            if (i < attentionMask.size && attentionMask[i] == 1L) {
                val vec = hiddenStates[i]
                for (j in 0 until hiddenSize) sum[j] += vec[j]
                validCount++
            }
        }
        if (validCount == 0) return FloatArray(hiddenSize)
        for (j in 0 until hiddenSize) sum[j] /= validCount.toFloat()
        return sum
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = sqrt(sum)
        if (norm == 0f) return vec
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }

    /** 关闭 session,释放 native 资源。 */
    fun close() {
        resultOf {
            session?.close()
            session = null
            vocab = null
            cachedInputNames = null
            cachedOutputNames = null
            // env 是全局共享的,不关闭
            TimeUnit.MILLISECONDS.sleep(50)  // 给 native 资源回收留时间
        }.onError { msg, e -> Logger.w("OnnxEmbeddingProvider", "close 失败: $msg", e) }
    }

    /** 检查本地 ONNX 是否可用(模型文件 + vocab 都已配置且存在)。 */
    fun isAvailable(): Boolean {
        if (modelPath.isBlank() || !File(modelPath).exists()) return false
        val vPath = resolveVocabPath()
        return File(vPath).exists()
    }

    companion object {
        /** 默认最大序列长度(BERT 标准 512)。 */
        const val MAX_SEQ_LEN = 512
    }
}
