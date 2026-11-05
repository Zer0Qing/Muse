package io.zer0.memory.ai

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage

/**
 * v2.6.23(M1): 用户原话证据匹配。
 *
 * 判定从严:归一化(去空白与标点)后,事实正文必须是某条**用户消息**的子串,
 * 且事实长度 ≥ [MemoryAutoSaveScheduler.EVIDENCE_MIN_FACT_CHARS] —— 短事实的
 * 子串匹配几乎必然误配("喜欢咖啡"能命中一屏消息)。宁可不写证据,不能写错证据:
 * 记忆卡的「依据」一旦被点开后对不上话,整条记忆的信任都会崩掉。
 *
 * 命中时返回 (用户消息原文, 消息 id);摘录截断与脱敏在 FactStore.normalizeEvidence 做。
 */
internal object UserEvidenceMatcher {

    /** v20(M1): 证据匹配前剥离的空白与标点(与 FactStore 墓碑双通道匹配的剥离口径一致)。 */
    private val STRIP_RE = Regex("[\\s，。！？、；：,.!?;:()（）\\[\\]【】「」『』《》\"'“”‘’]+")

    /** 提取层爱写"用户…",原话是"我…"——匹配前剥离同一组主语前缀(与去重 normalizeDedupText 同源)。 */
    private val SUBJECT_RE = Regex("^(用户|我|他|她|该用户|the user|i am|he is|she is)")

    fun find(history: List<UIMessage>?, factText: String, minFactChars: Int): Pair<String, String>? {
        if (history.isNullOrEmpty()) return null
        val normalizedFact = strip(factText)
        if (normalizedFact.length < minFactChars) return null
        // 从最近的消息找起:本轮末尾的原话更可能是出处。
        for (message in history.asReversed()) {
            if (message.role != MessageRole.USER || message.content.isBlank()) continue
            if (strip(message.content).contains(normalizedFact)) {
                return message.content to message.id.toString()
            }
        }
        return null
    }

    private fun strip(text: String): String = SUBJECT_RE.replace(text.replace(STRIP_RE, ""), "")
}
