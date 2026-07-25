package io.zer0.muse.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.63: 本地关键词 Embedding Provider(无需 ONNX,无需联网)。
 *
 * 实现原理:对文本进行分词(中文按字符 bigram + 英文按单词),然后用哈希函数
 * 把每个 token 映射到固定维度向量的若干维度上,累加权重后 L2 归一化。
 * 这是一种类似于 HashingVectorizer 的方案,虽然不如神经网络 embedding 语义
 * 丰富,但完全离线、零依赖、速度极快,适合中小规模知识库的关键词检索。
 *
 * 优势:
 *  - 无需 ONNX runtime / 模型文件 / 网络
 *  - 向量维度固定(512 维),与 VectorSearchService 的余弦相似度兼容
 *  - 中文 bigram 能捕获"知识库""检索"等复合词特征
 *
 * 局限:
 *  - 无深层语义理解(同义词/上下文推理能力弱)
 *  - 适合关键词匹配场景,不适合复杂语义问答
 *  - 对极短文本(少于 3 字)效果一般
 */
class LocalKeywordEmbeddingProvider : EmbeddingProvider {

    override val id: String = "local-keyword"
    override val displayName: String = "Local Keyword (Offline)"
    override val modelName: String = "local-keyword-v1"
    override val dimension: Int = HASH_DIM

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        texts.map { embedSingle(it) }
    }

    /** 对单条文本生成归一化的哈希词频向量。 */
    private fun embedSingle(text: String): FloatArray {
        val vec = FloatArray(HASH_DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        for (token in tokens) {
            val hash = stableHash(token)
            val index = (hash and 0x7FFFFFFF).toInt() % HASH_DIM
            // 词频累加:重复 token 增加权重
            vec[index] += 1f
        }

        // L2 归一化,使余弦相似度有效
        var sum = 0f
        for (v in vec) sum += v * v
        val n = kotlin.math.sqrt(sum)
        if (n > 0f) {
            for (i in vec.indices) vec[i] /= n
        }
        return vec
    }

    companion object {
        /** 哈希向量维度(512 维,兼顾精度和内存)。 */
        private const val HASH_DIM = 512

        /**
         * 分词:中文按字符 bigram,英文按单词(小写化)。
         * "知识库检索" -> ["知识", "识库", "库检", "检索"]
         * "RAG retrieval" -> ["rag", "retrieval"]
         */
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val cleaned = text.lowercase().trim()
            if (cleaned.isEmpty()) return tokens

            // 分离中文和英文段落
            val currentChinese = StringBuilder()
            for (ch in cleaned) {
                if (ch.code in 0x4E00..0x9FFF) {
                    // CJK 统一汉字
                    currentChinese.append(ch)
                } else {
                    // 非中文字符:先刷出累积的中文 bigram
                    if (currentChinese.isNotEmpty()) {
                        addChineseBigrams(currentChinese.toString(), tokens)
                        currentChinese.clear()
                    }
                    // 英文/数字单词由下方 split 统一处理
                }
            }
            if (currentChinese.isNotEmpty()) {
                addChineseBigrams(currentChinese.toString(), tokens)
            }

            // 英文单词分词(按非字母数字分割)
            val englishWords = cleaned.split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 2 }
            tokens.addAll(englishWords)

            // 不去重:重复 token 的词频对相似度计算有意义
            return tokens
        }

        /** 中文 bigram:相邻两个字组成一个 token。 */
        private fun addChineseBigrams(text: String, tokens: MutableList<String>) {
            if (text.length == 1) {
                tokens.add(text)
                return
            }
            for (i in 0 until text.length - 1) {
                tokens.add(text.substring(i, i + 2))
            }
        }

        /** 稳定哈希(FNV-1a 变体,跨平台一致)。 */
        private fun stableHash(str: String): Long {
            var hash = 2166136261L
            for (ch in str) {
                hash = hash xor ch.code.toLong()
                hash *= 16777619L
                hash = hash and 0xFFFFFFFFL
            }
            return hash
        }
    }
}
