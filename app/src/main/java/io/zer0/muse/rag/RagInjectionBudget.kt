package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.muse.context.ContextBudget
import io.zer0.muse.context.ContextSection

/**
 * RAG 注入与引用的一致性收口。
 *
 * 此前调用方只对 `injection.text` 做 [ContextBudget] 截断,却把**未截断**的
 * `injection.citations` 原样带下去。截断发生在文本尾部,被截掉的片段其 `[n]`
 * 在正文里已不存在,引用却仍会渲染成可点标签 —— 用户点开看到的内容模型根本没读到。
 *
 * 本对象把「截断」和「引用裁剪」绑成一步:引用列表以**截断后文本中真实出现的 [n]**
 * 为准,文本与引用始终同源。
 */
object RagInjectionBudget {

    /**
     * 形如 `[12]` 的引用编号标记。
     *
     * 限定行首:注入的编号行一定是 `[n] 来源:` / `[n] Source:` 开头,
     * 而片段正文里出现的方括号数字不应被当成引用。
     */
    private val CITATION_MARKER = Regex("^\\[(\\d{1,4})]", RegexOption.MULTILINE)

    private const val KNOWLEDGE_CONTEXT_OPEN = "<knowledge_context>"
    private const val KNOWLEDGE_CONTEXT_CLOSE = "</knowledge_context>"

    /** 裁剪结果:与文本保持一致的注入单元。 */
    data class Result(
        val text: String,
        val citations: List<RagCitation>,
        /** 文本是否被预算截断。 */
        val truncated: Boolean,
        /** 被裁掉的引用数(供调用方日志/诊断)。 */
        val droppedCitations: Int,
    )

    /**
     * 按 [section] 预算截断 [injection],并把 citations 收敛到截断后仍可回指的编号。
     *
     * 若截断把 `<knowledge_context>` 的闭合标签也切掉,补回闭合标签:边界标签本身是
     * 防注入声明的一部分,缺失会让「资料块」在模型看来一直没结束,后续正文被当成资料。
     */
    fun clampAndTrim(
        injection: RagInjection,
        budget: ContextBudget = ContextBudget(),
        section: ContextSection = ContextSection.RAG_CITATION,
    ): Result {
        val clamped = budget.clamp(section, injection.text)
        val text = if (clamped.truncated) closeDanglingKnowledgeContext(clamped.text) else clamped.text
        val visibleIndexes = CITATION_MARKER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        val kept = injection.citations.filter { it.index in visibleIndexes }
        return Result(
            text = text,
            citations = kept,
            truncated = clamped.truncated,
            droppedCitations = injection.citations.size - kept.size,
        )
    }

    /**
     * 截断后只剩开标签时补回闭合标签;已有闭合标签(或从未开标签)时原样返回。
     *
     * 闭合标签插在截断注记之前,而不是整段末尾:注记是给调用方看的诊断信息,
     * 不该被包进「资料块」里让模型当成知识库内容。
     */
    private fun closeDanglingKnowledgeContext(text: String): String {
        val openAt = text.lastIndexOf(KNOWLEDGE_CONTEXT_OPEN)
        if (openAt < 0) return text
        val closeAt = text.indexOf(KNOWLEDGE_CONTEXT_CLOSE, openAt + KNOWLEDGE_CONTEXT_OPEN.length)
        if (closeAt >= 0) return text
        val noticeAt = text.indexOf(ContextBudget.TRUNCATION_NOTICE_PREFIX, openAt)
        return if (noticeAt >= 0) {
            text.substring(0, noticeAt) + "\n" + KNOWLEDGE_CONTEXT_CLOSE + text.substring(noticeAt)
        } else {
            "$text\n$KNOWLEDGE_CONTEXT_CLOSE"
        }
    }
}
