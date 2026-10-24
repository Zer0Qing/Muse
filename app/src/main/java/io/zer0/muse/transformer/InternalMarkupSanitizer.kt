package io.zer0.muse.transformer

/**
 * 用户不可见的内部标记清洗器。
 *
 * 模型有时会把 mood / mod / think / reflection 直接写进 content，尤其是
 * 中转模型或流式输出被截断时。内部字段负责承载这些内容，正文、会话预览和
 * 标题生成只能使用本清洗器后的文本。
 *
 * ## v2.6.8: 统一出口
 *
 * 这里同时是 `card_state` 尾巴的清洗权威。此前 card_state 只有「一个写入点、一个剥离点
 * （气泡渲染）」，其余十余处原文读者（复制/分享/转发/编辑草稿/引用/消息地图/收藏/导出/
 * 会话预览/FTS 索引）都直接读 content，于是 JSON 漏到用户眼前、也能被全文搜索命中。
 * 现在对外只提供三个语义明确的出口，调用方按用途选一个，不再各自实现剥离：
 *
 *  - [displayContent]     展示：剥内部标记块 + 卡片状态尾巴，收敛空白。
 *  - [exportContent]      复制/分享/导出/朗读：在展示口径上再剥内联特效标签。
 *  - [searchableContent]  检索：只剥卡片状态尾巴，保留 mood/think 等既有索引文本。
 *
 * [stripForDisplay] 是展示口径的底层实现（卡片状态尾巴与 mood/think 同级处理），
 * 流式落库、推理通道、标题生成等历史调用点继续用它即可，同样不会漏 card_state。
 */
object InternalMarkupSanitizer {
    /**
     * v2.6.8: 卡片状态段的起止标记（唯一权威定义）。
     *
     * 用 U+2063 INVISIBLE SEPARATOR 包裹，降低与正常文本碰撞的概率；写入侧见
     * `CardAction.appendCardStateToMessage`，格式保持不变以兼容已落库的历史消息。
     */
    const val CARD_STATE_OPEN: String = "\u2063[card_state]\u2063"
    const val CARD_STATE_CLOSE: String = "\u2063[/card_state]\u2063"

    /**
     * 卡片状态的廉价探针。
     *
     * 刻意不含不可见字符：contains 是单趟扫描，正则才需要提前短路。
     * 任何「这段文本里有没有卡片状态」的判断都先过这一关。
     */
    private const val CARD_STATE_PROBE: String = "[card_state]"

    /** v2.6.7 性能: 空白收敛正则提取为文件级常量，避免每次调用都 new 一个 Regex。 */
    private val whitespaceRun = Regex("\\n{3,}")

    private val closedInternalBlock = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])" +
            "[\\s\\S]*?" +
            "(?:</(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[/(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])",
    )
    private val unclosedInternalStart = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\]).*$",
    )
    private val moodBlock = Regex(
        "(?is)(?:<(?:mood|mod)>|\\[(?:mood|mod)\\])([\\s\\S]*?)(?:</(?:mood|mod)>|\\[/(?:mood|mod)\\])",
    )

    /**
     * v1.0.90: 标签名标记检测（含半个标签与孤立闭标签）。
     *
     * 原来调用方只检测 `<mood>` 这种完整开标签，模型吐半个标签（`<mood`）或只吐闭标签
     * （`</mood>`）时会被当成普通正文直接上屏，用户就看到 mood 标签原文。
     * 凡是命中这个标记的内容，都必须走完整清洗路径。
     */
    val TAG_MARKER_REGEX =
        Regex("(?i)[<\\[]/?\\s*(?:mood|mod|think|thinking|reflection|moodfx)")

    /** 只移除内部标签外壳，保留其中的思考内容，供 reasoning 面板使用。 */
    fun stripContainerTags(text: String): String = text
        .replace(Regex("(?i)</?(?:mood|mod|think|thinking|reflection|moodfx)>"), "")
        .replace(Regex("(?i)\\[/?(?:mood|mod|think|thinking|reflection|moodfx)\\]"), "")
        .trim()

    /** 从推理通道移除完整 mood/mod 块,但保留其中其他 think 内容。 */
    fun stripMoodBlocks(text: String): String = moodBlock.replace(text, "").trim()

    /** v2.6.8: 文本里是否带卡片状态尾巴（廉价 contains，无正则）。 */
    fun containsCardState(text: String): Boolean = text.contains(CARD_STATE_PROBE)

    /**
     * v2.6.8: 剥掉卡片状态尾巴。
     *
     * 无标记时**原样返回同一实例**（不 trim、不重建），所以可以安全地放在热路径上。
     * 有标记时剥掉整段并去掉尾部空白 —— 尾巴是追加在正文后的 `\n[card_state]…`，
     * 留着换行会让气泡多出一行空白。未闭合（只写了一半）时从标记处截断，
     * 与历史实现一致，宁可丢状态也不把 JSON 漏给用户。
     */
    fun stripCardState(text: String): String {
        if (!containsCardState(text)) return text
        var result = text
        var stripped = false
        // 循环剔除每一段尾巴（理论上只有一段，但卡片连续回传时会叠）。
        var start = result.indexOf(CARD_STATE_OPEN)
        while (start >= 0) {
            val end = result.indexOf(CARD_STATE_CLOSE, start)
            if (end < 0) {
                // 尾巴写了一半（崩溃/截断）：从标记处截断，宁丢状态不漏 JSON。
                result = result.substring(0, start)
                stripped = true
                start = -1
            } else {
                result = result.removeRange(start, end + CARD_STATE_CLOSE.length)
                stripped = true
                start = result.indexOf(CARD_STATE_OPEN)
            }
        }
        // 尾巴是追加在正文后的 `\n[card_state]…`，剥掉后会留一个尾换行；只在真剥过时收尾。
        return if (stripped) result.trimEnd() else result
    }

    /**
     * v2.6.8 出口一：可以直接展示给用户的正文。
     *
     * 等价于 [stripForDisplay]（卡片状态尾巴已在其中与 mood/think 同级处理）。
     * 单独留一个名字，是为了让调用点读起来能看出「这是给用户看的口径」，
     * 也方便以后两者分岔时不用改几十处调用。
     */
    fun displayContent(text: String): String = stripForDisplay(text)

    /**
     * v2.6.8 出口二：复制 / 分享 / 导出 / 朗读用的正文。
     *
     * 在 [displayContent] 基础上再清掉 `[glow]…[/glow]` 这类内联特效标签（保留被包裹的文字）。
     * 与展示口径的唯一差别就是这层特效壳，用户拿到的文本里不该有协议标记。
     */
    fun exportContent(text: String): String = MoodSkinParser.stripInlineEffects(displayContent(text)).trim()

    /**
     * v2.6.8 出口三：搜索索引 / 检索比对用的正文。
     *
     * 只剥卡片状态尾巴，**不动** mood/think 等内部块，也不收敛空白：
     *  - 卡片状态是机器字段（cardId、JSON 键值），既不该被用户搜到，也不该出现在结果片段里；
     *  - 用户能搜的语义已经在正文摘要里（卡片回传时的那句话），剥掉状态不丢召回；
     *  - mood/think 是否入索引是另一个决策，本次不顺手改，避免搜索行为静默漂移。
     */
    fun searchableContent(text: String): String = stripCardState(text)

    /** 返回可以直接展示给用户的正文（内部标记块 + 卡片状态尾巴）。 */
    fun stripForDisplay(text: String): String {
        // v2.6.7 性能: 快速路径。所有内部标记都以 '<' 或 '[' 开头，
        // 两者都不含时标记正则必然无法命中，只需做空白收敛。
        // 本方法在消息列表过滤里每帧被调用 N 次（N = 消息数），是主线程真实热点。
        // v2.6.8: card_state 标记以 '[' 开头，含它的文本必然走慢路径 ——
        // 所以快速路径不需要额外探针，剥离放在慢路径里，热路径成本不变。
        val hasMarkup = text.contains('<') || text.contains('[')
        val result = if (text.isBlank() || !hasMarkup) {
            text
        } else {
            stripMarkupBlocks(stripCardState(text))
        }
        return whitespaceRun.replace(result, "\n\n").trim()
    }

    /** 慢路径：跑完整标记清洗。仅在文本含 '<' 或 '[' 时调用。 */
    private fun stripMarkupBlocks(text: String): String {
        var result = closedInternalBlock.replace(text, "")
        // 未闭合的内部块从标签处截断，避免把思考内容当正文展示。
        result = unclosedInternalStart.replace(result, "")
        // v1.0.90: 上面两条只处理带开标签的情况。只吐半个标签（`<mood`）或孤立的闭标签
        // （`</mood>`、`</think>`）时会原样漏到正文，这里最后再扫一遍标签外壳。
        result = stripContainerTags(result)
        return result
    }

    /** 仅提取 mood/mod 内容，兼容历史消息和不同模型的标签习惯。 */
    fun extractMood(text: String): String? = moodBlock.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
}
