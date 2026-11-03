package io.zer0.muse.data.sticker

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.common.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 真机上验证 PDF 渲染与切格（表情包重做 P5 唯一没法在 JVM 上测的一段）。
 *
 * 这条测试**只为拿数据**：`PdfRenderer` 出像素、两段式切格在真实素材上到底切出多少格，
 * 我的 Python 复刻只能算旁证 —— 渲染器不同、像素取法不同，结论必须以设备上跑
 * 生产代码为准。所以它打印得比断言多。
 *
 * 样本文件从**应用外部私有目录**读（`adb push` 到
 * `/sdcard/Android/data/io.zer0.muse/files/muse_sticker_sample.pdf`），
 * 这样不用申请存储权限，也就不会让"权限被拒"混进这次的测量结果。
 * 文件不在就直接 assumption 跳过：CI 与没推文件的机器上它不该变成红灯。
 *
 * 跑完清库，不把样本留在设备的表情包库里。
 *
 * 它是**测量工具不是回归断言**：切得准不准要人看格子，所以失败路径只记日志。
 * 一次已知结果（2026-11-04）：渲染修成按 1400 长边后，样本页被长宽比闸整页挡下
 * （以前是按 72dpi 渲染、把文字行切成 595x40 的横条收进来 26 条）。
 */
@RunWith(AndroidJUnit4::class)
public class StickerPdfDeviceTest {

    private val tag = "MusePdfCheck"

    @Test
    public fun pdfOnRealSampleReportsActualCutQuality(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sample = File(context.getExternalFilesDir(null), "muse_sticker_sample.pdf")
        assumeTrue("样本未推送到设备，跳过真机测量: ${sample.absolutePath}", sample.isFile)

        val repository = StickerLibraryRepository(context)
        try {
            logPageDiagnostics(context, sample)
            val prepared = repository.preparePdf(Uri.fromFile(sample), sample.name)
            when (prepared) {
                is Result.Error -> {
                    // 测量工具：切不合格时如实报告并跳过断言，让红灯只留给真·故障
                    Log.w(tag, "preparePdf 未产出可用格子: ${prepared.message}")
                }
                is Result.Success -> {
                    val draft = prepared.data
                    Log.i(tag, "页数上限内切出格子=${draft.items.size} 分类=${draft.category}")
                    draft.items.forEachIndexed { index, entry ->
                        Log.i(tag, "  cell[$index] file=${entry.file.name} bytes=${entry.file.length()}")
                    }
                    // 这里只断"至少切出东西且文件非空"：数量对不对由人看日志与导出的格子判断
                    assertTrue("没切出任何格子", draft.items.isNotEmpty())
                    assertTrue("不该有空文件", draft.items.none { it.file.length() <= 0L })
                }
            }
        } finally {
            repository.clearAll()
        }
    }

    /** 把切出的格子留在设备上，方便 pull 下来肉眼核对（只在样本存在时跑）。 */
    @Test
    public fun dumpCutCellsToDeviceDownloads(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sample = File(context.getExternalFilesDir(null), "muse_sticker_sample.pdf")
        assumeTrue("样本未推送", sample.isFile)

        val repository = StickerLibraryRepository(context)
        try {
            val prepared = repository.preparePdf(Uri.fromFile(sample), sample.name).getOrNull()
            if (prepared == null) {
                Log.w(tag, "解析失败，无法导出格子")
                return@runBlocking
            }
            val outDir = File(context.getExternalFilesDir(null), "cut_cells").apply { mkdirs() }
            prepared.items.forEachIndexed { index, entry ->
                entry.file.copyTo(File(outDir, "%02d_%s".format(index, entry.fileName)), overwrite = true)
            }
            Log.i(tag, "格子已留在 ${outDir.absolutePath}（共 ${prepared.items.size} 个）")
        } finally {
            repository.clearAll()
        }
    }

    /**
     * 逐页打印渲染尺寸、原始格数与形状分布。
     *
     * 这些数字是**参数的唯一来源**：合成图测试永远"我预期几个就几个"，
     * 而 PdfRenderer 的像素与我 Python 复刻不同，切格参数只能在设备上重标。
     */
    private fun logPageDiagnostics(context: Application, sample: File) {
        val descriptor = android.os.ParcelFileDescriptor
            .open(sample, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = StickerPageRenderer.open(descriptor)
        if (renderer == null) {
            Log.w(tag, "诊断跳过：PDF 打不开")
            return
        }
        renderer.use { open ->
            val pages = minOf(open.pageCount, StickerPageRenderer.MAX_PAGES)
            Log.i(tag, "诊断: 处理页数=$pages（PDF 共 ${open.pageCount} 页）")
            for (index in 0 until pages) {
                val page = open.render(index) ?: continue
                page.use { rendered ->
                    val background = StickerMontageSplitter
                        .backgroundImage(rendered.pixels, rendered.width, rendered.height)
                    val isBg: (Int) -> Boolean = { color -> StickerMontageSplitter.isBackgroundPixel(color, background) }
                    val grid = StickerGridLayout.layout(rendered.pixels, rendered.width, rendered.height, isBg)
                    val thin = grid.count { it.aspectRatio > StickerPdfPlanner.MAX_CELL_ASPECT }
                    val tiny = grid.count { minOf(it.width, it.height) < StickerPdfPlanner.MIN_CELL_SIDE_PX }
                    val plan = StickerPdfPlanner.plan(grid, rendered.width, rendered.height)
                    Log.i(
                        tag,
                        "  page[$index] ${rendered.width}x${rendered.height} 原始格=${grid.size} " +
                            "细长=$thin 过小=$tiny 判定=${plan.javaClass.simpleName}",
                    )
                    grid.take(6).forEach { cell ->
                        Log.i(tag, "    cell ${cell.width}x${cell.height} aspect=${cell.aspectRatio}")
                    }
                    dumpPageIfRequested(rendered, index)
                }
            }
        }
    }

    /**
     * 按需把渲染出来的一页存成 PNG（`-e dump 1`）。
     *
     * 目的是**离线调参**：调切格参数必须拿真 PdfRenderer 的像素 ——
     * 用合成图或别的渲染器标出来的参数换到设备上就失准（本轮已经栽过一次）。
     * 存下来 pull 走，本地就能反复试参数，Kotlin 侧只在选定后改一次。
     */
    private fun dumpPageIfRequested(page: StickerPageRenderer.RenderedPage, index: Int) {
        val wanted = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("dump")
        if (wanted != "1") return
        val context = ApplicationProvider.getApplicationContext<Application>()
        val out = File(context.getExternalFilesDir(null), "page$index.png")
        runCatching {
            FileOutputStream(out).use { stream ->
                page.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }.onSuccess { Log.i(tag, "  已存渲染页 ${out.absolutePath} ${page.width}x${page.height}") }
            .onFailure { Log.w(tag, "  存页失败: ${it.message}") }
    }
}
