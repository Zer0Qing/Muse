package io.zer0.muse.data.sticker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 把"整页格子图"切成一张张表情（表情包重做 P1）。
 *
 * 为什么需要它：样本 PDF 的第 1 页是**一张** 1206×1448 的整页大图，21 个表情排在里面。
 * 按"图片"取的话只会得到一张目录图，而用户要的是 21 张能直接发的表情。
 *
 * 做法是纯像素算法，不依赖 Android，因此能在 JVM 上直接测 —— 切歪这种事只有测出来才算数：
 *
 *  1. 用**四条边**估背景色（素材页几乎都是白底或透明底）；
 *  2. 按小块下采样成"有没有内容"的粗网格；
 *  3. 粗网格上跑连通块（8 邻域），每块的外接框就是一个表情；
 *  4. 太小（角标水印）与太细长（标题行、分割线）丢掉；
 *  5. 万一格子连成一片（贴纸互相贴着、背景偏色），退回**投影切分**：
 *     按行、列的内容空隙切格，取有内容的交叉格；只有一个方向分得开也照切。
 *
 * 切得不完美是可以接受的：导入向导里用户可以勾掉、可以改名。
 * 但"把整页当一张导进来"不能接受 —— 那等于没支持这种格式。
 */
object StickerMontageSplitter {

    /** 估计页面背景色（取四边一圈的众数，返回真实颜色而非量化键）。 */
    fun backgroundImage(pixels: IntArray, width: Int, height: Int): Int = guessBackground(pixels, width, height)

    /** 某个像素是否算背景（透明像素一律算背景）。 */
    fun isBackgroundPixel(color: Int, background: Int): Boolean = isBackground(color, background)

    /** 切出的格子：像素坐标，右/下为开区间。 */
    data class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Int get() = width * height

        /** 长宽比（>=1）。细长条通常是文字行或分隔线，不是表情。 */
        val aspectRatio: Float
            get() {
                val short = min(width, height).coerceAtLeast(1)
                return max(width, height).toFloat() / short
            }
    }

    /** 格子最小边长：低于这个多半是水印或角标（样本水印是 106×32）。 */
    const val MIN_CELL_SIDE = 64

    /** 最大长宽比：超过就是标题行/分割线。 */
    const val MAX_ASPECT_RATIO = 4f

    /** 单格面积占整页比例下限。 */
    const val MIN_AREA_RATIO = 0.002f

    /** 最多切多少格：超出来一般是噪点碎块。 */
    const val MAX_CELLS = 300

    /** 与背景色的最大通道差，小于它算背景。 */
    private const val COLOR_TOLERANCE = 40

    /** 块内非背景像素占比达到多少才算"有内容"。 */
    private const val BLOCK_CONTENT_RATIO = 0.04f

    fun split(pixels: IntArray, width: Int, height: Int): List<Cell> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val block = max(8, min(width, height) / 96)
        val columns = (width + block - 1) / block
        val rows = (height + block - 1) / block
        val grid = Grid(contentGrid(pixels, width, height, block, columns, rows), columns, rows, block, width, height)
        return grid.pickCells()
    }

    /** 粗网格：每个块里非背景像素够多才算有内容。 */
    private fun contentGrid(pixels: IntArray, width: Int, height: Int, block: Int, columns: Int, rows: Int): BooleanArray {
        val sampler = Sampler(pixels, width, height, block, guessBackground(pixels, width, height))
        val flags = BooleanArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                flags[row * columns + column] = sampler.hasContent(column * block, row * block)
            }
        }
        return flags
    }

    /**
     * 一个块内的采样。
     *
     * 像素、尺寸、块大小、背景色本来就是一套上下文，收成一个小类比给函数挂八个参数好读
     * （八个参数已经被静态检查点名了）。
     */
    private class Sampler(
        private val pixels: IntArray,
        private val width: Int,
        private val height: Int,
        private val block: Int,
        private val background: Int,
    ) {
        private val step: Int = max(1, block / 4)

        fun hasContent(x0: Int, y0: Int): Boolean {
            val x1 = min(x0 + block, width)
            val y1 = min(y0 + block, height)
            var hit = 0
            var seen = 0
            var y = y0
            while (y < y1) {
                val line = y * width
                var x = x0
                while (x < x1) {
                    seen++
                    if (!isBackground(pixels[line + x], background)) hit++
                    x += step
                }
                y += step
            }
            return seen > 0 && hit.toFloat() / seen >= BLOCK_CONTENT_RATIO
        }
    }

    /** 粗网格 + 页面尺寸。收成一处是为了不把六个参数传给每个 helper。 */
    private class Grid(
        val flags: BooleanArray,
        val columns: Int,
        val rows: Int,
        val block: Int,
        val pageWidth: Int,
        val pageHeight: Int,
    ) {

        /** 优先用连通块；只剩一大块或一块都没留下时退回投影切分。 */
        fun pickCells(): List<Cell> {
            val kept = connectedCells().filter { isPlausible(it) }
            return if (kept.isNotEmpty() && !mergedIntoOne(kept)) {
                kept.readingOrder()
            } else {
                projectionCells().filter { isPlausible(it) || kept.isEmpty() }.readingOrder()
            }
        }

        /** 连通块（8 邻域）→ 外接框。 */
        fun connectedCells(): List<Cell> {
            val visited = BooleanArray(flags.size)
            val cells = mutableListOf<Cell>()
            flags.indices.filter { flags[it] && !visited[it] }.forEach { start ->
                val box = flood(start, visited)
                cells += toCell(box[0], box[1], box[2], box[3])
            }
            return cells
        }

        /** 投影切分：按行/列的内容空隙切格，取有内容的交叉格。 */
        fun projectionCells(): List<Cell> {
            val bandRows = runsOf(rows) { row -> (0 until columns).any { flags[row * columns + it] } }
            val laneColumns = runsOf(columns) { column -> (0 until rows).any { flags[it * columns + column] } }
            // 只有一个方向分得开也要切：例如三行贴纸行内互贴、列间没空隙，
            // 这时按"整幅一条列带"处理，仍能得三格而不是放弃
            val bands = when {
                bandRows.size >= 2 -> bandRows
                laneColumns.size >= 2 -> listOf(0 to rows - 1)
                else -> return emptyList()
            }
            val lanes = when {
                laneColumns.size >= 2 -> laneColumns
                bandRows.size >= 2 -> listOf(0 to columns - 1)
                else -> return emptyList()
            }
            return cellsAt(bands, lanes)
        }

        fun isPlausible(cell: Cell): Boolean {
            if (cell.width < MIN_CELL_SIDE || cell.height < MIN_CELL_SIDE) return false
            val long = max(cell.width, cell.height).toFloat()
            if (long / min(cell.width, cell.height) > MAX_ASPECT_RATIO) return false
            return cell.area >= pageWidth * pageHeight * MIN_AREA_RATIO
        }

        /** 只剩一块且占了大半个页面：多半是格子连到一起了。 */
        fun mergedIntoOne(cells: List<Cell>): Boolean {
            val only = cells.singleOrNull() ?: return cells.isEmpty()
            return only.area > pageWidth * pageHeight * 0.5f
        }

        private fun cellsAt(bands: List<Pair<Int, Int>>, lanes: List<Pair<Int, Int>>): List<Cell> {
            val cells = mutableListOf<Cell>()
            bands.forEach { (rowFrom, rowTo) ->
                lanes.forEach { (columnFrom, columnTo) ->
                    if (hasContentIn(rowFrom, rowTo, columnFrom, columnTo)) {
                        cells += toCell(columnFrom, columnTo, rowFrom, rowTo)
                    }
                }
            }
            return cells
        }

        private fun hasContentIn(rowFrom: Int, rowTo: Int, columnFrom: Int, columnTo: Int): Boolean =
            (rowFrom..rowTo).any { row -> (columnFrom..columnTo).any { column -> flags[row * columns + column] } }

        private fun flood(start: Int, visited: BooleanArray): IntArray {
            val queue = ArrayDeque<Int>().apply { add(start) }
            var minColumn = columns
            var maxColumn = -1
            var minRow = rows
            var maxRow = -1
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val row = index / columns
                val column = index % columns
                minColumn = min(minColumn, column)
                maxColumn = max(maxColumn, column)
                minRow = min(minRow, row)
                maxRow = max(maxRow, row)
                neighboursOf(row, column).forEach { (nRow, nColumn) ->
                    val next = nRow * columns + nColumn
                    if (flags[next] && !visited[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
            }
            return intArrayOf(minColumn, maxColumn, minRow, maxRow)
        }

        private fun neighboursOf(row: Int, column: Int): List<Pair<Int, Int>> =
            listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
                .map { (dr, dc) -> row + dr to column + dc }
                .filter { (r, c) -> r in 0 until rows && c in 0 until columns }

        private fun toCell(minColumn: Int, maxColumn: Int, minRow: Int, maxRow: Int): Cell = Cell(
            left = (minColumn * block - block / 2).coerceAtLeast(0),
            top = (minRow * block - block / 2).coerceAtLeast(0),
            right = ((maxColumn + 1) * block + block / 2).coerceAtMost(pageWidth),
            bottom = ((maxRow + 1) * block + block / 2).coerceAtMost(pageHeight),
        )
    }

    /** 把 [0, size) 里连续为真的索引收成闭区间段。 */
    private fun runsOf(size: Int, at: (Int) -> Boolean): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (index in 0 until size) {
            if (at(index)) {
                if (start < 0) start = index
            } else if (start >= 0) {
                runs += start to index - 1
                start = -1
            }
        }
        if (start >= 0) runs += start to size - 1
        return runs
    }

    private fun List<Cell>.readingOrder(): List<Cell> = sortedWith(compareBy({ it.top / 64 }, { it.left })).take(MAX_CELLS)

    /**
     * 背景色：取四边一圈的众数。
     *
     * 量化到 16 级是为了让众数不被抗锯齿抖动打散；但**返回值必须是真实颜色** ——
     * 之前把量化键直接返回，调用方拿它当 ARGB 比较，
     * 于是白色页面被判成"每个像素都不是背景"，一格都切不出来（测试抓到的）。
     */
    private fun guessBackground(pixels: IntArray, width: Int, height: Int): Int {
        val counts = HashMap<Int, Int>()
        val sample = HashMap<Int, Int>()
        fun bump(index: Int) {
            val color = pixels[index]
            val key = quantize(color)
            counts[key] = (counts[key] ?: 0) + 1
            if (!sample.containsKey(key)) sample[key] = color
        }
        for (x in 0 until width) {
            bump(x)
            bump((height - 1) * width + x)
        }
        for (y in 0 until height) {
            bump(y * width)
            bump(y * width + width - 1)
        }
        val key = counts.maxByOrNull { it.value }?.key ?: return -1
        return sample[key] ?: -1
    }

    private fun isBackground(color: Int, background: Int): Boolean {
        if (color ushr 24 == 0) return true
        return channelDelta(color, background, 16) <= COLOR_TOLERANCE &&
            channelDelta(color, background, 8) <= COLOR_TOLERANCE &&
            channelDelta(color, background, 0) <= COLOR_TOLERANCE
    }

    private fun channelDelta(first: Int, second: Int, shift: Int): Int = abs((first ushr shift and 0xFF) - (second ushr shift and 0xFF))

    private fun quantize(color: Int): Int = (color ushr 16 and 0xFF) / 16 shl 8 or
        ((color ushr 8 and 0xFF) / 16 shl 4) or
        ((color and 0xFF) / 16)
}
