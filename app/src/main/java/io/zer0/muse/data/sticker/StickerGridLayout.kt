package io.zer0.muse.data.sticker

import kotlin.math.max

/**
 * 密集目录页的两段式切格（表情包重做 P5 第二段）。
 *
 * 为什么不是连通块：真实样本上量过——21~25 张贴纸排 6 行的目录页，
 * 连通块要么把相邻两张并成一块（空隙只有约 20px），要么把"画"和它下面的
 * "配文"切成两块，而这两种结果的**格子数与面积离散度几乎一样**，
 * 光看数字分不出来（核对图才看得出来）。
 *
 * 有效的是这段：**先按行、再在每一行内按列**找墨量低谷。
 * 理由很朴素——行与行之间一定有空白带（哪怕贴纸挨着），
 * 而"每行有几张、间距多少"各行不同，所以列必须逐行单独求，
 * 拿一份全局列剖面去套所有行必然错位。
 *
 * 参数来自真实样本的一次跑通：行带 6、格子 25、宽 62–131（中位 76）、面积差 2.6 倍，
 * 单格只占页面积 0.7%。也就是说"格子必须大到占页 1.5%"那种直觉是错的：
 * 裁切是贴着墨迹收紧的，不是网格槽位大小。
 */
object StickerGridLayout {

    /** 一行里最少几张才算"这是一目录页"。 */
    const val MIN_COLS_PER_ROW = 2

    /** 行带上限：超过就是噪声。 */
    const val MAX_ROW_BANDS = 12

    /** 一次最多接受多少格（与判定层一致，密过这个数的素材不符合现实）。 */
    const val MAX_CELLS = 40

    /** 墨量低于"该行中位墨量"的这个比例就算空白。 */
    const val LOW_INK_RATIO = 0.12f

    /** 采样步长：2px 一次，够准又不至于把 1400 页扫到慢。 */
    private const val SAMPLE_STEP = 2

    /** 单列带最小宽度（像素）：比这窄的是笔画碎片。 */
    private const val MIN_LANE_PX = 60

    /** 单行带最小高度（像素）。 */
    private const val MIN_BAND_PX = 60

    /** 取原始段时的下限：比这还窄的不算一段内容。 */
    private const val MIN_FRAGMENT_PX = 8

    /**
     * 合并只在**相对量**上做：空隙 ≤ 行高中位数的这个比例。
     *
     * 绝对像素在真机上被证伪过：设成 25px 时，真实目录页的 6 条贴纸带（高 106~132）
     * 里有四条被并成了 520px 的一整块，切出来是 75x458 的竖切片。
     * 真机原始行段是 `[46,106,114,132,130,112,28,20,16,12]` ——
     * 贴纸带都 100+，文字与配文带都 ≤46，用"相对中位数"就能干净分开：
     * 0.1 倍约 11px，正好够把配文并进上面那张，又绝不至于把两行并起来。
     */
    private const val MERGE_GAP_RELATIVE = 0.1f

    /** 只允许把"明显比中位行带矮"的带并上去（配文、印章、分隔线）。 */
    private const val MERGE_SHORT_BAND_RELATIVE = 0.5f

    /**
     * @param isBackground 由调用方提供的"这像素是不是背景"判定（背景色估计在渲染层做，
     *   那边才知道页面有没有被填白）
     */
    fun layout(
        pixels: IntArray,
        width: Int,
        height: Int,
        isBackground: (Int) -> Boolean,
    ): List<StickerMontageSplitter.Cell> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val bands = rowBands(pixels, width, height, isBackground)
        if (bands.isEmpty() || bands.size > MAX_ROW_BANDS) return emptyList()
        // 每行至少两张才算目录页（这个判定在 rowCells 里逐行做）；
        // 这里不再拿它当"总数"用 —— 一行两列 × 6 行才是我们见到的形状
        return bands.flatMap { band -> rowCells(pixels, width, height, isBackground, band) }.take(MAX_CELLS)
    }

    /** 行带：整行墨量足够高的连续区段。 */
    private fun rowBands(
        pixels: IntArray,
        width: Int,
        height: Int,
        isBackground: (Int) -> Boolean,
    ): List<IntRange> {
        val profile = ArrayList<Float>()
        val indexes = ArrayList<Int>()
        var y = 0
        while (y < height) {
            val line = y * width
            var hit = 0
            for (x in 0 until width) {
                if (!isBackground(pixels[line + x])) hit++
            }
            profile += hit.toFloat() / width
            indexes += y
            y += SAMPLE_STEP
        }
        // 先按很小的下限取原始段，合并窄空隙之后再按最小行高筛：
        // 反过来的话配文那条薄带在合并前就被丢掉了，等于没合
        val raw = runsAbove(profile, indexes, LOW_INK_RATIO, MIN_FRAGMENT_PX)
        return mergeCloseRuns(raw).filter { it.last - it.first >= MIN_BAND_PX }
    }

    /**
     * 合并被"窄空隙"分开的行带。
     *
     * 典型场景就是贴纸下面的配文：画到配文之间往往只有十几像素空白，
     * 不合并的话配文会被当成一条薄带丢掉（甚至被最小行高直接丢弃），
     * 用户拿到的表情就少了那行字 —— 而字正是表情的一半。
     */
    private fun mergeCloseRuns(runs: List<IntRange>): List<IntRange> {
        if (runs.size < 2) return runs
        val heights = runs.map { it.last - it.first }
        val medianHeight = heights.sorted().let { it[it.size / 2] }
        val gapLimit = medianHeight * MERGE_GAP_RELATIVE
        val shortLimit = medianHeight * MERGE_SHORT_BAND_RELATIVE
        val out = mutableListOf(runs.first())
        runs.drop(1).forEach { next ->
            val prev = out.last()
            val narrow = (next.last - next.first) < shortLimit
            if (narrow && next.first - prev.last <= gapLimit) {
                out[out.lastIndex] = prev.first..next.last
            } else {
                out += next
            }
        }
        return out
    }

    /** 一行内按本行墨量低谷分栏。 */
    private fun rowCells(
        pixels: IntArray,
        width: Int,
        height: Int,
        isBackground: (Int) -> Boolean,
        band: IntRange,
    ): List<StickerMontageSplitter.Cell> {
        val ink = IntArray(width)
        var y = band.first
        var sampleRows = 0
        while (y <= band.last && y < height) {
            val line = y * width
            for (x in 0 until width) {
                if (!isBackground(pixels[line + x])) ink[x]++
            }
            sampleRows++
            y += SAMPLE_STEP
        }
        val divisor = max(1, sampleRows)
        val profile = ArrayList<Float>(width)
        val indexes = ArrayList<Int>(width)
        for (x in 0 until width) {
            profile += ink[x].toFloat() / divisor
            indexes += x
        }
        val lanes = runsAbove(profile, indexes, LOW_INK_RATIO, MIN_LANE_PX)
        if (lanes.size < MIN_COLS_PER_ROW) return emptyList()
        return lanes.take(MAX_CELLS).map { lane ->
            StickerMontageSplitter.Cell(
                left = lane.first.coerceAtLeast(0),
                top = band.first,
                right = lane.last.coerceAtMost(width),
                bottom = band.last,
            )
        }
    }

    /**
     * 把剖面里"高于中位数比例"的连续段收成区间。
     *
     * 用中位数而不是最大值做基准：目录页各行的整体浓淡差别很大，
     * 按最大值归一会把淡那一行整行判成空白。
     */
    private fun runsAbove(profile: List<Float>, indexes: List<Int>, ratio: Float, minSpan: Int): List<IntRange> {
        val positive = profile.filter { it > 0f }
        if (positive.isEmpty()) return emptyList()
        val median = positive.sorted().let { it[it.size / 2] }
        val threshold = median * ratio
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in profile.indices) {
            if (profile[i] > threshold) {
                if (start < 0) start = i
            } else if (start >= 0) {
                span(indexes, start, i - 1, minSpan)?.let { out += it }
                start = -1
            }
        }
        if (start >= 0) span(indexes, start, profile.lastIndex, minSpan)?.let { out += it }
        return out
    }

    /** 太窄的一段直接丢掉（返回 null），不要塞一个 -1..-1 的假区间进结果。 */
    private fun span(indexes: List<Int>, from: Int, to: Int, minSpan: Int): IntRange? {
        val lo = indexes[from]
        val hi = indexes[to] + SAMPLE_STEP
        return if (hi - lo >= minSpan) lo..hi else null
    }
}
