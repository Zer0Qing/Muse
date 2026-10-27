package io.zer0.muse.ui.chat

import io.zer0.muse.transformer.InternalMarkupSanitizer

/**
 * 流式刷新缓存。
 *
 * 流式期间 content / reasoning 只会向后追加，不会整体改写。基于这一点，
 * 有几件事在第一次算出结果后就不会再变，没必要每帧重算：
 *
 *  1. 标签标记是否存在 —— 前缀里出现过的标记，追加再多正文也不会消失；
 *  2. mood 内容 —— mood 块位于开头，闭合之后追加的正文不会再影响提取结果；
 *  3. reasoning 的标签剥离结果 —— 长度没变说明没追加，直接复用。
 *
 * 这三件事原本每帧都会对全文跑一次正则，长回复时是主要开销。
 * 任何一条不成立（内容变短、换了消息、流式结束）都退回原始计算路径。
 *
 * 线程约束：只在流式回调的同一线程内使用，不做线程安全处理。
 */
internal class StreamFlushCache {
    private var currentId: String? = null

    /** 是否已在本条消息里见过标签标记（一旦为 true 不再回退）。 */
    private var tagMarkerSeen = false

    /** mood 已提取出的结果；null 表示尚未成功提取。 */
    private var moodResult: String? = null

    /** mood 结果被确认锁定时 content 的长度。 */
    private var moodSealedLength = -1

    /** reasoning 剥离缓存的输入长度与结果。 */
    private var reasoningLength = -1
    private var reasoningStripped: String? = null

    /** 上一次见到的内容长度，用于识别内容被整体替换（变短）的情况。 */
    private var lastContentLength = -1

    /**
     * 切换消息时重置。同一条消息内反复调用是安全的。
     */
    fun begin(messageId: String, contentLength: Int) {
        if (currentId != messageId) {
            currentId = messageId
            tagMarkerSeen = false
            moodResult = null
            moodSealedLength = -1
            reasoningLength = -1
            reasoningStripped = null
            lastContentLength = -1
        } else if (contentLength < lastContentLength) {
            // 内容变短 = 被改写（编辑 / 重新生成 / 清洗回写），缓存全部作废。
            tagMarkerSeen = false
            moodResult = null
            moodSealedLength = -1
        }
        lastContentLength = contentLength
    }

    /**
     * 内容里是否含内部标签标记。
     *
     * 命中过一次就恒为 true：正文只追加时，已有的标记不会消失。
     */
    fun hasTagMarker(text: String): Boolean {
        if (tagMarkerSeen) return true
        val hit = InternalMarkupSanitizer.TAG_MARKER_REGEX.containsMatchIn(text)
        if (hit) tagMarkerSeen = true
        return hit
    }

    /**
     * 提取 mood，闭块之后直接复用上次结果。
     *
     * 复用条件两个同时满足：
     *  - 当前长度不小于锁定时的长度（没有回退）；
     *  - 锁定点之后没有新的开标签（否则说明模型又写了一个 mood 块）。
     */
    fun mood(content: String, extract: (String) -> String?): String? {
        val cached = moodResult
        if (cached != null && content.length >= moodSealedLength) {
            if (!hasNewOpeningMarker(content, moodSealedLength)) return cached
        }
        val fresh = extract(content)
        moodResult = fresh
        // 只有在确实提取到内容时才锁定：空结果可能只是块还没写完。
        moodSealedLength = if (fresh != null) content.length else -1
        return fresh
    }

    /**
     * reasoning 的标签外壳剥离，长度不变时复用。
     *
     * 流式期间 reasoning 也只追加，长度相同即代表内容相同。
     */
    fun strippedReasoning(raw: String, strip: (String) -> String): String {
        val cached = reasoningStripped
        if (cached != null && raw.length == reasoningLength) return cached
        val result = strip(raw)
        reasoningStripped = result
        reasoningLength = raw.length
        return result
    }

    /** 流式结束或需要强制重算时清空派生缓存（保留消息 id）。 */
    fun invalidateDerived() {
        moodResult = null
        moodSealedLength = -1
        reasoningLength = -1
        reasoningStripped = null
    }

    private fun hasNewOpeningMarker(content: String, fromLength: Int): Boolean {
        // 开标签最长 "<moodfx>"（8 字符），回退 8 保证不切断恰好跨越边界的标签。
        val from = (fromLength - 8).coerceAtLeast(0)
        if (from >= content.length) return false
        val tail = content.substring(from).lowercase()
        return tail.contains("<mood") || tail.contains("<mod") ||
            tail.contains("[mood]") || tail.contains("[mod]")
    }
}
