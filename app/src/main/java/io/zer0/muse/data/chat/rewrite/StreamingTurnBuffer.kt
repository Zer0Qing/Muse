package io.zer0.muse.data.chat.rewrite

/** 流式消息的结构化 part。 */
data class StreamingPart(
    val kind: String,
    val text: String,
    val metadataJson: String = "{}",
)

/** 不可变的流式草稿快照。 */
data class MessageDraft(
    val turnId: String,
    val assistantId: String,
    val parts: List<StreamingPart>,
    val finished: Boolean,
) {
    val visibleText: String
        get() = parts.filter { it.kind == "text" }.joinToString("") { it.text }

    val reasoningText: String
        get() = parts.filter { it.kind == "reasoning" }.joinToString("") { it.text }
}

enum class FallbackDecision {
    ReplacedEmptyDraft,
    IgnoredBecauseVisibleContentExists,
    IgnoredBecauseFinished,
}

/**
 * 单一流式累积器。
 *
 * 事件按 turnId、streamId 和 sequenceInStream 去重；完成后拒绝后续 delta，
 * 从而避免旧 stream 或重复 finalize 污染新回复。
 */
class StreamingTurnBuffer(
    val turnId: String,
    val assistantId: String,
    val streamId: String,
) {
    private val parts = mutableListOf<StreamingPart>()
    private val acceptedSequences = mutableSetOf<Long>()
    private var nextSequence = 0L
    private var finished = false

    /** 追加文本 delta；返回 false 表示事件被拒绝或重复。 */
    fun appendText(sequenceInStream: Long, delta: String): Boolean =
        append(sequenceInStream, "text", delta)

    /** 兼容没有 sequence 的旧 provider，使用本地递增序号。 */
    fun appendText(delta: String): Boolean = appendText(nextSequence, delta)

    /** 追加 reasoning delta。 */
    fun appendReasoning(sequenceInStream: Long, delta: String): Boolean =
        append(sequenceInStream, "reasoning", delta)

    /** 追加工具 part。 */
    fun appendTool(sequenceInStream: Long, toolName: String, metadataJson: String): Boolean =
        append(sequenceInStream, "tool", toolName, metadataJson)

    /**
     * 应用非流式 fallback。
     * 已有可见正文时绝不能把完整 completion 当普通 delta 拼接。
     */
    fun applyFallback(fullText: String): FallbackDecision {
        if (finished) return FallbackDecision.IgnoredBecauseFinished
        if (parts.any { it.kind == "text" && it.text.isNotEmpty() }) {
            return FallbackDecision.IgnoredBecauseVisibleContentExists
        }
        parts.removeAll { it.kind == "text" }
        parts.add(StreamingPart("text", fullText))
        return FallbackDecision.ReplacedEmptyDraft
    }

    fun snapshot(): MessageDraft = MessageDraft(turnId, assistantId, parts.toList(), finished)

    fun finish(): MessageDraft {
        finished = true
        return snapshot()
    }

    private fun append(sequenceInStream: Long, kind: String, text: String, metadataJson: String = "{}"): Boolean {
        if (finished || sequenceInStream < 0 || !acceptedSequences.add(sequenceInStream)) return false
        nextSequence = maxOf(nextSequence, sequenceInStream + 1)
        if (text.isEmpty() && kind != "tool") return true
        parts.add(StreamingPart(kind, text, metadataJson))
        return true
    }
}
