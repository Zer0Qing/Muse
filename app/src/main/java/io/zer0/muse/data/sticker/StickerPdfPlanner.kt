package io.zer0.muse.data.sticker

/**
 * 一整页 PDF 该切几刀、还是整页收（表情包重做 P5）。
 *
 * 市面素材的 PDF 有两种长法：
 *  1. **目录页**：一格一页大图里排了十几个表情（样本"自嘲熊"就是 21 格排一页）→ 要自动切；
 *  2. **单表情页**：一页就是一张表情（或带一点白边）→ 不能切，切了就是把一张完整图切碎。
 *
 * 这两种在"切出来几格"上天然可分，所以判定规则很短：
 *
 *  - 2 格以上 → 按格收；
 *  - 只有 1 格且它占了页面 **70% 以上** → 整页当一张收；
 *  - 只有 1 格但只占一小块 → 这页多半是说明文字、二维码或作者签名，**不收**；
 *  - 格子数多到不像话（>[MAX_CELLS_PER_PAGE]）→ 判为噪声，整页不收：
 *    宁可少导，也不要给用户库里塞几十张碎块。
 *
 *  实测数据（样本自嘲熊目录页，21 格）：现有切格算法只切出 9 块且面积差 132 倍，
 *  所以另加一条**格子大小必须彼此接近**的闸门，见 [MAX_AREA_SPREAD]。
 *
 * 抽成纯函数是为了能在 JVM 上把这些边界钉住 —— 真机上"这一页到底切了几刀"很难肉眼核对，
 * 而且每次调背景容差都可能悄悄改变结果。
 */
object StickerPdfPlanner {

    /** 单页最多接受多少格（表情目录页没有更密的；超过说明切碎了）。 */
    const val MAX_CELLS_PER_PAGE = 40

    /** 单格占页面比例达到多少才敢当"整页一张"收。 */
    const val WHOLE_PAGE_AREA_RATIO = 0.7f

    /** 一页的处理决定。 */
    sealed interface Plan {
        /** 按这些格裁切导入。 */
        data class Crop(val cells: List<StickerMontageSplitter.Cell>) : Plan

        /** 这页没有可导的东西。 */
        data object Skip : Plan
    }

    /**
     * 格子面积的最大离散倍数。
     *
     * 真实样本上量到的事实：一张 21 格的目录页，当前切格算法只切出 9 块，
     * 且面积从 540 到 71280 像素（差 132 倍）—— 因为多张表情被并成了一块。
     * 这种结果**导入比不导更糟**：用户得到的是几块装着半截表情的碎图。
     *
     * 所以切得"不均匀"就等于"没切准"，直接不收这一页。
     * 网格检测（按墨量谷值找列边界）留到下一期做，那时再放开这道闸。
     */
    const val MAX_AREA_SPREAD = 8f

    /**
     * 这里**不设**"每格至少占页面多少面积"的闸。
     *
     * 试过，而且是错的：贴墨裁切的格子本来就比网格槽位小得多 ——
     * 真实目录页上切准的格子中位数只占页 0.7%，而上一期判为碎片的也是 0.6%，
     * 两个数字根本分不开。用面积当质量判据只会把好结果一起误杀。
     *
     * 结论：切得准不准，**数字判不出来，只能给人看**。所以这条链的最后一环是导入向导
     * （预览 → 勾选 → 改名），而不是更强的启发式；这里只留"离谱就不收"的兜底
     * （格子数上限与面积离散度）。
     */
    fun plan(cells: List<StickerMontageSplitter.Cell>, pageWidth: Int, pageHeight: Int): Plan {
        val pageArea = pageWidth.toLong() * pageHeight.toLong()
        return when {
            cells.isEmpty() -> Plan.Skip
            cells.size > MAX_CELLS_PER_PAGE -> Plan.Skip
            !uniformEnough(cells) -> Plan.Skip
            cells.size >= 2 -> Plan.Crop(cells)
            cells.single().area >= pageArea * WHOLE_PAGE_AREA_RATIO -> Plan.Crop(cells)
            else -> Plan.Skip
        }
    }

    /** 单格永远"均匀"；多格要求面积彼此接近。 */
    fun uniformEnough(cells: List<StickerMontageSplitter.Cell>): Boolean {
        if (cells.size <= 1) return true
        val areas = cells.map { it.area.toFloat() }
        val min = areas.min()
        val max = areas.max()
        return min > 0f && max / min <= MAX_AREA_SPREAD
    }
}
