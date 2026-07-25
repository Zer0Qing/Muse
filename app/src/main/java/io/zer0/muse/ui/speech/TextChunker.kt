package io.zer0.muse.ui.speech

/**
 * 文本分片器 — 把长文本按标点切成适合 TTS 朗读的小片。
 *
 * 参考 rikkahub TextChunker 实现:
 *  - 用 lookbehind 正则 `(?<=[。！？，、：;.!?:,\n])` 按标点切分(标点保留在前片)
 *  - 累积策略:当前片 + 下一段不超过 [maxChunkLength] 就继续累积,超过才切新片
 *  - 硬截断兜底:无标点长段用 chunked(maxChunkLength) 强切,避免单段过长撑爆 TTS 合成
 *
 * 设计权衡:maxChunkLength = 150 兼顾「合成时延」与「断句自然度」
 *  - 太短(<50):每片几个字,合成开销大,断句破碎
 *  - 太长(>300):单片合成数秒,首字时延明显,出错需重整片
 */
object TextChunker {

    /** 默认单片最大字符数。 */
    const val DEFAULT_MAX_CHUNK_LENGTH = 150

    /**
     * 句子结束标点(中英文 + 换行)。
     * lookbehind `(?<=[...])` 匹配标点后的零宽位置,String.split 在该位置切割,
     * 标点本身保留在前一段(朗读时停顿自然)。
     */
    private val sentenceEndRegex = Regex("(?<=[。！？，、：;.!?:,\n])")

    /**
     * 把 [text] 切成适合 TTS 朗读的小片列表。
     *
     * 算法:
     *  1. 用 [sentenceEndRegex] 按标点切分,得到原始段落(标点已附在段尾)
     *  2. 遍历段落做累积:当前片 + 下一段超过 [maxChunkLength] → 提交当前片,开新片
     *  3. 单段超过 [maxChunkLength](无标点长段)→ 用 [chunked] 硬切
     *
     * @param text 原始文本(建议先经过 [TtsManager.stripMarkdown] 清理 Markdown)
     * @param maxChunkLength 单片最大字符数
     * @return 分片列表(空文本 / 全空白返回空列表)
     */
    fun chunk(text: String, maxChunkLength: Int = DEFAULT_MAX_CHUNK_LENGTH): List<String> {
        if (text.isBlank()) return emptyList()
        // 按标点切分(标点保留在前片),过滤空白片(避免连续标点产生空段)
        val rawSegments = text.split(sentenceEndRegex).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (segment in rawSegments) {
            // 累积策略:当前片 + 下一段超过上限,先提交当前片再开新片
            if (current.isNotEmpty() && current.length + segment.length > maxChunkLength) {
                result.add(current.toString())
                current.clear()
            }
            // 硬截断兜底:单段(无标点长段)超过上限,用 chunked 强切
            if (segment.length > maxChunkLength) {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                segment.chunked(maxChunkLength).forEach { result.add(it) }
            } else {
                current.append(segment)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
