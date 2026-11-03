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

    /**
     * 单格最大长宽比。
     *
     * 真机第一次跑就暴露了这个缺口：PDF 后面几页只有文字（图床链接与说明），
     * 切格把一行行文字切成 **595x40 的横条**（长宽比近 15）当表情收了 26 条。
     * 表情基本是方的，超过这个比例就不是表情 —— 与连通块那条路径用的上限一致。
     */
    const val MAX_CELL_ASPECT = 4f

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
     * （预览 → 勾选 → 改名），而不是更强的启发式；这里只留逐格形状剔除
     * （长宽比、最小边长）与"离谱就不收"的兜底（格子数上限、面积离散度）。
     */
    /**
     * 单格最小边长（像素）。
     *
     * 真机量出来的：目录页顶上的标题文字被切成 900x30 一条。
     * 这种**逐格剔掉**就行 —— 上一版写成"任一格超标就整页不收"，
     * 结果一条标题文字把 21 张贴纸全带走了。一格坏不该毁掉整页。
     */
    const val MIN_CELL_SIDE_PX = 40

    fun plan(cells: List<StickerMontageSplitter.Cell>, pageWidth: Int, pageHeight: Int): Plan {
        val pageArea = pageWidth.toLong() * pageHeight.toLong()
        // 先逐格剔掉"明显不是表情"的形状（细长条、碎片），再对剩下的做离谱判定
        val usable = cells.filter { it.aspectRatio <= MAX_CELL_ASPECT && minOf(it.width, it.height) >= MIN_CELL_SIDE_PX }
        return when {
            usable.isEmpty() -> Plan.Skip
            usable.size > MAX_CELLS_PER_PAGE -> Plan.Skip
            !uniformEnough(usable) -> Plan.Skip
            usable.size >= 2 -> Plan.Crop(usable)
            usable.single().area >= pageArea * WHOLE_PAGE_AREA_RATIO -> Plan.Crop(usable)
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
