package io.zer0.muse.ui.markdown

/**
 * 流式增量 Markdown 块解析器(A3 计划项)。
 *
 * 背景: 流式消息每 ~80ms 追加一段文本,MarkdownText 原先每帧对全量文本
 * (随消息增长可达数千字符)重新 [parseMarkdown]。本解析器缓存已稳定的
 * 头部块,只重扫追加的尾部,并把未变化的块保持为同一实例——Compose 重组时
 * 参数未变的子 composable 会被跳过,从而降低主线程重组成本。
 *
 * 增量策略(正确性依据 — 追加点行号 = 旧文本中换行数):
 *  - 纯追加(新文本以旧文本为前缀)时,只重扫追加点所在行之后的尾部;
 *  - 末尾若存在开放块(围栏代码块/多行公式/表格,可能继续追加),
 *    从该块起始行重新扫描并丢弃其旧实例(开放块起点恒 <= 追加点行);
 *  - 追加点行之前若存在"潜在表格头"(含 | 的行,追加分隔线后会升级为表格),
 *    由重扫追加点行覆盖——追加点行即旧文本最后一行,天然包含该潜在表头;
 *  - 其余已闭合块保持原实例直接复用(稳定节点缓存)。
 *
 * 边界: 流式 token 追加在闭合标记行(``` / $$)之后必然产生换行,因此
 * "闭合标记行内追加字符"在真实流式不可达;若人工构造该输入(防御性),
 * 本解析器回退全量解析保证正确。
 *
 * 语义保证: 对任意文本按任意粒度多次追加,parse 的累计结果 == 一次全量
 * [parseMarkdown] 的结果(由 MarkdownParserTest 验证)。文本非纯追加
 * (被替换/历史加载/内容编辑)时自动回退全量解析并重置缓存。
 */
class IncrementalMarkdownParser {
    private val stableBlocks = mutableListOf<MarkdownBlock>()
    private val blockStartLines = mutableListOf<Int>()
    private var openStartLine = -1
    private var lastFullText = ""

    /**
     * 解析当前文本。流式场景应每次传入完整文本(与上次仅追加关系),内部只重扫尾部。
     */
    fun parse(text: String): List<MarkdownBlock> {
        // 纯追加且内容有变化时才走增量;否则(空文本/替换/历史/首帧/未变)全量或复用缓存
        val isAppendable = text.isNotEmpty() &&
            text.length >= lastFullText.length &&
            text.startsWith(lastFullText) &&
            text != lastFullText
        if (isAppendable) {
            val reparseStart = resolveReparseStart(text)
            if (reparseStart >= 0) {
                return reparseFrom(reparseStart, text.split(MARKDOWN_LINE_SPLIT), text)
            }
        }
        return if (text == lastFullText) stableBlocks.toList() else fullParse(text)
    }

    /**
     * 计算重扫起点行号。返回 -1 表示需回退全量(表格前向依赖)。
     *
     * 起点 = 追加内容第一个字符所在行(字符定位,规避 trailing 空行行号错位),
     * 开放块(围栏/公式/表格未闭合)存在时回溯到其起始行。
     */
    private fun resolveReparseStart(text: String): Int {
        val appendPos = lastFullText.length
        val appended = text.substring(appendPos)
        val lines = text.split(MARKDOWN_LINE_SPLIT)
        // 追加内容以换行开头时,该换行只是行分隔,第一个内容行在其后
        val firstContentChar = if (appended.startsWith('\n')) appendPos + 1 else appendPos
        val lineStart = text.lastIndexOf('\n', firstContentChar - 1) + 1
        var reparseStart = text.substring(0, lineStart).count { it == '\n' }
        if (openStartLine >= 0) reparseStart = minOf(openStartLine, reparseStart)

        // 表格前向依赖(隔行): 重扫起点前一行若是潜在表头/分隔线(含 |),
        // 该行可能因下一行升级为分隔线而变成表格头,重扫起点覆盖不到它。
        // 表格场景在流式中罕见,回退全量保证正确;普通文本不受影响。
        if (reparseStart > 0 && lines[reparseStart - 1].contains('|')) {
            return -1
        }
        // 追加点落在旧末行内(appendPos > lineStart)且该行是闭合标记行(``` / $$)时,
        // 直接重扫该行会被误判为新的开放标记 → 回溯到所属块起始行重扫。
        // 开放块起点(fence/公式开行)必须保留,否则丢失开放状态。
        if (appendPos > lineStart && reparseStart < lines.size && isClosureMarker(lines[reparseStart])) {
            if (!(openStartLine >= 0 && reparseStart == openStartLine)) {
                reparseStart = blockStartLines.lastOrNull { it < reparseStart } ?: reparseStart
            }
        }
        return reparseStart
    }

    private fun fullParse(text: String): List<MarkdownBlock> {
        val lines = text.split(MARKDOWN_LINE_SPLIT)
        val result = parseMarkdownPass(lines, 0)
        stableBlocks.clear()
        stableBlocks.addAll(result.blocks)
        blockStartLines.clear()
        blockStartLines.addAll(result.blockStartLines)
        openStartLine = result.openStartLine
        lastFullText = text
        return result.blocks
    }

    private fun reparseFrom(startLine: Int, lines: List<String>, text: String): List<MarkdownBlock> {
        // 丢弃起始行 >= startLine 的旧块(开放块/潜在表格头/追加点行),其余保持同一实例
        val firstDropped = blockStartLines.indexOfFirst { it >= startLine }
        if (firstDropped >= 0) {
            blockStartLines.subList(firstDropped, blockStartLines.size).clear()
            stableBlocks.subList(firstDropped, stableBlocks.size).clear()
        }
        val result = parseMarkdownPass(lines, startLine)
        stableBlocks.addAll(result.blocks)
        blockStartLines.addAll(result.blockStartLines)
        openStartLine = result.openStartLine
        lastFullText = text
        return stableBlocks.toList()
    }

    private fun isClosureMarker(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("```") || trimmed == "$$"
    }
}
