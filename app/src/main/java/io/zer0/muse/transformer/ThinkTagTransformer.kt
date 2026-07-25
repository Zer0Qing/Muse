package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.MusePatterns

/**
 * v1.131: <think>...</think> 标签正则已迁移到 io.zer0.muse.util.MusePatterns.THINK_TAG_REGEX。
 * 本文件 [stripThinkTags] / [ThinkTagTransformer] 共用 MusePatterns.THINK_TAG_REGEX。
 */

/**
 * v1.99: 从文本中剥离所有 `<think>...</think>` 标签,返回纯净正文。
 *
 * 用于翻译路径([TranslateViewModel] / [ChatViewModel.translateMessage]):
 * 某些推理模型把思考链内嵌在 text 中(而非走 reasoningContent 通道),
 * 翻译结果会带上 `<think>...</think>` 标签,直接显示给用户体验很差。
 * 本函数用与 [ThinkTagTransformer] 相同的正则,剥离所有 think 块后返回剩余文本。
 *
 * - `<think>思考</think>回复` → "回复"
 * - `<think>思考</think>` (无正文) → ""
 * - 无 think 标签 → 原文
 */
fun stripThinkTags(text: String): String {
    if (!text.contains("<think>", ignoreCase = true)) return text
    val matches = MusePatterns.THINK_TAG_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return text
    val sb = StringBuilder(text.length)
    var lastEnd = 0
    for (m in matches) {
        sb.append(text, lastEnd, m.range.first)
        lastEnd = m.range.last + 1
    }
    sb.append(text, lastEnd, text.length)
    return sb.toString().trim()
}

/**
 * Think 标签 Transformer(Phase 8.1 H1)。
 *
 * 把 ASSISTANT 消息 content 里的 `<think>...</think>` 标签抽出到 [UIMessage.reasoning] 字段,
 * 让 UI 可以折叠展示思考链,content 只显示最终回复。
 *
 * 处理时机: 历史 ASSISTANT 消息(已持久化的)发往 LLM 前的预处理。
 * 流式响应的 thinking 处理由 Provider 直接发 ReasoningDelta,不走此 Transformer。
 *
 * 兼容:
 *  - `<think>思考</think>回复` → reasoning="思考", content="回复"
 *  - `<think>思考</think>` (无正文) → reasoning="思考", content=""
 *  - `<think></think>` (空标签) → reasoning=null, content=剩余正文(空标签也会被剥离)
 *  - 不匹配的 think 标签(无闭合) → 原样保留
 *  - 已有 reasoning 字段的 → → 跳过(不重复抽取)
 *
 * 注意: 本 Transformer 不支持嵌套 think 标签,非贪婪正则只会匹配最内层闭合标签。
 * Muse 产物不会出现嵌套,故不做状态机处理。
 *
 * 安全提示: reasoning 为原始文本,内容未净化,UI 渲染层需自行转义(防 XSS)。
 *
 * 独立编写(正则 + 字符串操作)。抽取逻辑与 MoodTagTransformer 解耦,各自实现相同 extractTag。
 */
class ThinkTagTransformer : Transformer {

    override val name: String = "ThinkTag"

    /**
     * 正则匹配 `<think>...</think>` 标签(非贪婪,跨行,忽略大小写)。
     * 用 single-line 模式(dotAll)让 . 匹配换行。
     */
    private val thinkRegex = MusePatterns.THINK_TAG_REGEX

    /**
     * 通用标签抽取:从 [content] 中找出 [regex] 匹配的所有块。
     *  - [existing] 非空时直接跳过(返回原 content),保留已有字段值
     *  - 否则一次性 findAll 收集所有匹配:无论 group 是否为空都从 content 移除标签(空标签也剥离),
     *    多块内容用换行连接,避免循环 removeRange 的 O(n*k) 拷贝
     *
     * @return Pair(extracted 值, 剥离后的新 content)
     */
    private fun extractTag(content: String, regex: Regex, existing: String?): Pair<String?, String> {
        if (existing != null) return existing to content
        val matches = regex.findAll(content).toList()
        if (matches.isEmpty()) return null to content
        // 多块内容用换行连接,空块过滤掉
        val extracted = matches
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
            .ifBlank { null }
        // 单趟构建新 content,无论 group 是否为空都移除标签
        val sb = StringBuilder(content.length)
        var lastEnd = 0
        for (m in matches) {
            sb.append(content, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        sb.append(content, lastEnd, content.length)
        return extracted to sb.toString()
    }

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg
        if (msg.reasoning != null) return@map msg  // 已有 reasoning,跳过
        if (!msg.content.contains("<think>", ignoreCase = true)) return@map msg  // 快速路径

        val (extracted, remaining) = extractTag(msg.content, thinkRegex, null)
        if (extracted == null && remaining == msg.content) {
            msg  // 没匹配到完整 think 标签,原样返回
        } else {
            msg.copy(
                reasoning = extracted,
                content = remaining.trim(),
            )
        }
    }

    /**
     * 流式视觉转换:把 ASSISTANT 消息中的 think 标签实时剥离到 reasoning 字段,
     * 让 UI 在流式过程中即可折叠展示思考链(content 只显示正文)。
     *
     * 与 [transform] 的差异:
     *  - [transform] 只处理已闭合的 `<think>...</think>`(发往 LLM 前的预处理)
     *  - [visualTransform] 额外处理流式中未闭合的 `<think>foo`(无 `</think>`),
     *    把 foo 也作为 reasoning 显示,让用户实时看到 LLM 的思考过程
     *
     * 注意: 本方法返回的是新列表(供 UI 渲染),不修改调用方持有的实际消息。
     * 已有 reasoning 字段的消息跳过(避免覆盖已抽取的内容)。
     */
    override suspend fun visualTransform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg
        if (msg.reasoning != null) return@map msg  // 已有 reasoning,跳过
        val content = msg.content
        if (!content.contains("<think>", ignoreCase = true)) return@map msg  // 快速路径

        // 1. 先抽出已闭合的 <think>...</think> 块(与 transform 一致)
        val (extractedClosed, remainingAfterClosed) = extractTag(content, thinkRegex, null)

        // 2. 流式特殊处理:检测未闭合的 <think>foo(无 </think>),
        //    把 foo 也作为 reasoning 显示,让 UI 即时展示思考过程
        val unclosedIdx = remainingAfterClosed.indexOf("<think>", ignoreCase = true)
        val extractedUnclosed: String?
        val finalContent: String
        if (unclosedIdx >= 0) {
            val unclosedReasoning = remainingAfterClosed
                .substring(unclosedIdx + "<think>".length)
                .trim()
                .ifBlank { null }
            extractedUnclosed = unclosedReasoning
            finalContent = remainingAfterClosed.substring(0, unclosedIdx)
        } else {
            extractedUnclosed = null
            finalContent = remainingAfterClosed
        }

        // 合并已闭合 + 未闭合的 reasoning
        val combinedReasoning = listOfNotNull(extractedClosed, extractedUnclosed)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
            .ifBlank { null }

        if (combinedReasoning == null && finalContent == content) {
            msg  // 没有任何可抽取内容,原样返回
        } else {
            msg.copy(
                reasoning = combinedReasoning,
                content = finalContent.trim(),
            )
        }
    }
}
