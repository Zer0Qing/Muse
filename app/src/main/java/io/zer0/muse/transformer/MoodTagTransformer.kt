package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.MusePatterns

/**
 * v0.30-b: MOOD 标签 Transformer(6 步工作流第 6 步 — MOOD→正文包装)。
 *
 * 把 ASSISTANT 消息 content 里的 `<mood>...</mood>` 块抽出到 [UIMessage.mood] 字段,
 * 让 UI 可以折叠展示 AI 的"内部腹稿",content 只显示最终回复正文。
 *
 * v0.32 实验性 selfReflection 接入:同时剥离 `<reflection>...</reflection>` 块到
 * [UIMessage.reflection] 字段(只接后端,UI 渲染待后续任务)。
 *
 * 处理时机: 历史 ASSISTANT 消息(已持久化的)发往 LLM 前的预处理。
 * 流式响应的 mood / reflection 处理由 ChatViewModel 在流式累积时实时剥离(见 updateAssistant)。
 *
 * 兼容:
 *  - `<mood>Vibe: ...\nSparks: ...\n...</mood>\n正文` → mood="Vibe: ...\n...", content="正文"
 *  - `<mood>...</mood>` (无正文) → mood="...", content=""
 *  - `<mood></mood>` (空标签) → mood=null, content=剩余正文(空标签也会被剥离)
 *  - 不匹配的 mood 标签(无闭合) → 原样保留
 *  - 已有 mood 字段的 → 跳过 mood 抽取(不重复抽取)
 *  - 同理 reflection 标签的抽取规则与 mood 一致
 *
 * 注意: 本 Transformer 不支持嵌套 mood/reflection 标签(如 `<mood><mood>...</mood></mood>`),
 * 非贪婪正则只会匹配最内层闭合标签并留下孤立闭合标签。Muse 产物不会出现嵌套,故不做状态机处理。
 *
 * 安全提示: mood/reflection 为原始文本,内容未净化,UI 渲染层需自行转义(防 XSS)。
 */
class MoodTagTransformer : Transformer {

    override val name: String = "MoodTag"

    /**
     * B6-02: 流式生成完成后的兜底提取。
     *
     * finalizeResponse 走 [Transformer.onGenerationFinish] 钩子处理最终 assistant 消息。
     * 若标签跨 chunk 到达（<moodfx> 与闭合标签不在同一 delta），updateAssistant 的
     * 实时提取会因标签未闭合而漏掉；此处复用 transform 逻辑做最终兜底，确保
     * mood / moodfx / reflection 都被提取并写回消息字段。幂等：已有字段会跳过。
     */
    override suspend fun onGenerationFinish(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> = transform(messages, context)

    /**
     * 正则匹配 `<mood>...</mood>` 块(非贪婪,跨行)。
     * v1.131: 已迁移到 io.zer0.muse.util.MusePatterns.MOOD_TAG_REGEX。
     * Muse 只用 <mood> 单标签,简化解析。
     */
    private val moodRegex = MusePatterns.MOOD_TAG_REGEX

    /**
     * v0.32 实验性 selfReflection:正则匹配 `<reflection>...</reflection>` 块。
     * 命名遵循 mood 一致的设计;剥离后存入 [UIMessage.reflection]。
     */
    private val reflectionRegex = Regex("""<reflection>([\s\S]*?)</reflection>""", RegexOption.IGNORE_CASE)

    /**
     * 通用标签抽取:从 [content] 中找出 [regex] 匹配的所有块。
     *  - [existing] 非空时直接跳过(返回原 content),保留已有字段值
     *  - 否则一次性 findAll 收集所有匹配:无论 group 是否为空都从 content 移除标签(空标签也剥离),
     *    多块内容用换行连接,避免循环 removeRange 的 O(n*k) 拷贝
     *
     * @return Pair(extracted 值, 剥离后的新 content)
     */
    private fun extractTag(content: String, regex: Regex, existing: String?): Pair<String?, String> {
        val matches = regex.findAll(content).toList()
        if (existing != null && matches.isEmpty()) return existing to content
        if (matches.isEmpty()) {
            // v1.x: 兜底 — 未闭合标签(模型偶尔输出 <mood> 或 [mood] 却忘记闭合),
            // 正则无法匹配;把标签头标记字符剥掉,避免 mood 块原文展示给用户
            val stripped = content
                .replace(Regex("(?i)<(?:mood|think)>\\s*"), "")
                .replace(Regex("(?i)\\[(?:mood|think)\\]\\s*"), "")
            return null to stripped
        }
        // 多块内容用换行连接,空块过滤掉
        val extracted = matches
            .map { it.groupValues.getOrNull(1).orEmpty() }
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
        return (existing ?: extracted) to sb.toString()
    }

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg

        // ── MOOD 剥离 ──
        // 已有 mood 字段则跳过(避免重复抽取)
        val (extractedMood, contentAfterMood) = extractTag(msg.content, moodRegex, msg.mood)
        // 部分模型把内部腹稿写成 <mod>...</mod>，也按 mood 附属块处理，不能进入正文。
        val modRegex = Regex("""<(?:mod|mood)>([\s\S]*?)</(?:mod|mood)>|\[(?:mod|mood)\]([\s\S]*?)\[/\s*(?:mod|mood)\]""", RegexOption.IGNORE_CASE)
        val modMatches = modRegex.findAll(contentAfterMood).toList()
        val modContent = modMatches.mapNotNull { match ->
            match.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() }?.trim()
        }.joinToString("\n").ifBlank { null }
        val contentAfterMod = modMatches.fold(contentAfterMood) { current, match -> current.replace(match.value, "") }
        val combinedMood = listOfNotNull(extractedMood, modContent).joinToString("\n").ifBlank { null }

        // ── reflection 剥离(v0.32 实验性 selfReflection) ──
        // 已有 reflection 字段则跳过(避免重复抽取)
        // ── moodfx 剥离(B6-02,与 <mood> 腹稿完全隔离) ──
        val (extractedMoodSkin, contentAfterMoodSkin) = MoodSkinParser.extract(contentAfterMod, msg.moodSkin)

        // ── reflection 剥离(v0.32 实验性 selfReflection) ──
        val (extractedReflection, workingContent) = extractTag(contentAfterMoodSkin, reflectionRegex, msg.reflection)

        // 任意一个有抽取,或 content 被裁剪 → 生成新 msg;否则原样返回
        if (combinedMood == msg.mood && extractedMoodSkin == msg.moodSkin &&
            extractedReflection == msg.reflection && workingContent == msg.content
        ) {
            msg
        } else {
            msg.copy(
                mood = combinedMood,
                reflection = extractedReflection,
                // B6-02: 之前漏了这一行 — moodfx 被剥离后皮肤名没有写回 msg.moodSkin，
                // 导致 ChatScreen 读 lastAssistant.moodSkin 恒为 null，全屏皮肤永不显示。
                moodSkin = extractedMoodSkin,
                content = workingContent.trim(),
            )
        }
    }
}
