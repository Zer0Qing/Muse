package io.zer0.muse.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.zer0.ai.ChatService
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64 as JavaBase64

/**
 * v1.0.52 P2-4: PDF 视觉解析器。
 *
 * 用 Android 系统 [PdfRenderer] 把 PDF 每页渲染成 Bitmap,再用支持视觉的 AI 模型做 OCR,
 * 提取文字与结构。支持扫描版 PDF(图片型,本地 PDFBox 文本提取返回空时降级到此路径)。
 *
 * 设计参考 Operit examples/pdf_vision_parser.ts,关键点:
 *  - 4 路并发(避免单线程串行 OCR 大 PDF 耗时过长)
 *  - 进度回调(每页 OCR 完成回调一次,UI 可显示 "page X/Y")
 *  - 大页面降采样(targetPageWidth 限制渲染宽度,避免 OOM 与超大 base64)
 *  - 结果按页拼接,带 "--- Page N ---" 分隔符
 *
 * 安全:
 *  - PdfRenderer 只读打开 ParcelFileDescriptor,不写原文件
 *  - 渲染 Bitmap 在协程内及时 recycle,避免内存堆积
 *  - 并发上限 4,防止短时间生成 4+ Bitmap 导致 OOM
 */
class PdfVisionParser(
    private val context: Context,
    private val ocrClient: VisionOcrClient,
    private val pageConcurrency: Int = DEFAULT_PAGE_CONCURRENCY,
    private val targetPageWidth: Int = DEFAULT_TARGET_PAGE_WIDTH,
) {

    /**
     * 解析结果。
     *
     * @param success 是否全部页面成功
     * @param text 拼接后的全文(每页用 "--- Page N ---" 分隔)
     * @param pagesProcessed 实际成功 OCR 的页数
     * @param pageCount PDF 总页数
     * @param error 失败原因(success=false 时非空)
     */
    data class ParseResult(
        val success: Boolean,
        val text: String,
        val pagesProcessed: Int,
        val pageCount: Int,
        val error: String? = null,
    )

    /**
     * 解析 PDF。
     *
     * @param pdfPath PDF 文件绝对路径
     * @param onPageProgress 进度回调,(currentPage, totalPages),currentPage 从 1 开始
     * @return 解析结果
     */
    suspend fun parse(
        pdfPath: String,
        onPageProgress: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    ): ParseResult = withContext(Dispatchers.IO) {
        val file = File(pdfPath)
        if (!file.exists() || !file.isFile) {
            return@withContext ParseResult(false, "", 0, 0, "PDF 文件不存在: $pdfPath")
        }
        if (!file.canRead()) {
            return@withContext ParseResult(false, "", 0, 0, "PDF 文件不可读: $pdfPath")
        }

        // 检查视觉模型可用性(早失败,避免渲染一半才发现无视觉模型)
        if (!ocrClient.isAvailable()) {
            return@withContext ParseResult(
                success = false,
                text = "",
                pagesProcessed = 0,
                pageCount = 0,
                error = "未配置支持视觉的 AI 模型,请在设置中配置支持 vision 的模型(如 GPT-4o / Gemini / Claude)",
            )
        }

        val pfd: ParcelFileDescriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrElse {
            return@withContext ParseResult(false, "", 0, 0, "打开 PDF 失败: ${it.message}")
        }

        val renderer = PdfRenderer(pfd)
        try {
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                return@withContext ParseResult(false, "", 0, 0, "PDF 页数为 0")
            }

            // 每页 OCR 结果按 pageIndex 索引存放,保证拼接顺序与原 PDF 一致
            val results = Array<String?>(pageCount) { null }
            val errors = Array<String?>(pageCount) { null }
            val semaphore = Semaphore(pageConcurrency.coerceAtLeast(1))

            coroutineScope {
                (0 until pageCount).map { pageIndex ->
                    async {
                        semaphore.withPermit {
                            try {
                                val base64 = renderPageToBase64(renderer, pageIndex)
                                val ocrText = ocrClient.ocr(base64)
                                results[pageIndex] = ocrText
                            } catch (t: Throwable) {
                                Logger.w(TAG, "OCR 第 ${pageIndex + 1} 页失败", t)
                                errors[pageIndex] = t.message ?: t.javaClass.simpleName
                            } finally {
                                // 每页完成(无论成功失败)都回调进度
                                onPageProgress(pageIndex + 1, pageCount)
                            }
                        }
                    }
                }.awaitAll()
            }

            val pagesProcessed = results.count { it != null }
            val text = buildString {
                for (i in 0 until pageCount) {
                    val pageText = results[i]
                    if (pageText != null) {
                        appendLine("--- Page ${i + 1} ---")
                        appendLine(pageText)
                        appendLine()
                    }
                }
            }.trimEnd()

            // 全部失败 → success=false
            if (pagesProcessed == 0) {
                val firstError = errors.firstOrNull { it != null } ?: "未知错误"
                return@withContext ParseResult(
                    success = false,
                    text = "",
                    pagesProcessed = 0,
                    pageCount = pageCount,
                    error = "所有页面 OCR 失败,首张错误: $firstError",
                )
            }

            // 部分成功也算成功,但拼接错误信息到正文末尾(便于 LLM 知道哪些页失败)
            val finalText = if (pagesProcessed < pageCount) {
                val failedPages = errors.indices.filter { errors[it] != null }.joinToString(",") { (it + 1).toString() }
                "$text\n\n[注意: 第 $failedPages 页 OCR 失败,内容可能缺失]"
            } else {
                text
            }

            ParseResult(
                success = true,
                text = finalText,
                pagesProcessed = pagesProcessed,
                pageCount = pageCount,
            )
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    /**
     * 渲染指定页为 Bitmap 并转 base64。
     *
     * 降采样策略:
     *  - 计算 scale = targetPageWidth / page.width(若 page.width > targetPageWidth)
     *  - 渲染目标 Bitmap 大小 = page.width*scale × page.height*scale
     *  - 用 ARGB_8888 保证清晰度(扫描版 PDF OCR 对清晰度敏感)
     *
     * 注:可见性 internal 便于单元测试通过反射验证逻辑(实际 PdfRenderer 无法 mock)。
     */
    internal fun renderPageToBase64(renderer: PdfRenderer, pageIndex: Int): String {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = if (page.width > targetPageWidth) {
                targetPageWidth.toFloat() / page.width.toFloat()
            } else {
                1f
            }
            val bmpWidth = (page.width * scale).toInt().coerceAtLeast(1)
            val bmpHeight = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            try {
                // PdfRenderer.RENDER_MODE_FOR_DISPLAY 适合 OCR(比 FOR_PRINT 颜色淡但更快)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                return JavaBase64.getEncoder().encodeToString(bytes)
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    companion object {
        private const val TAG = "PdfVisionParser"

        /** 默认 4 路并发(Operit 同款配置,平衡速度与内存)。 */
        const val DEFAULT_PAGE_CONCURRENCY = 4

        /** 默认渲染宽度上限(像素)。A4 @ 96dpi ≈ 794px,1024 略放大保留细节。 */
        const val DEFAULT_TARGET_PAGE_WIDTH = 1024

        /** 单次 OCR 提示词(要求模型尽量完整提取文字与结构,不总结)。 */
        const val OCR_PROMPT = "尽量完整提取本页可见文字与结构,保留标题、列表、表格顺序,不做总结。" +
            "若是扫描版页面,做 OCR 还原文字。若页面为纯图片(非文字),描述图片主要内容。"
    }
}

/**
 * 视觉模型 OCR 客户端抽象。
 *
 * 抽出接口的目的:
 *  1. 单元测试可注入 fake 实现,绕开 Android PdfRenderer 与真实 LLM 调用
 *  2. 未来支持非 ChatService 的视觉模型(如本地 OCR 引擎 / 云端 OCR API)时,只需实现此接口
 */
interface VisionOcrClient {
    /** 是否配置了可用的视觉模型(早失败检查)。 */
    suspend fun isAvailable(): Boolean

    /** 对单张 base64 JPEG 图片做 OCR,返回提取的文本。 */
    suspend fun ocr(imageBase64: String): String
}

/**
 * 默认实现:基于 [ChatService] + 当前 Provider 配置中的视觉模型。
 *
 * 行为:
 *  - isAvailable(): 检查 SettingsRepository 当前 Provider 是否有 supportsVisionInput() 模型
 *  - ocr(): 构造带 imageBase64List 的 USER 消息,调用 completeText(UTILITY 模式关思考省时)
 *  - 容错:text 为空时降级用 reasoning(推理模型可能把 OCR 内容放 reasoning_content)
 */
class DefaultVisionOcrClient(
    private val chatService: ChatService,
    private val configStore: ProviderConfigStore,
) : VisionOcrClient {

    override suspend fun isAvailable(): Boolean {
        val config = configStore.get() ?: return false
        // 任意一个模型支持 vision 即可
        return config.models.any { it.supportsVisionInput() }
    }

    override suspend fun ocr(imageBase64: String): String {
        if (imageBase64.isBlank()) return "[OCR 输入为空]"

        val message = UIMessage(
            role = MessageRole.USER,
            content = PdfVisionParser.OCR_PROMPT,
            imageBase64List = listOf(imageBase64),
        )

        val completion = runCatching {
            chatService.completeText(
                messages = listOf(message),
                mode = ChatRequestMode.UTILITY,
            )
        }.getOrElse {
            return "[OCR 调用失败: ${it.message}]"
        }

        // 优先用 text;空时降级 reasoningContent(推理模型可能把 OCR 内容放 reasoning_content)
        return completion.text.takeIf { it.isNotBlank() }
            ?: completion.reasoningContent?.takeIf { it.isNotBlank() }
            ?: "[OCR 返回空内容]"
    }
}
