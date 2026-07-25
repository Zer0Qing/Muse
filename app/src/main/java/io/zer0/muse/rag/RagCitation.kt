package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation

/**
 * v1.133: 注入结果 — 包含文本 + 引用列表。
 *
 * [RagCitation] 已移至 [io.zer0.ai.core] 包,使 [io.zer0.ai.core.UIMessage]
 * 可直接引用(避免 ai 模块反向依赖 app 模块)。
 */
data class RagInjection(
    /** 注入到 system prompt 的文本(含 [N] 引用标记)。 */
    val text: String,
    /** 引用列表(与 text 中的 [N] 对应)。 */
    val citations: List<RagCitation>,
    /** 检索耗时(ms,仅用于性能监控)。 */
    val elapsedMs: Long,
)
