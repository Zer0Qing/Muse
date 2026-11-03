package io.zer0.muse.data.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 出像素与裁切的那层薄胶水（表情包重做 P5）。
 *
 * 只负责"把一页变成像素、把一格变成 PNG 字节"，**不判断该切几刀** ——
 * 判定在 [StickerPdfPlanner]（纯函数、有单测），背景估计与连通块在
 * [StickerMontageSplitter]（同样有单测）。这一层留给真机核对。
 *
 * 两处省内存的取舍：
 *  1. 每页按 [MAX_RENDER_SIDE] 限边渲染：A4 矢量页按 300dpi 会是 2480×3508，
 *     而表情图本来就几百像素，按 1400 封顶既够切格也只占 1/3 的内存；
 *  2. **一页一页渲染、用完立刻 recycle**：整份 PDF 的像素绝不一次留在内存里。
 */
internal class StickerPageRenderer private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
) : AutoCloseable {

    val pageCount: Int get() = renderer.pageCount

    /** 渲染好的一页：像素 + 尺寸，用完须 [close]。 */
    class RenderedPage(
        val bitmap: Bitmap,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
    ) : AutoCloseable {
        /** 按格子裁一刀并编成 PNG。 */
        fun pngOf(cell: StickerMontageSplitter.Cell): ByteArray? = runCatching {
            val left = cell.left.coerceIn(0, width - 1)
            val top = cell.top.coerceIn(0, height - 1)
            val right = cell.right.coerceIn(left + 1, width)
            val bottom = cell.bottom.coerceIn(top + 1, height)
            val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val out = ByteArrayOutputStream()
            val ok = cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (cropped !== bitmap) cropped.recycle()
            if (ok) out.toByteArray() else null
        }.onFailure { Logger.w(TAG, "裁切失败: ${it.message}") }
            .getOrNull()

        override fun close() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** @return null 表示这一页渲染不出来（坏页不该让整份 PDF 失败） */
    fun render(index: Int): RenderedPage? = runCatching {
        renderer.openPage(index).use { page ->
            val scale = min(1f, MAX_RENDER_SIDE / max(page.width, page.height).toFloat())
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PDF 页面本身没有背景语义：不先填白，透明底会被渲染成黑色，
            // 而黑底会让"估背景"整页判成有内容，一格都切不出来。
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            RenderedPage(bitmap, pixels, width, height)
        }
    }.onFailure { Logger.w(TAG, "渲染第 ${index + 1} 页失败: ${it.message}") }
        .getOrNull()

    override fun close() {
        // 两个都要关：PdfRenderer 不接管传入的 fd
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    /** 打开一个 PDF 并交给调用方使用；失败返回 null。 */
    companion object {
        private const val TAG = "StickerPageRenderer"

        /** 渲染长边上限：够切格，又不至于把 A4 按 300dpi 全量留在内存。 */
        const val MAX_RENDER_SIDE = 1400

        /** 一次最多处理多少页。 */
        const val MAX_PAGES = 30

        fun open(pfd: ParcelFileDescriptor): StickerPageRenderer? = runCatching {
            StickerPageRenderer(PdfRenderer(pfd), pfd)
        }.onFailure { Logger.w(TAG, "打开 PDF 失败: ${it.message}") }
            .getOrNull()
    }
}
