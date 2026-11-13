package io.zer0.muse.asr.local

/**
 * tokens.txt 解析与 CTC greedy 解码。
 *
 * tokens.txt 每行格式：`token id`（空格分隔，token 自身可含空格，以最后一个空格分界）。
 * 解码顺序：logits 逐帧 argmax → 去重 → 去 blank(0) → 跳过前导控制 token → 按 id 拼词。
 */
internal object SenseVoiceTokens {

    /** CTC blank 的 token id（tokens.txt 中 id=0 的 `<unk>` 前置 blank 位）。 */
    private const val BLANK_ID = 0

    /** 模型输出里固定的前导控制序列长度：`<|lang|><|emo|><|event|><|itn|>`。 */
    internal const val LEADING_CONTROL_COUNT = 4

    /**
     * 解析 tokens.txt。返回 id → token 文本。
     *
     * token 内部可以含空格（如 `▁the` 不含，但部分词表有带空格项），所以按
     * 「最后一个空格是分隔符」切分而不是 split(" ")。
     */
    fun parse(content: String): Map<Int, String> {
        val map = HashMap<Int, String>(26000)
        content.lineSequence().forEach { line ->
            val trimmed = line.trimEnd('\n', '\r')
            if (trimmed.isBlank()) return@forEach
            val sep = trimmed.lastIndexOf(' ')
            if (sep <= 0) return@forEach
            val id = trimmed.substring(sep + 1).toIntOrNull() ?: return@forEach
            map[id] = trimmed.substring(0, sep)
        }
        return map
    }

    /**
     * CTC greedy 解码。
     *
     * 逐帧取 argmax；连续相同帧合并为一；blank 帧丢弃。
     * @param frameIds 每帧的 argmax token id
     */
    fun ctcGreedy(frameIds: IntArray): List<Int> {
        val out = ArrayList<Int>(frameIds.size)
        var prev = -1
        for (id in frameIds) {
            if (id != prev && id != BLANK_ID) out.add(id)
            prev = id
        }
        return out
    }

    /**
     * token id 序列 → 可读文本。
     *
     * 跳过前 [LEADING_CONTROL_COUNT] 个控制 token（语言 / 情绪 / 事件 / ITN 标记），
     * 其余按词表拼接。SenseVoice 的 token 自带 `▁` 词边界符号时直接去除。
     */
    fun decode(ids: List<Int>, vocab: Map<Int, String>): String = buildString {
        for ((index, id) in ids.withIndex()) {
            if (index < LEADING_CONTROL_COUNT) continue
            val token = vocab[id] ?: continue
            append(token.removePrefix("▁"))
        }
    }
}
