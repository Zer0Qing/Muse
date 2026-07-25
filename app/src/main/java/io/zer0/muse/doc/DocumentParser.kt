package io.zer0.muse.doc

import android.content.Context
import android.net.Uri
import androidx.core.text.HtmlCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import io.zer0.muse.R

/**
 * Phase 5-E + Phase 8.6: 文档解析器。
 *
 * 支持格式:
 *  - text 子类型(txt/md/csv/json/xml/html/log):直接读纯文本
 *  - application/pdf: 用 PDFBox-Android 提取文本(Phase 8.6 升级,真正文本提取)
 *  - docx(office open xml): 解析 word/document.xml 提取 <w:t> 文本
 *  - pptx: 解析 ppt/slides/slide*.xml 提取 <a:t> 文本
 *  - xlsx: 解析 xl/sharedStrings.xml + xl/worksheets/sheet*.xml 提取单元格文本(v1.133 新增)
 *  - epub: ZIP + OPF spine + XHTML 标签剥离
 *  - 图片(image 子类型): 调用方走 OcrManager,本类不处理
 *
 * 体积控制: 文本截断到 1MB(避免超大文件 OOM)。
 *
 * 合规: 全部代码独立编写,未复制第三方实现(同类方案用 Apache POI,本项目用自实现 OOXML 解析)。
 */
class DocumentParser {

    companion object {
        /** L-DP6: 文本截断上限(1MB),粗略估算 chars*2 ≈ bytes。 */
        private const val MAX_TEXT_BYTES = 1 shl 20

        /** H-DP1: ZIP 单 entry 解压上限(4MB),防 ZIP 炸弹。 */
        private const val ZIP_MAX_BYTES = 4 * (1 shl 20)
    }

    /** PDFBox 需要一次性 init(加载字体资源)。 */
    private val pdfBoxInited = AtomicBoolean(false)

    /**
     * M-DP2: 解析文档,返回 [Result]。
     * 成功携带纯文本(可能为空串);失败返回 [Result.Error] 携带可读消息。
     * @param uri 内容 URI(SAF 返回)
     * @param context 用于 ContentResolver + PDFBox init
     *
     * 错误消息已提取至 strings_data.xml 资源,readXxx 方法内用 context.getString() 获取。
     * 截断标记(如"[… 文件过大,已截断 …]")是文本结构的一部分,不属于 UI 文案。
     */
    fun parseResult(uri: Uri, context: Context): Result<String> {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: guessMimeFromName(uri.toString())
        return resultOf {
            when {
                mimeType == null -> readAsText(uri, context)
                mimeType.startsWith("text/") -> readAsText(uri, context)
                mimeType == "application/pdf" -> readPdf(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    readDocx(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    readPptx(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    readXlsx(uri, context)
                mimeType == "application/epub+zip" -> readEpub(uri, context)
                // 兜底:按扩展名再试一次
                else -> {
                    val lower = uri.toString().lowercase()
                    when {
                        lower.endsWith(".pdf") -> readPdf(uri, context)
                        lower.endsWith(".docx") -> readDocx(uri, context)
                        lower.endsWith(".pptx") -> readPptx(uri, context)
                        lower.endsWith(".xlsx") -> readXlsx(uri, context)
                        lower.endsWith(".epub") -> readEpub(uri, context)
                        else -> readAsText(uri, context)
                    }
                }
            }
        }
    }

    /**
     * 解析文档,返回纯文本。
     *
     * M-DP2: 旧调用方兼容入口。失败时返回空串(而非错误文案),由调用方按 isBlank 校验;
     * 新调用方应改用 [parseResult] 以获取失败原因。
     *
     * @param uri 内容 URI(SAF 返回)
     * @param context 用于 ContentResolver + PDFBox init
     * @return 提取的文本;失败或空内容返回空串
     */
    fun parse(uri: Uri, context: Context): String =
        parseResult(uri, context).getOrNull() ?: ""

    /**
     * 读取纯文本文件(txt/md/csv/json/xml/html)。1MB 截断防 OOM。
     *
     * M-DP3: 编码检测——
     *  1. 读前 3 字节嗅探 BOM:UTF-8(EF BB BF)/ UTF-16 LE(FF FE)/ UTF-16 BE(FE FF)
     *  2. 无 BOM 则尝试 UTF-8 严格解码;失败则按 GBK 兜底(中文 Windows 常见编码)
     *  3. GBK 也失败则回退 UTF-8 宽松解码(替换非法字节)
     */
    private fun readAsText(uri: Uri, context: Context): String {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            // 先读全部字节(限 MAX_TEXT_BYTES,防超大文件 OOM)
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            var n = input.read(buf)
            while (n > 0) {
                total += n
                if (total > MAX_TEXT_BYTES) {
                    baos.write(buf, 0, n - (total - MAX_TEXT_BYTES))
                    break
                }
                baos.write(buf, 0, n)
                n = input.read(buf)
            }
            val bytes = baos.toByteArray()
            val truncated = total > MAX_TEXT_BYTES
            val charset = detectCharset(bytes)
            val text = String(bytes, charset).trim()
            return if (truncated) {
                "$text\n\n[… 文件过大,已截断。原文件超过 1MB,仅读取前 1MB 内容 …]"
            } else {
                text
            }
        }
        return ""
    }

    /**
     * M-DP3: 嗅探字节序列的字符编码。
     * - 有 BOM:按 BOM 返回对应 charset
     * - 无 BOM:尝试 UTF-8 严格解码,失败则返回 GBK
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        // BOM 嗅探
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE
        }
        // 无 BOM:尝试 UTF-8 严格解码
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            StandardCharsets.UTF_8
        }.getOrElse {
            // UTF-8 解码失败,按 GBK 兜底(GBK 几乎不会对任意字节报错);
            // GBK 不可用则回退 UTF-8(后续 String 构造会替换非法字节)
            runCatching { Charset.forName("GBK") }.getOrElse { StandardCharsets.UTF_8 }
        }
    }

    /**
     * Phase 8.6: PDF 文本提取(用 PDFBox-Android)。
     *
     * Phase 11.2.3 增强:复杂版式处理(L7)
     *  - 文档元数据(标题/作者/主题/关键词/创建时间)→ 前置 "[文档信息]" 块
     *  - 文档目录(PDOutline 递归)→ 前置 "[目录]" 块(最多 2 层)
     *  - stripper 参数:sortByPosition + 段落分隔(空行强化段落)
     *  - 页眉/页脚过滤:跨页重复(>=3 页)的首/尾行视为页眉页脚,后处理剔除
     *
     * PDFBox-Android 是 Apache PDFBox 的 Android 移植版,支持:
     *  - 真正文本提取(PDFTextStripper,基于 PDF 文本对象)
     *  - 加密 PDF 解密(若已知密码)
     *  - 不依赖原生 PdfRenderer(无 Bitmap 中转)
     *
     * 局限:扫描型 PDF(图片型)文本提取会返回空,需走 OCR。
     */
    private fun readPdf(uri: Uri, context: Context): String {
        ensurePdfBoxInited(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                val maxPages = 200
                val end = minOf(total, maxPages)

                // Step 1: 逐页提取文本(保留页面分隔符便于页眉页脚过滤)
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                // 段落分隔:PDFBox 默认按缩进检测段落,这里用空行强化段落分隔
                stripper.paragraphStart = "\n\n"
                stripper.paragraphEnd = "\n\n"
                stripper.wordSeparator = " "

                val pageTexts = mutableListOf<String>()
                for (i in 1..end) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val pageText = runCatching { stripper.getText(doc) }
                        .getOrNull() ?: continue
                    pageTexts.add(pageText)
                }

                // Step 2: 页眉/页脚过滤(跨页重复的首/尾行)
                val cleanedTexts = filterHeadersFooters(pageTexts)

                // Step 3: 合并正文 + 截断
                val sb = StringBuilder()
                cleanedTexts.forEach { pageText ->
                    sb.append(pageText)
                    if (sb.length * 2 > MAX_TEXT_BYTES) {
                        sb.appendLine()
                        sb.appendLine("[… PDF 内容过长,已截断 …]")
                        return@forEach
                    }
                }
                // L-DP8: 删除冗余条件(sb.length 已由上面 forEach 截断控制,此处仅判断页数)
                if (total > maxPages) {
                    sb.appendLine()
                    sb.appendLine("[… PDF 共 $total 页,仅提取前 $maxPages 页 …]")
                }

                // Step 4: 前置元数据 + 目录(若有)
                val meta = extractPdfMetadata(doc)
                val toc = extractPdfOutline(doc)
                val prefix = buildString {
                    if (meta.isNotBlank()) {
                        appendLine("[文档信息]")
                        appendLine(meta)
                        appendLine()
                    }
                    if (toc.isNotBlank()) {
                        appendLine("[目录]")
                        appendLine(toc)
                        appendLine()
                    }
                }

                val bodyText = sb.toString().trim()
                val text = if (prefix.isNotBlank()) "$prefix$bodyText" else bodyText
                return if (text.isBlank()) {
                    context.getString(R.string.doc_err_pdf_text_empty, total)
                } else {
                    text
                }
            }
        }
        return context.getString(R.string.doc_err_open_pdf)
    }

    /**
     * Phase 11.2.3: 提取 PDF 元数据(PDDocumentInformation)。
     *
     * PDF 元数据字段:Title / Author / Subject / Keywords / Creator / Producer / CreationDate。
     * 仅返回非空字段,格式 "字段: 值"。
     */
    private fun extractPdfMetadata(doc: PDDocument): String {
        return runCatching {
            val info: PDDocumentInformation? = doc.documentInformation
            val sb = StringBuilder()
            info ?: return@runCatching ""
            listOf(
                "标题" to info.title,
                "作者" to info.author,
                "主题" to info.subject,
                "关键词" to info.keywords,
                "创建者" to info.creator,
                "生成器" to info.producer,
            ).forEach { (label, value) ->
                if (!value.isNullOrBlank()) {
                    sb.appendLine("$label: $value")
                }
            }
            // 创建时间(可选)
            runCatching {
                val cal = info.creationDate
                if (cal != null) {
                    sb.appendLine("创建时间: ${cal.time}")
                }
            }
            sb.toString().trim()
        }.getOrDefault("")
    }

    /**
     * Phase 11.2.3: 递归提取 PDF 目录(PDOutlineItem)。
     *
     * 限制:
     *  - 最多 2 层深度(避免深层目录刷屏)
     *  - 最多 100 条(超长目录截断)
     *  - 仅取 title,不解析 destination(目标页码需解析 PDPageDestination,
     *    复杂度高,且对纯文本提取价值不大,留作扩展)
     */
    private fun extractPdfOutline(doc: PDDocument): String {
        return runCatching {
            val outline: PDDocumentOutline? = doc.documentCatalog?.documentOutline
            outline ?: return@runCatching ""
            val sb = StringBuilder()
            var count = 0
            val maxItems = 100
            fun walk(item: PDOutlineItem?, depth: Int) {
                if (item == null || count >= maxItems) return
                val title = item.title
                if (!title.isNullOrBlank()) {
                    val indent = if (depth == 0) "" else "  ".repeat(depth)
                    sb.appendLine("$indent• $title")
                    count++
                }
                // 递归子节点(最多 2 层)
                if (depth < 1) {
                    var child = item.firstChild
                    while (child != null && count < maxItems) {
                        walk(child, depth + 1)
                        child = child.nextSibling
                    }
                }
            }
            var top = outline.firstChild
            while (top != null && count < maxItems) {
                walk(top, 0)
                top = top.nextSibling
            }
            if (count >= maxItems) {
                sb.appendLine("… (目录过长,已截断) …")
            }
            sb.toString().trim()
        }.getOrDefault("")
    }

    /**
     * Phase 11.2.3: 页眉/页脚过滤。
     *
     * 启发式:对每页文本取首 2 行 + 末 2 行,统计跨页重复次数。
     * 重复 >= 3 页的行视为页眉/页脚,从所有页面中剔除。
     *
     * 局限:
     *  - 短文档(< 3 页)不会触发过滤(保留全部内容)
     *  - 页码每页不同(如 "1/10"、"2/10")不会被识别为重复,保留
     *  - 对章节标题误判风险:若某章节首行跨多页重复(罕见),会被误删
     */
    private fun filterHeadersFooters(pageTexts: List<String>): List<String> {
        if (pageTexts.size < 3) return pageTexts

        // 统计每页首/尾行的出现次数
        val lineCounts = mutableMapOf<String, Int>()
        val candidates = mutableListOf<List<String>>()  // 每页的候选行(首2+尾2)
        pageTexts.forEach { pageText ->
            val lines = pageText.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                candidates.add(emptyList())
                return@forEach
            }
            val head = lines.take(2)
            val tail = lines.takeLast(2)
            val candidate = (head + tail).distinct()
            candidates.add(candidate)
            candidate.forEach { line ->
                lineCounts[line] = (lineCounts[line] ?: 0) + 1
            }
        }

        // 重复 >= 3 页的视为页眉页脚
        val headerFooterLines = lineCounts.filter { it.value >= 3 }.keys

        if (headerFooterLines.isEmpty()) return pageTexts

        // 从每页文本中剔除这些行(整行匹配,避免误删段落内文本)
        return pageTexts.map { pageText ->
            pageText.lines().filter { line ->
                line.isBlank() || !headerFooterLines.contains(line)
            }.joinToString("\n")
        }
    }

    /** PDFBox-Android 需在首次使用前 init(加载内置字体资源)。 */
    private fun ensurePdfBoxInited(context: Context) {
        if (pdfBoxInited.compareAndSet(false, true)) {
            // v1.25: 失败时回滚标志并记录日志,允许后续重试
            runCatching { PDFBoxResourceLoader.init(context) }
                .onFailure { e ->
                    pdfBoxInited.set(false)
                    Logger.w("DocumentParser", "PDFBox 初始化失败", e)
                }
        }
    }

    /**
     * Phase 8.6: DOCX 文本提取(自实现 OOXML 解析)。
     *
     * DOCX 是 ZIP 包,主要文件:
     *  - word/document.xml: 文档主体
     *
     * 文本结构:
     *  - <w:p>: 段落
     *  - <w:t>: 文本片段
     *  - <w:tab/>: 制表符
     *  - <w:br/>: 软换行
     *  - <w:cr/>: 硬回车
     *
     * 解析策略: 遍历 document.xml,按 <w:p> 切段,合并 <w:t> 文本,
     * 遇 <w:tab>/<w:br>/<w:cr> 转 \t/\n,段落间换行。
     */
    private fun readDocx(uri: Uri, context: Context): String {
        val xml = readZipEntry(context, uri, "word/document.xml")
            ?: return context.getString(R.string.doc_err_docx_no_document)
        val sb = StringBuilder()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inParagraph = false
        val paragraphText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "p" -> {  // 注意:OOXML 默认命名空间,本地名即 p/t/tab/br/cr
                            inParagraph = true
                            paragraphText.setLength(0)
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (inParagraph) paragraphText.append(text)
                        }
                        "tab" -> if (inParagraph) paragraphText.append('\t')
                        "br", "cr" -> if (inParagraph) paragraphText.append('\n')
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && inParagraph) {
                        inParagraph = false
                        if (paragraphText.isNotEmpty()) {
                            sb.appendLine(paragraphText.toString())
                        }
                        // M-DP4: 每段结束后检查累计长度,超 1MB 即截断并 break
                        if (sb.length * 2 > MAX_TEXT_BYTES) {
                            sb.appendLine("[… DOCX 内容过长,已截断 …]")
                            break
                        }
                    }
                }
            }
            event = parser.next()
        }
        return truncate(sb.toString().trim())
    }

    /**
     * Phase 8.6: PPTX 文本提取(自实现 OOXML 解析)。
     *
     * PPTX 是 ZIP 包,主要文件:
     *  - ppt/slides/slide1.xml, slide2.xml, ... : 各页幻灯片
     *
     * 文本结构:
     *  - <a:p>: 段落
     *  - <a:t>: 文本片段
     *  - <a:br>: 软换行
     *
     * 解析策略: 按文件名排序遍历 slide{N}.xml,每页输出 "— Slide N —" 分隔,
     * 合并 <a:t> 文本。
     */
    private fun readPptx(uri: Uri, context: Context): String {
        val slideNames = listZipEntries(context, uri)
            .filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
            .sortedBy { name ->
                name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0
            }
        if (slideNames.isEmpty()) return context.getString(R.string.doc_err_pptx_no_slides)
        val sb = StringBuilder()
        for ((idx, slideName) in slideNames.withIndex()) {
            val xml = readZipEntry(context, uri, slideName) ?: continue
            sb.appendLine("— Slide ${idx + 1} —")
            val parser = newSecurePullParser()
            parser.setInput(xml.reader())
            var inParagraph = false
            val paragraphText = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p" -> {
                                inParagraph = true
                                paragraphText.setLength(0)
                            }
                            "t" -> {
                                val text = parser.nextText()
                                if (inParagraph) paragraphText.append(text)
                            }
                            "br" -> if (inParagraph) paragraphText.append('\n')
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "p" && inParagraph) {
                            inParagraph = false
                            if (paragraphText.isNotEmpty()) {
                                sb.appendLine(paragraphText.toString())
                            }
                        }
                    }
                }
                event = parser.next()
            }
            sb.appendLine()
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… PPTX 内容过长,已截断 …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /**
     * v1.133: XLSX 文本提取(自实现 OOXML 解析,无 Apache POI 依赖)。
     *
     * XLSX 是 ZIP 包,主要文件:
     *  - xl/sharedStrings.xml: 共享字符串表(所有唯一字符串,按出现顺序索引)
     *  - xl/worksheets/sheet1.xml, sheet2.xml, ... : 各工作表
     *
     * sharedStrings 结构:
     *  - <sst><si><t>字符串</t></si>...</sst>
     *  - <si> 可能含多个 <t>(富文本格式),应合并
     *
     * worksheet 结构:
     *  - <worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>
     *  - <c> 标签的 t 属性:
     *    - 不存在或 "n":数字,<v> 是数字
     *    - "s":共享字符串,<v> 是 sharedStrings 索引
     *    - "inlineStr":内联字符串,<is><t>值</t></is>
     *    - "str":公式字符串,<v> 是公式计算结果
     *    - "b":布尔,<v> 是 0/1
     *    - "e":错误,<v> 是错误字符串
     *
     * 解析策略:
     *  1. 读 sharedStrings.xml,解析所有 <si> 到 List<String>(按出现顺序)
     *  2. 按文件名数字排序遍历 sheet{N}.xml,每页输出 "— Sheet N —" 分隔
     *  3. 逐 <row> 解析,逐 <c> 输出值(制表符分隔),行末换行
     *
     * 简化点:
     *  - 不解析 workbook.xml + rels 取 sheet 名称(按 sheetN.xml 文件名排序,纯文本提取场景足够)
     *  - 公式单元格只取 <v> 计算结果,不取 <f> 公式本身
     *  - 单元格坐标(r 属性)只用于定位,不用于补空列(纯文本场景丢空列可接受)
     */
    private fun readXlsx(uri: Uri, context: Context): String {
        // 1. 读共享字符串表(可能不存在 — 全数字表)
        val sharedStrings = parseSharedStrings(readZipEntry(context, uri, "xl/sharedStrings.xml"))

        // 2. 列出所有 sheet 文件,按数字排序
        val sheetNames = listZipEntries(context, uri)
            .filter { it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
            .sortedBy { name ->
                name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0
            }
        if (sheetNames.isEmpty()) return context.getString(R.string.doc_err_xlsx_no_sheets)

        val sb = StringBuilder()
        for ((idx, sheetName) in sheetNames.withIndex()) {
            val xml = readZipEntry(context, uri, sheetName) ?: continue
            sb.appendLine("— Sheet ${idx + 1} —")
            parseWorksheet(xml, sharedStrings, sb)
            sb.appendLine()
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… XLSX 内容过长,已截断 …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /**
     * v1.133: 解析 xl/sharedStrings.xml — 返回 <si> 顺序对应的字符串列表。
     *
     * 每个 <si> 可能含一个或多个 <t>(富文本格式),合并所有 <t> 文本。
     * 解析失败返回空列表(纯数字表可能无此文件)。
     *
     * 注:可见性 internal 供单元测试直接调用(parseResult 走 ContentResolver 不便测试)。
     */
    internal fun parseSharedStrings(xml: String?): List<String> {
        if (xml.isNullOrBlank()) return emptyList()
        val list = mutableListOf<String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inSi = false
        val siText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "si" -> {
                            inSi = true
                            siText.setLength(0)
                        }
                        "t" -> if (inSi) {
                            // nextText 会消费 START_TAG + TEXT + END_TAG,自动定位到 END_TAG 之后
                            siText.append(parser.nextText())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si" && inSi) {
                        inSi = false
                        list.add(siText.toString())
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /**
     * v1.133: 解析 xl/worksheets/sheetN.xml — 提取 <row><c> 单元格值,追加到 [sb]。
     *
     * 每个 <row> 输出一行,单元格之间用 \t 分隔。
     * <c> 标签按 t 属性解析:
     *  - "s":查 sharedStrings 表
     *  - "inlineStr":读 <is><t>
     *  - 其他/无 t:读 <v> 原值(数字/公式结果/布尔/错误)
     *
     * 注:可见性 internal 供单元测试直接调用。
     */
    internal fun parseWorksheet(xml: String, sharedStrings: List<String>, sb: StringBuilder) {
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inRow = false
        var inCell = false
        var cellType = ""           // <c t="..."> 的 t 属性
        var inInlineStr = false     // 是否在 <is> 内
        val rowCells = mutableListOf<String>()
        val cellBuffer = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "row" -> {
                            inRow = true
                            rowCells.clear()
                        }
                        "c" -> {
                            inCell = true
                            cellType = ""
                            cellBuffer.setLength(0)
                            // 读 t 属性
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i) == "t") {
                                    cellType = parser.getAttributeValue(i) ?: ""
                                }
                            }
                        }
                        "is" -> if (inCell) inInlineStr = true
                        "v" -> if (inCell) {
                            cellBuffer.append(parser.nextText())
                        }
                        "t" -> if (inCell && inInlineStr) {
                            cellBuffer.append(parser.nextText())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    when (name) {
                        "is" -> inInlineStr = false
                        "c" -> {
                            if (inCell) {
                                // 根据 cellType 转换 cellBuffer 内容
                                val raw = cellBuffer.toString()
                                val display = when {
                                    raw.isEmpty() -> ""
                                    cellType == "s" -> {
                                        // 共享字符串:raw 是索引
                                        val idx = raw.toIntOrNull()
                                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx]
                                        else raw
                                    }
                                    cellType == "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    // inlineStr / str / n / e / 无 t:直接用 raw
                                    else -> raw
                                }
                                rowCells.add(display)
                                inCell = false
                                cellType = ""
                                cellBuffer.setLength(0)
                            }
                        }
                        "row" -> {
                            if (inRow && rowCells.isNotEmpty()) {
                                sb.appendLine(rowCells.joinToString("\t"))
                            }
                            inRow = false
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Phase 8.6: EPUB 文本提取(自实现)。
     *
     * EPUB 是 ZIP 包,结构:
     *  - META-INF/container.xml: 指向 OPF 文件
     *  - *.opf: 书目清单(spine 标签定义阅读顺序)
     *  - *.xhtml / *.html: 内容文件
     *
     * 解析策略:
     *  1. 从 container.xml 读 rootfile@full-path 找 OPF
     *  2. 从 OPF 读 spine <itemref idref=...> 取阅读顺序
     *  3. 从 manifest <item id=... href=... media-type=...> 取 id → href 映射
     *  4. 按 spine 顺序读每个 XHTML,剥离 HTML 标签输出文本
     */
    private fun readEpub(uri: Uri, context: Context): String {
        // 1. 读 container.xml 找 OPF 路径
        val containerXml = readZipEntry(context, uri, "META-INF/container.xml")
            ?: return context.getString(R.string.doc_err_epub_no_container)
        val opfPath = parseContainerForOpfPath(containerXml)
            ?: return context.getString(R.string.doc_err_epub_no_opf)
        val opfDir = opfPath.substringBeforeLast('/', "")

        // 2. 读 OPF,解析 manifest 与 spine
        val opfXml = readZipEntry(context, uri, opfPath)
            ?: return context.getString(R.string.doc_err_epub_missing_opf, opfPath)
        val manifest = parseOpfManifest(opfXml)  // id → href
        val spine = parseOpfSpine(opfXml)  // idref 顺序列表

        // 3. 按 spine 顺序读 XHTML 并剥离标签
        val sb = StringBuilder()
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            // M-DP5: 路径拼接后规范化,处理 href 中的 "../" 避免越界或匹配失败
            val path = resolveZipPath(opfDir, href)
            val xhtml = readZipEntry(context, uri, path) ?: continue
            val text = stripHtmlTags(xhtml)
            if (text.isNotBlank()) {
                sb.appendLine(text.trim())
                sb.appendLine()
            }
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… EPUB 内容过长,已截断 …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /** 从 container.xml 解析 OPF 路径(rootfile@full-path)。 */
    private fun parseContainerForOpfPath(xml: String): String? {
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                // namespace 可能是 "urn:oasis:names:tc:opendocument:xmlns:container"
                // 但本地名即 rootfile,full-path 属性可能无命名空间
                for (i in 0 until parser.attributeCount) {
                    val attrName = parser.getAttributeName(i)
                    if (attrName == "full-path") {
                        return parser.getAttributeValue(i)
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    /** 从 OPF 解析 manifest:<item id=... href=... media-type=.../>。 */
    private fun parseOpfManifest(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                var id: String? = null
                var href: String? = null
                for (i in 0 until parser.attributeCount) {
                    when (parser.getAttributeName(i)) {
                        "id" -> id = parser.getAttributeValue(i)
                        "href" -> href = parser.getAttributeValue(i)
                    }
                }
                if (id != null && href != null) {
                    map[id] = href
                }
            }
            event = parser.next()
        }
        return map
    }

    /** 从 OPF 解析 spine:<itemref idref=.../> 顺序列表。 */
    private fun parseOpfSpine(xml: String): List<String> {
        val list = mutableListOf<String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "itemref") {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == "idref") {
                        list.add(parser.getAttributeValue(i))
                        break
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /** 剥离 HTML/XHTML 标签,只保留文本;换行处理 <p>/<br>/<div>。 */
    private fun stripHtmlTags(html: String): String {
        val sb = StringBuilder()
        val parser = newSecurePullParser()
        // 宽松模式:HTML 可能不规范
        try {
            parser.setInput(html.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> sb.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text
                        if (text.isNotBlank()) sb.append(text)
                    }
                }
                event = parser.next()
            }
        } catch (e: Throwable) {
            // v1.73: XML 解析失败:回退 HtmlCompat 解码(完整 HTML 实体支持)。
            // L-DP9: 旧实现仅解码 nbsp/amp/lt/gt 四种实体,中文/特殊符号实体(&copy; &hellip; 等)丢失。
            Logger.w("DocumentParser", "XML 解析失败,回退 HtmlCompat 剥离标签", e)
            return runCatching {
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }.getOrElse {
                // HtmlCompat 也失败:最终回退纯正则
                html.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        }
        return sb.toString()
    }

    /** L-DP6: 1MB 文本截断(粗略估算 chars*2 ≈ bytes)。 */
    private fun truncate(text: String): String {
        return if (text.length * 2 > MAX_TEXT_BYTES) {
            text.take(MAX_TEXT_BYTES / 2) + "\n\n[… 内容超过 1MB,已截断 …]"
        } else {
            text
        }
    }

    /**
     * L-DP7: 创建安全的 XmlPullParser,禁用外部实体防 XXE。
     *
     * Android 默认 KXmlParser 不下载外部实体(安全),但显式 setFeature 更稳妥;
     * kxml2 不支持 external-entities feature,runCatching 忽略不支持的情况,
     * 依赖默认安全行为。
     */
    private fun newSecurePullParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newPullParser()
    }

    /**
     * M-DP5: 规范化 ZIP entry 路径,处理 href 中的 "../"。
     * 用 java.nio.file.Paths.normalize() 解析后转回正斜杠(ZIP entry 统一用 '/')。
     */
    private fun resolveZipPath(baseDir: String, href: String): String {
        if (baseDir.isEmpty()) return href
        val resolved = Paths.get(baseDir, href).normalize()
        return resolved.toString().replace('\\', '/')
    }

    /** 列出 ZIP 中所有 entry 名。 */
    private fun listZipEntries(context: Context, uri: Uri): List<String> {
        val names = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    names.add(entry.name)
                    zis.closeEntry()
                }
            }
        }
        return names
    }

    /**
     * 读取 ZIP 中指定 entry 的内容(字符串)。
     *
     * H-DP1: 防 ZIP 炸弹——手动读取到 ByteArrayOutputStream 并检查累计字节数,
     * 超 [ZIP_MAX_BYTES] (4MB) 抛 [IOException],避免 zis.readBytes() 无上限解压导致 OOM。
     */
    private fun readZipEntry(context: Context, uri: Uri, entryName: String): String? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == entryName) {
                        val baos = ByteArrayOutputStream()
                        val buf = ByteArray(8 * 1024)
                        var total = 0
                        var n = zis.read(buf)
                        while (n > 0) {
                            total += n
                            if (total > ZIP_MAX_BYTES) {
                                throw IOException("ZIP entry '$entryName' 解压超限(${ZIP_MAX_BYTES} 字节),疑似 ZIP 炸弹")
                            }
                            baos.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                        return String(baos.toByteArray(), Charsets.UTF_8)
                    }
                    zis.closeEntry()
                }
            }
        }
        return null
    }

    /** 从 URI 路径猜测 MIME 类型(兜底,部分 SAF 返回 null)。 */
    private fun guessMimeFromName(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") ||
                lower.endsWith(".csv") || lower.endsWith(".json") ||
                lower.endsWith(".xml") || lower.endsWith(".html") ||
                lower.endsWith(".htm") || lower.endsWith(".log") -> "text/plain"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".pptx") ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".xlsx") ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".epub") -> "application/epub+zip"
            else -> null
        }
    }
}
