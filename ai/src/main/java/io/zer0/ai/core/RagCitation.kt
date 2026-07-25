package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * v1.133: RAG 引用 — 用于在 ChatUI 中渲染"这段回答参考了哪些文档"。
 *
 * 由 [io.zer0.muse.rag.RagService.buildInjectionContextWithCitations] 返回,
 * ChatViewModel 注入到 [UIMessage.ragCitations] 字段,
 * MessageBubble 渲染为可点击的引用 chip(点击展开 snippet,长按跳转文档详情)。
 *
 * 放在 ai.core 包下(而非 muse.rag)是为了让 [UIMessage] 能直接引用,
 * 避免反向依赖(ai 模块不应依赖 app 模块)。
 */
@Serializable
data class RagCitation(
    /** 引用编号(从 1 开始,对应 system prompt 中的 [1] [2] ...)。 */
    val index: Int,
    val docId: String,
    val docTitle: String,
    val chunkId: String,
    val chunkIndex: Int,
    /** 命中片段(前 200 字,用于 chip 展开预览)。 */
    val snippet: String,
    /** 检索分数(向量相似度 / RRF 分数 / Rerank 分数,取决于路径)。 */
    val score: Float,
    /** v1.133: 命中类型(vector / hybrid / rerank / keyword_fallback)。 */
    val matchType: String,
    /**
     * v1.133: 是否为内部文档(预置开发文档 devdoc 等)。
     *
     * 由 [io.zer0.muse.rag.RagService] 在构造 RagCitation 时从 [io.zer0.muse.data.knowledge.KnowledgeDocEntity]
     * 传入,统一替代原 `docId.startsWith("devdoc-")` 硬编码判断。
     * 默认 false 保持向后兼容(旧序列化数据反序列化时取默认值)。
     */
    val isInternal: Boolean = false,
)
