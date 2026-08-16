package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import java.io.File

/**
 * v1.0.52 P2-4: parse_pdf 工具入口 + 注册器。
 *
 * AI 可调用此工具对 PDF 做视觉解析(渲染每页为图片 → 视觉模型 OCR)。
 *
 * 使用场景:
 *  - 扫描版 PDF(图片型,DocumentParser 的 PDFBox 文本提取返回空)
 *  - 复杂版式 PDF(表格/公式/图文混排,文本提取顺序混乱)
 *  - 用户明确要求"看 PDF 内容"且本地文本提取不足时
 *
 * 安全:
 *  - 只读操作,不修改原文件
 *  - SAFE 风险等级(无副作用,只是耗时与 API 调用)
 *  - 页数硬上限 200(防超大 PDF 拖垮 LLM 上下文)
 *
 * 返回格式:
 *  成功: 拼接的全文(每页 "--- Page N ---" 分隔)
 *  失败: "[错误] ..." 形式的可读消息
 */
object PdfVisionSkill {

    const val NAME = "parse_pdf"

    /** 默认最大页数(防止超大 PDF 耗时过长 + 上下文爆炸)。 */
    const val DEFAULT_MAX_PAGES = 50

    /** 硬上限(用户可显式指定到 200,但不能更高)。 */
    const val HARD_MAX_PAGES = 200

    /**
     * 解析 max_pages 参数并钳制到合法范围。
     *
     * 纯逻辑,供单元测试直接验证,不依赖文件系统或 parser 调用。
     * (C-32: 把 max_pages 钳制从 execute 抽出,避免测试耦合"文件存在性检查→parser 调用"顺序。)
     *
     * @param raw 原始参数值;null/空/非数字用 [DEFAULT_MAX_PAGES];越界钳制到 [1, HARD_MAX_PAGES]。
     */
    internal fun resolveMaxPages(raw: String?): Int =
        raw?.toIntOrNull()?.coerceIn(1, HARD_MAX_PAGES) ?: DEFAULT_MAX_PAGES

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "对 PDF 文件做视觉解析(每页渲染为图片 → 视觉模型 OCR 提取文字)。" +
            "适用于扫描版 PDF(图片型)或复杂版式 PDF(表格/公式/图文混排)。" +
            "普通文字版 PDF 请优先用 read_file(若可读)或文档解析附件,本工具耗时较长。" +
            "需要配置支持 vision 的模型(GPT-4o / Gemini / Claude 等)。" +
            "返回每页 OCR 文本,用 '--- Page N ---' 分隔。",
        parameters = mapOf(
            "path" to "必填,PDF 文件绝对路径(如 '/sdcard/Download/report.pdf' 或工作区相对路径)",
            "max_pages" to "可选,最大解析页数(默认 50,硬上限 200),防止超大 PDF 耗时过长",
        ),
        required = setOf("path"),
        category = "built-in",
        parameterTypes = mapOf("max_pages" to "integer"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行 parse_pdf 工具。
     *
     * @param args 工具参数
     * @param parser PDF 视觉解析器实例
     * @param context 用于解析工作区相对路径(可选)
     * @param workspaceRoot 工作区根目录(用于解析相对路径)
     */
    suspend fun execute(
        args: Map<String, String>,
        parser: PdfVisionParser,
        context: Context,
        workspaceRoot: File,
    ): String {
        val rawPath = args["path"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return "[错误] 缺少必填参数 path"

        if (rawPath.contains("..")) return "[错误] 路径禁止包含 '..'"

        val maxPages = resolveMaxPages(args["max_pages"])

        // 解析路径:工作区相对路径 → filesDir → cacheDir → 外部 Download → 绝对路径
        val candidates = listOf(
            File(workspaceRoot, rawPath),
            File(context.filesDir, rawPath),
            File(context.cacheDir, rawPath),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), rawPath),
            File(rawPath),
        )
        val target = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return "[错误] PDF 文件不存在: $rawPath"

        // 进度日志(便于调试,UI 侧若需进度展示可后续扩展)
        var lastReported = 0L
        val result = runCatching {
            parser.parse(target.absolutePath) { current, total ->
                // 限制日志频率(每页一条即可)
                val now = System.currentTimeMillis()
                if (now - lastReported > 500 || current == total) {
                    Logger.i("PdfVisionSkill", "parse_pdf 进度: $current/$total (${target.name})")
                    lastReported = now
                }
            }
        }.getOrElse {
            Logger.w("PdfVisionSkill", "parse_pdf 异常", it)
            return "[错误] 解析异常: ${it.message}"
        }

        if (!result.success) {
            return "[错误] ${result.error ?: "解析失败"}"
        }

        // 截断到 maxPages(如果 PDF 超过 maxPages,parse 内部已处理;此处仅做最终输出截断)
        val finalText = if (result.pagesProcessed > maxPages) {
            result.text.take(estimateCharsForPages(maxPages)) +
                "\n\n[注意: 实际解析 ${result.pagesProcessed} 页,已截断到 $maxPages 页的内容量]"
        } else {
            result.text
        }

        return buildString {
            appendLine("[PDF 视觉解析成功] 文件: ${target.name}")
            appendLine("总页数: ${result.pageCount},成功解析: ${result.pagesProcessed}")
            appendLine()
            append(finalText)
        }
    }

    /** 粗略估算 N 页的字符上限(每页约 2000 字符,防止输出过长)。 */
    private fun estimateCharsForPages(pages: Int): Int = pages * 2000
}

/**
 * PdfVision 工具注册器 — 把 parse_pdf 注册到 ToolRegistry。
 *
 * 依赖 ToolRegistry + PdfVisionParser + Context + WorkspaceManager(取 rootDir)。
 */
class PdfVisionToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val parser: PdfVisionParser,
    private val context: Context,
    private val workspaceRoot: File,
) {
    init { registerAll() }

    fun registerAll() {
        toolRegistry.register(PdfVisionSkill.toolDef()) { args ->
            PdfVisionSkill.execute(args, parser, context, workspaceRoot)
        }
    }
}
