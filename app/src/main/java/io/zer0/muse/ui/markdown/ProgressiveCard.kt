package io.zer0.muse.ui.markdown

/**
 * 流式卡片的渐进渲染节流。
 *
 * ## 为什么需要「快照长度」这件事
 *
 * 富内容卡片（svg / html / chart）在流式期间也要边生成边显示，而每次把新内容交给
 * WebView 都是一次 `loadDataWithBaseURL`：重新解析整页 HTML、重新执行卡片脚本、
 * 图表还要重新初始化。这条代价与「刷新次数」成正比，与「每次多几个字符」几乎无关。
 *
 * 早先的实现按固定步长量化（每增长 120 字符就换一份内容），于是长度为 L 的卡片在
 * 生成过程中要刷新 `L / 120` 次。一张 8KB 的图表卡片就是 68 次整页重载，
 * 每次都带着同一份约 25KB 的样式与脚本 —— 这是「卡片一出现整条对话就发烫」的主要来源。
 *
 * ## 现在的口径：几何增长
 *
 * 快照长度只在**翻倍档位**上推进：`step, 2*step, 4*step, ...`。
 * 于是 8KB 的卡片只有 7 次刷新，累计交给 WebView 的 HTML 总量从
 * `Σ L_k ≈ L²/(2*step)` 降到 `≈ 2L`（约 17 倍）。
 *
 * 视觉上每次刷新带来的增量更大，但刷新点之间的间隔也更大 —— 对「正在生成」这种
 * 本来就要等的情况，连续小步刷新的收益远小于它带来的重载成本。
 *
 * ## 边界
 *
 * - 内容长度不足一个 `step` 时原样返回：短卡片不该被截成空。
 * - 快照长度永远不超过内容长度，因此永远不会返回过长前缀。
 * - 非流式场景（[progressive] = false）不经过这里，直接用完整内容。
 */
internal object ProgressiveCard {

    /** 内容短于这个长度就不做渐进渲染 —— 半句 HTML 闪一下比不显示更差。 */
    const val MIN_CHARS: Int = 24

    /**
     * 首档步长（字符）。
     *
     * 取 [MIN_CHARS] 而不是更大值：每次刷新都要重载整页（含约 25KB 的样式与脚本），
     * 代价与内容多少无关。若首档取 120，内容在 24…119 之间时「短于步长即原样返回」会让
     * 每一帧都变内容 —— 那就是最坏的一段：几十次整页重载发生在卡片刚出现的前两百毫秒里。
     * 从 24 起步、按档位翻倍，第一段只需要 3 次刷新。
     */
    const val DEFAULT_STEP: Int = MIN_CHARS

    /**
     * 算出这一帧应当渲染到第几个字符。
     *
     * @param length 当前内容长度
     * @param step 首档步长，必须为正
     * @return 快照长度，落在 `[step, 2*step, 4*step, ...]` 上且 `≤ length`；
     *         内容短于 `step` 时返回 `length`（原样）
     */
    fun snapshotLength(length: Int, step: Int = DEFAULT_STEP): Int {
        require(step > 0) { "step must be positive" }
        if (length <= step) return length
        var snap = step
        while (snap.toLong() * 2 <= length) {
            snap *= 2
        }
        return snap
    }

    /**
     * 取这一帧应当交给 WebView 的内容。
     *
     * 返回值的**同一性**和长度同样重要：内容没跨过档位时必须返回与上一次相等的前缀，
     * 否则上游 `remember(spec, ...)` 会当作内容变化，触发一次没必要的整页重载。
     * 这里返回的总是 `content.take(snapshotLength(...))`，值相等即满足 Compose 的
     * 结构化相等判断。
     */
    fun snapshot(content: String, step: Int = DEFAULT_STEP): String {
        val snap = snapshotLength(content.length, step)
        if (snap >= content.length) return content
        // 不要切在代理对中间：emoji / 生僻字是 UTF-16 的两个 char，从中间截断会得到
        // 一个孤立代理项，交给 WebView 编码后变成替换字符。退一格即可。
        val end = if (snap > 0 && Character.isHighSurrogate(content[snap - 1])) snap - 1 else snap
        return content.substring(0, end)
    }

    /**
     * 生成长度为 [length] 的内容时，总共会经历多少次快照。
     *
     * 只用于测试与账本核对：固定步长是 `length / step`，几何档位是
     * `floor(log2(length / step)) + 1`。
     */
    fun snapshotCount(length: Int, step: Int = DEFAULT_STEP): Int {
        require(step > 0) { "step must be positive" }
        if (length <= step) return 1
        var snap = step
        var count = 1
        while (snap.toLong() * 2 <= length) {
            snap *= 2
            count += 1
        }
        return count
    }
}
