package io.zer0.muse.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.theme.MuseDateFormats
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话导出格式枚举(扩展版,在原 Markdown 基础上新增 HTML / PDF)。
 *
 * - [MARKDOWN]:Markdown 纯文本(便于复制粘贴到笔记软件)
 * - [HTML]:单文件 HTML,内联 CSS + base64 图片,可直接用浏览器打开分享
 * - [PDF]:PDF 文档,Android 原生 [PdfDocument] 渲染,A4 分页
 */
enum class ExportFormat {
    MARKDOWN, HTML, PDF
}

/**
 * 对话导出器:负责把一组 [UIMessage] 渲染为 Markdown / HTML / PDF。
 *
 * 设计要点:
 *  - 纯函数式 + 只接收 messages + chatTitle,不依赖 ViewModel / 数据库
 *    (调用方负责加载完整消息列表,见 ChatViewModel 中的包装方法)
 *  - Markdown 导出复用现有 [ChatExportCoordinator] 的格式约定(角色加粗 + 空行分隔)
 *  - HTML 生成单文件,内联 CSS(iOS 聊天气泡风格,适配深浅色),图片用 base64 内联,
 *    代码块用 highlight.js CDN(离线时退化为纯 <pre> 标签)
 *  - PDF 用 [PdfDocument] + [Canvas.drawText],A4 分页,页眉(标题 + 日期)+ 页脚(页码)
 *
 * 注:本类不持有状态,所有方法均可安全在 IO 线程调用。
 */
object ConversationExporter {

    /** A4 宽度(PostScript points,1pt = 1/72 inch)。 */
    private const val PAGE_WIDTH = 595

    /** A4 高度(PostScript points)。 */
    private const val PAGE_HEIGHT = 842

    /** 页面四周边距(pt)。 */
    private const val PAGE_MARGIN = 36f

    /** PDF 字号:标题(pt)。 */
    private const val SIZE_TITLE = 16f

    /** PDF 字号:正文(pt)。 */
    private const val SIZE_BODY = 12f

    /** PDF 字号:时间/页脚(pt)。 */
    private const val SIZE_META = 10f

    /** PDF 每页大约保留的行数(用于分页估算,实际以 Y 坐标判断为准)。 */
    private const val APPROX_LINES_PER_PAGE = 50

    /**
     * 导出为 Markdown 文本。
     *
     * 复用现有 [io.zer0.muse.ui.chat.ChatExportCoordinator.exportSessionAsMarkdown] 的格式约定:
     *  - 顶部 # 标题 + 空行
     *  - 每条消息:**角色**: 内容 + 空行
     *  - 思考过程(若有)以 ### 思考过程 单独成段
     *
     * @param messages 完整消息列表(按时间顺序)
     * @param chatTitle 会话标题
     * @return Markdown 字符串
     */
    fun exportToMarkdown(messages: List<UIMessage>, chatTitle: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(chatTitle).append("\n\n")
        sb.append("> 导出时间: ")
            .append(SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()).format(Date()))
            .append("  \n\n")

        messages.forEach { msg ->
            val role = roleLabel(msg.role)
            sb.append("**").append(role).append("**: ").append(msg.content).append("\n\n")
            // 思考过程(若有)
            if (!msg.reasoning.isNullOrBlank()) {
                sb.append("### 思考过程\n\n").append(msg.reasoning).append("\n\n")
            }
        }
        return sb.toString()
    }

    /**
     * 导出为单文件 HTML(内联 CSS + base64 图片 + highlight.js CDN)。
     *
     * 样式特征:
     *  - iOS 聊天气泡风格(用户右对齐蓝色气泡,助手左对齐灰色气泡)
     *  - 通过 @media (prefers-color-scheme) 适配深浅色
     *  - 代码块用 <pre><code> 包裹,引入 highlight.js CDN(离线时退化为纯等宽字体)
     *  - 图片以 base64 data URI 内联(便于单文件分享)
     *
     * @param messages 完整消息列表
     * @param chatTitle 会话标题
     * @return 完整 HTML 字符串(含 <!DOCTYPE html> 头)
     */
    fun exportToHtml(messages: List<UIMessage>, chatTitle: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.append("<title>").append(escapeHtml(chatTitle)).append("</title>")
        // highlight.js CDN(无网络时退化为浏览器默认等宽字体,不影响阅读)
        sb.append("<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/github.min.css\">")
        sb.append("<script src=\"https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js\"></script>")
        sb.append("<style>").append(CSS).append("</style></head><body>")

        // 顶部标题区
        sb.append("<header class=\"chat-header\">")
        sb.append("<h1>").append(escapeHtml(chatTitle)).append("</h1>")
        sb.append("<p class=\"meta\">导出时间: ")
            .append(escapeHtml(
                SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()).format(Date())
            ))
            .append("</p></header>")

        // 消息列表
        sb.append("<main class=\"chat-list\">")
        messages.forEach { msg ->
            val isUser = msg.role == MessageRole.USER
            val bubbleClass = if (isUser) "bubble user" else "bubble assistant"
            sb.append("<div class=\"").append(bubbleClass).append("\">")
            // 角色标签
            sb.append("<div class=\"role\">").append(escapeHtml(roleLabel(msg.role))).append("</div>")
            // 消息正文(渲染为段落 + 代码块)
            sb.append("<div class=\"content\">").append(renderContentHtml(msg.content)).append("</div>")
            // 内联图片(USER 上传的本地图片 / ASSISTANT 生成的图片)
            if (msg.imageBase64List.isNotEmpty()) {
                msg.imageBase64List.forEach { b64 ->
                    sb.append("<img class=\"inline-img\" alt=\"image\" src=\"data:image/jpeg;base64,")
                        .append(b64).append("\" />")
                }
            }
            // 远程图片 URL(直接引用,不内联)
            if (msg.imageUrls.isNotEmpty()) {
                msg.imageUrls.forEach { url ->
                    sb.append("<img class=\"inline-img\" alt=\"image\" src=\"")
                        .append(escapeAttr(url)).append("\" />")
                }
            }
            // 思考过程(折叠)
            val reasoningText = msg.reasoning
            if (!reasoningText.isNullOrBlank()) {
                sb.append("<details class=\"reasoning\"><summary>思考过程</summary><div class=\"content\">")
                    .append(renderContentHtml(reasoningText))
                    .append("</div></details>")
            }
            // 时间戳
            if (msg.createdAt > 0) {
                sb.append("<div class=\"time\">")
                    .append(escapeHtml(
                        SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()).format(Date(msg.createdAt))
                    ))
                    .append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</main>")

        // 启动 highlight.js
        sb.append("<script>if(window.hljs){document.querySelectorAll('pre code').forEach(b=>hljs.highlightElement(b));}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * 导出为 PDF 文件。
     *
     * 渲染策略:
     *  - 使用 [PdfDocument] 起始页,A4 尺寸(595 x 842 pt)
     *  - 每页绘制页眉(标题 + 日期)与页脚(页码)
     *  - 消息以"角色标签 + 正文 + 时间"块为单位顺序排版
     *  - 正文用 [StaticLayout] 自动换行;当 Y 坐标超过页底时新建一页
     *  - 每页约 50 行(由 [APPROX_LINES_PER_PAGE] 估算,实际以坐标判断)
     *
     * @param context Android Context(用于读取 cacheDir)
     * @param messages 完整消息列表
     * @param chatTitle 会话标题
     * @return 已写入的 PDF 文件(位于 cacheDir/export/)
     */
    fun exportToPdf(context: Context, messages: List<UIMessage>, chatTitle: String): File {
        val pdf = PdfDocument()

        // 共用画笔
        val bodyPaint = TextPaint().apply {
            color = 0xFF222222.toInt()
            textSize = SIZE_BODY
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val rolePaint = TextPaint().apply {
            color = 0xFF0A6CFF.toInt()
            textSize = SIZE_BODY
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val metaPaint = TextPaint().apply {
            color = 0xFF888888.toInt()
            textSize = SIZE_META
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        // 页眉中的会话标题用 16pt 加粗(符合"标题 16sp"规格)
        val titlePaint = TextPaint().apply {
            color = 0xFF1A1A1A.toInt()
            textSize = SIZE_TITLE
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        // 页眉/页脚的次要信息(日期、页码)用 10pt
        val headerPaint = TextPaint().apply {
            color = 0xFF555555.toInt()
            textSize = SIZE_META
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val contentWidth = PAGE_WIDTH - PAGE_MARGIN * 2
        val exportTime = SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()).format(Date())

        // 正文区域上下界(留出页眉页脚空间)
        val topY = PAGE_MARGIN + 24f
        val bottomLimit = PAGE_HEIGHT - PAGE_MARGIN - 18f

        // 开启第一页 + 绘制页眉
        var pageIndex = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create())
        var canvas = page.canvas
        drawHeader(canvas, chatTitle, exportTime, titlePaint, headerPaint)
        drawFooter(canvas, pageIndex, metaPaint)
        var y = topY

        for (msg in messages) {
            // 角色标签:放不下则换页
            if (y + SIZE_BODY * 1.6f > bottomLimit) {
                val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                pageIndex++
                page = np; canvas = nc; y = topY
            }
            canvas.drawText(roleLabel(msg.role), PAGE_MARGIN, y + SIZE_BODY, rolePaint)
            y += SIZE_BODY * 1.6f

            // 正文:用 StaticLayout 算出换行后的所有行,逐行绘制并按需换页
            val layout = StaticLayout.Builder.obtain(
                msg.content, 0, msg.content.length, bodyPaint, contentWidth.toInt()
            ).setLineSpacing(0f, 1.2f).build()

            val lineHeight = if (layout.lineCount > 0) layout.getLineBottom(0) - layout.getLineTop(0) else SIZE_BODY * 1.2f
            for (i in 0 until layout.lineCount) {
                // 当前页放不下此行 → 换页
                if (y + lineHeight.toFloat() > bottomLimit) {
                    val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                    pageIndex++
                    page = np; canvas = nc; y = topY
                }
                val lineText = layout.text.subSequence(
                    layout.getLineStart(i), layout.getLineEnd(i)
                ).toString()
                // drawText 的 y 参数是基线位置 = 顶部 + ascent 的绝对值
                canvas.drawText(lineText, PAGE_MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                y += lineHeight.toFloat()
            }

            // 时间戳
            if (msg.createdAt > 0) {
                if (y + SIZE_META * 1.8f > bottomLimit) {
                    val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                    pageIndex++
                    page = np; canvas = nc; y = topY
                }
                val timeText = SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault())
                    .format(Date(msg.createdAt))
                canvas.drawText(timeText, PAGE_MARGIN, y + SIZE_META, metaPaint)
                y += SIZE_META * 1.8f
            }

            // 消息间隔
            y += SIZE_BODY * 0.5f
        }

        pdf.finishPage(page)

        // 输出到 cacheDir/export/
        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
        val safeName = sanitizeFileName(chatTitle) + "-" +
            SimpleDateFormat(MuseDateFormats.FILE_TIMESTAMP, Locale.US).format(Date()) + ".pdf"
        val outFile = File(exportDir, safeName)
        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()
        return outFile
    }

    /**
     * 结束当前页 + 开启新页 + 绘制新页的页眉页脚,返回新 (Page, Canvas)。
     *
     * PdfDocument 规则:必须先 finishPage 上一页才能 startPage 新页;
     * 页眉/页脚在新页开启后立即绘制(每页都带页眉页脚)。
     */
    private fun turnPage(
        pdf: PdfDocument,
        currentPage: PdfDocument.Page,
        currentPageIndex: Int,
        title: String,
        exportTime: String,
        titlePaint: Paint,
        timePaint: Paint,
        footerPaint: Paint,
    ): Pair<PdfDocument.Page, Canvas> {
        pdf.finishPage(currentPage)
        val newPageIndex = currentPageIndex + 1
        val newPage = pdf.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, newPageIndex).create()
        )
        val newCanvas = newPage.canvas
        drawHeader(newCanvas, title, exportTime, titlePaint, timePaint)
        drawFooter(newCanvas, newPageIndex, footerPaint)
        return newPage to newCanvas
    }

    // ===== 私有辅助方法 =====

    /** 获取角色中文标签。 */
    private fun roleLabel(role: MessageRole): String = when (role) {
        MessageRole.USER -> "用户"
        MessageRole.ASSISTANT -> "助手"
        MessageRole.SYSTEM -> "系统"
        MessageRole.TOOL -> "工具"
    }

    /**
     * 绘制页眉(标题 + 日期 + 分隔线)。
     *
     * @param titlePaint 会话标题画笔(16pt 加粗)
     * @param timePaint 日期/分隔线画笔(10pt)
     */
    private fun drawHeader(
        canvas: Canvas,
        title: String,
        exportTime: String,
        titlePaint: Paint,
        timePaint: Paint,
    ) {
        // 顶部标题(16pt 加粗,超过 40 字截断)
        val titleText = if (title.length > 40) title.take(40) + "…" else title
        canvas.drawText(titleText, PAGE_MARGIN, PAGE_MARGIN + SIZE_TITLE, titlePaint)
        // 右侧导出日期(10pt)
        val timeWidth = timePaint.measureText(exportTime)
        canvas.drawText(
            exportTime,
            PAGE_WIDTH - PAGE_MARGIN - timeWidth,
            PAGE_MARGIN + SIZE_META,
            timePaint,
        )
        // 标题下分隔线
        canvas.drawLine(
            PAGE_MARGIN, PAGE_MARGIN + SIZE_TITLE + 4f,
            PAGE_WIDTH - PAGE_MARGIN, PAGE_MARGIN + SIZE_TITLE + 4f, timePaint,
        )
    }

    /** 绘制页脚页码。 */
    private fun drawFooter(canvas: Canvas, pageIndex: Int, paint: Paint) {
        val text = "$pageIndex"
        val width = paint.measureText(text)
        canvas.drawText(
            text,
            (PAGE_WIDTH - width) / 2f,
            PAGE_HEIGHT - PAGE_MARGIN + 4f,
            paint,
        )
    }

    /** 把消息正文渲染为 HTML 段落 + 代码块。 */
    private fun renderContentHtml(content: String): String {
        if (content.isBlank()) return ""
        val sb = StringBuilder()
        // 拆分代码块(```...```)
        val codeBlockRegex = Regex("""```(\w*)\n?([\s\S]*?)```""")
        var lastEnd = 0
        codeBlockRegex.findAll(content).forEach { match ->
            // 代码块前的普通文本
            if (match.range.first > lastEnd) {
                val plain = content.substring(lastEnd, match.range.first)
                sb.append(plainToParagraphs(plain))
            }
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
            val langClass = if (lang.isNotBlank()) " class=\"language-$lang\"" else ""
            sb.append("<pre><code$langClass>").append(escapeHtml(code)).append("</code></pre>")
            lastEnd = match.range.last + 1
        }
        // 末尾剩余普通文本
        if (lastEnd < content.length) {
            sb.append(plainToParagraphs(content.substring(lastEnd)))
        }
        return sb.toString()
    }

    /** 把普通文本按空行分段,每段包一个 <p>。 */
    private fun plainToParagraphs(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.split(Regex("""\n{2,}""")).joinToString("") { para ->
            val safe = escapeHtml(para).replace("\n", "<br>")
            "<p>$safe</p>"
        }
    }

    /** 转义 HTML 文本内容(& < >)。 */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** 转义 HTML 属性值(额外转义引号)。 */
    private fun escapeAttr(s: String): String =
        escapeHtml(s).replace("\"", "&quot;")

    /** 把会话标题转为安全文件名。 */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").take(40).ifBlank { "muse" }

    /** HTML 内联 CSS(iOS 聊天气泡风格 + 深浅色适配)。 */
    private val CSS = """
        :root {
            color-scheme: light dark;
        }
        * { box-sizing: border-box; }
        body {
            margin: 0;
            padding: 24px 16px 60px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Helvetica Neue", Arial, sans-serif;
            font-size: 15px;
            line-height: 1.6;
            background: #f5f5f7;
            color: #1a1a1a;
        }
        .chat-header {
            text-align: center;
            margin-bottom: 24px;
            padding-bottom: 16px;
            border-bottom: 1px solid rgba(0, 0, 0, 0.08);
        }
        .chat-header h1 {
            margin: 0 0 6px;
            font-size: 22px;
            font-weight: 600;
        }
        .chat-header .meta {
            margin: 0;
            font-size: 12px;
            color: #888;
        }
        .chat-list {
            max-width: 760px;
            margin: 0 auto;
            display: flex;
            flex-direction: column;
            gap: 14px;
        }
        .bubble {
            max-width: 80%;
            padding: 12px 16px;
            border-radius: 18px;
            box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        .bubble.user {
            align-self: flex-end;
            background: #0a6cff;
            color: #ffffff;
            border-bottom-right-radius: 4px;
        }
        .bubble.assistant {
            align-self: flex-start;
            background: #ffffff;
            color: #1a1a1a;
            border: 1px solid rgba(0, 0, 0, 0.05);
            border-bottom-left-radius: 4px;
        }
        .bubble .role {
            font-size: 12px;
            font-weight: 600;
            margin-bottom: 4px;
            opacity: 0.85;
        }
        .bubble .content p {
            margin: 0 0 8px;
        }
        .bubble .content p:last-child {
            margin-bottom: 0;
        }
        .bubble .content pre {
            margin: 8px 0;
            padding: 12px;
            background: rgba(0, 0, 0, 0.06);
            border-radius: 10px;
            overflow-x: auto;
            font-size: 13px;
            font-family: "SF Mono", Menlo, Consolas, monospace;
        }
        .bubble.user .content pre {
            background: rgba(255, 255, 255, 0.18);
        }
        .bubble .content code {
            font-family: "SF Mono", Menlo, Consolas, monospace;
        }
        .bubble .time {
            margin-top: 6px;
            font-size: 11px;
            opacity: 0.6;
            text-align: right;
        }
        .bubble.assistant .time {
            text-align: left;
        }
        .inline-img {
            display: block;
            max-width: 100%;
            border-radius: 10px;
            margin: 8px 0 0;
        }
        .reasoning {
            margin-top: 8px;
            font-size: 13px;
            opacity: 0.85;
        }
        .reasoning summary {
            cursor: pointer;
            font-weight: 500;
            opacity: 0.75;
        }
        .reasoning .content {
            margin-top: 6px;
            padding: 8px 10px;
            background: rgba(0, 0, 0, 0.04);
            border-radius: 8px;
        }
        @media (prefers-color-scheme: dark) {
            body {
                background: #000000;
                color: #f2f2f2;
            }
            .chat-header {
                border-bottom-color: rgba(255, 255, 255, 0.12);
            }
            .bubble.assistant {
                background: #1c1c1e;
                color: #f2f2f2;
                border-color: rgba(255, 255, 255, 0.08);
            }
            .bubble .content pre {
                background: rgba(255, 255, 255, 0.08);
            }
            .bubble.user .content pre {
                background: rgba(255, 255, 255, 0.18);
            }
            .reasoning .content {
                background: rgba(255, 255, 255, 0.06);
            }
        }
    """.trimIndent()
}
