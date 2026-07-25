package io.zer0.muse.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.common.resultOf

// L-MD15 修复: 行号 gutter 宽度计算常量(原先 10dp/16dp 硬编码在 CodeBlockView 内)
// 每位数字宽度 10dp,最小宽度 16dp(1 位数也保留对齐空间)
private const val GUTTER_CHAR_WIDTH_DP = 10
private const val GUTTER_MIN_WIDTH_DP = 16

// L2 修复: 行内格式正则提升为文件级 private val,避免每次 parseInline 调用重新编译
// 主正则: 链接 [text](url) 优先,其后才是 `code` / **bold** / *italic*
private val INLINE_REGEX = Regex(
    """\[(.+?)]\((.+?)\)|`(.+?)`|\*\*(.+?)\*\*|\*(.+?)\*"""
)
// L2 修复: [N] 引用编号正则 — 仅当 N 为正整数且非 [text](url) 形式
private val CITATION_REGEX = Regex("""\[(\d+)]""")
// v1.97: 纯文本 URL 自动识别(类似 openhanako linkify)
// 匹配 http/https 开头的 URL,到空白/引号/括号/中文标点结束
// 参考openhanako trimAutoLinkifiedSuffixes:排除尾部中文标点(。、,;!?」』)
// v1.97 阶段二: internal 供单元测试覆盖边界用例(URL_AUTOLINK_REGEX 边界测试)
// v1.97 阶段二修正: 字符类移除 `:` 和 `?` — 这两个字符在 URL 主体中合法(端口 :8080 / query ?q=1),
// 排除会导致 URL 被截断。ASCII 句点 `.` 同理不排除(URL 内合法,如 example.com),句末标点
// 由 openhanako trimAutoLinkifiedSuffixes 在调用处二次清理(此处保持简单字符类)。
// 注: `!` 保留排除(中文全角感叹号 ! 后接中文文本时不应纳入 URL)。
internal val URL_AUTOLINK_REGEX = Regex("""https?://[^\s<>"'`)\]】」』。、,;!]+""")

/**
 * 轻量 Markdown 渲染组件(自实现,不引入第三方库)。
 *
 * 支持:
 *  - 围栏代码块 ```(等宽字体 + 暖纸深色背景 + 横向滚动 + 行号 + 复制按钮 + 长代码折叠)
 *  - 标题 #/##/###(层级字号)
 *  - 行内代码 `...`(等宽 + 浅背景)
 *  - 粗体 **...** / 斜体 *...*
 *  - 链接 [...](...)(secondary 色 + 下划线)
 *  - 有序/无序列表(缩进 + 符号)
 *  - 引用 >(左侧色条)
 *  - 水平线 ---
 *
 * 不支持: 表格/脚注/图片(Phase 5+ 评估)。
 *
 * Phase 8.3 增强:
 *  - 代码块行号(左侧 gutter,等宽对齐)
 *  - 代码块复制按钮(右上角,Toast 反馈)
 *  - 代码块自动折叠(>30 行默认折叠,显示前 10 行 + "展开 N 行")
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    citationUrls: List<String> = emptyList(),
    /**
     * v1.42: 流式消息标记。
     * 为 true 时跳过链接点击检测(pointerInput)与复杂布局优化,降低重组开销。
     *
     * v1.97 (P2): 流式时额外启用两项优化:
     *  1. 块数超过 [STREAMING_BLOCK_CAP] 时只渲染最后 [STREAMING_BLOCK_CAP] 个块,
     *     顶部用 "…" 占位(避免超长消息全量组合,Compose 重组成本随块数线性增长)。
     *  2. CodeBlock / Formula / Table / RichContentCard 降级为纯文本预览,
     *     跳过语法高亮 / LaTeX 渲染 / 表格布局 / SVG-HTML 渲染等重布局;
     *     流式结束后(isStreaming=false)恢复完整渲染。
     */
    isStreaming: Boolean = false,
    /**
     * HTML/SVG 代码块全屏预览回调。
     * 当用户点击 HTML/SVG 代码块右上角的"预览"图标时触发,
     * 参数为完整 HTML 源码(SVG 已包装为完整 HTML)。
     * 由 [MessageBubble] / [ChatScreen] 注入导航逻辑,跳转到 [HtmlPreviewScreen]。
     */
    onHtmlPreview: (String) -> Unit = {},
) {
    // v1.101 (P9): 流式时把 parseMarkdown 移到后台线程,避免主线程高频正则解析。
    // 流式每 80ms 更新一次文本,原 remember(text) 会在主线程对全量文本(随消息增长
    // 可达数千字符)反复正则扫描,是流式卡顿的主要剩余瓶颈。
    // 用 produceState 在 Dispatchers.Default 解析:text 变化时取消旧协程、重启新协程,
    // value 保留上一次解析结果(流式时视觉上本就有 ~80ms 延迟,滞后一帧可接受)。
    // 非流式(历史消息)保持 remember(text) 同步解析,避免滚动入屏时闪空。
    val allBlocks: List<MarkdownBlock> = if (isStreaming) {
        val streamed by produceState<List<MarkdownBlock>>(emptyList(), text) {
            // v1.114: 修复流式闪烁。
            // produceState 在 key(text)变化时会取消旧协程并启动新协程,但 value 保留上一次值
            // (initialValue 仅首次组合使用)。原代码直接 value = withContext { ... } 在协程
            // 被取消前若解析未完成,value 会停留在旧值;但流式 text 每 ~80ms 更新一次,
            // 解析协程频繁被取消重启,导致 value 长时间得不到更新、视觉闪烁。
            // 修复:1) 启动新解析前不重置 value(直接计算新值后赋值,保留 previous);
            //      2) 加 50ms 防抖,合并短时间内连续的 text 变化,避免协程被频繁取消。
            delay(50)
            val parsed = withContext(Dispatchers.Default) { parseMarkdown(text) }
            value = parsed
        }
        streamed
    } else {
        remember(text) { parseMarkdown(text) }
    }
    // v1.97 (P2): 流式 + 块数超阈值时只渲染尾部,降低 Compose 重组成本
    val truncated = isStreaming && allBlocks.size > STREAMING_BLOCK_CAP
    val blocks = if (truncated) allBlocks.takeLast(STREAMING_BLOCK_CAP) else allBlocks
    // 行内格式需要的主题色(@Composable 上下文读取,传给非 Composable 的 parseInline)
    // v1.97: URL 高亮色从 secondary 改为 primary(主题色),让链接更醒目
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBgColor = MaterialTheme.colorScheme.surfaceVariant
    val quoteTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Phase 8.4: 引用编号 [N] 颜色(用 secondary 突出,与超链接一致)
    val citationColor = MaterialTheme.colorScheme.secondary

    Column(modifier = modifier.fillMaxWidth()) {
        // v1.97 (P2): 截断时顶部显示省略占位,提示用户上方内容已折叠(流式结束后恢复)
        if (truncated) {
            Text(
                text = "…",
                style = style,
                color = quoteTextColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
        // L-MD16 说明: 此处用 forEachIndexed 而非 LazyColumn 渲染所有块。
        // 原因: MarkdownText 用于单条消息气泡内,单条消息的块数通常很少(几段文字 + 少量代码块),
        // 用普通 Column 的开销可接受。若改用 LazyColumn,会破坏气泡内嵌布局(高度自适应),
        // 且 LazyColumn 不能嵌套在非固定高度的父容器中(消息流本身已是 LazyColumn)。
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    // Phase 8.5 修复: parseInline 用 remember 缓存,避免每次重组重新解析行内格式。
                    // 键包含 text + 所有颜色 + citationUrls,任一变化才重新解析。
                    val annotated = remember(block.text, color, linkColor, codeBgColor, citationUrls, citationColor) {
                        parseInline(block.text, color, linkColor, codeBgColor, citationUrls, citationColor)
                    }
                    if (isStreaming) {
                        // v1.42: 流式中直接用 Text 渲染,避免 LinkableText 的 pointerInput 随内容变化反复重建。
                        Text(
                            text = annotated,
                            style = style,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinkableText(
                            annotatedText = annotated,
                            style = style,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is MarkdownBlock.Heading -> {
                    if (idx > 0) Spacer(Modifier.height(8.dp))
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    val annotated = remember(block.text, color, linkColor, codeBgColor, citationUrls, citationColor) {
                        parseInline(block.text, color, linkColor, codeBgColor, citationUrls, citationColor)
                    }
                    LinkableText(
                        annotatedText = annotated,
                        style = headingStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (idx < blocks.lastIndex) Spacer(Modifier.height(4.dp))
                }

                is MarkdownBlock.CodeBlock -> {
                    // v1.97 (P2): 流式期间降级为纯文本预览,跳过 CodeBlockView 的行号/复制/折叠/高亮布局
                    if (isStreaming) {
                        StreamingCodePreview(block, style, codeBgColor)
                    } else {
                        // v0.48: 富媒体代码块(svg/html/chart)用 RichContentCard 渲染,其余走 CodeBlockView
                        val richLang = block.language?.lowercase()?.trim().orEmpty()
                        when {
                            // ChartRenderer: mermaid / plantuml 用独立渲染器(WebView + Coil,含高度自适应与失败回退)
                            isMermaidCodeBlock(block.language, block.code) -> MermaidBlock(code = block.code)
                            isPlantUmlCodeBlock(block.language, block.code) -> PlantUmlBlock(code = block.code)
                            richLang in setOf("svg", "html", "chart") ->
                                RichContentCard(
                                    language = richLang,
                                    content = block.code,
                                    onHtmlPreview = onHtmlPreview,
                                )
                            else -> CodeBlockView(block)
                        }
                    }
                }

                is MarkdownBlock.ListItem -> ListItemView(block, style, color, linkColor, codeBgColor, citationUrls, citationColor)

                is MarkdownBlock.Quote -> QuoteView(block, style, color, linkColor, codeBgColor, quoteTextColor, citationUrls, citationColor)

                // Phase 8.6: LaTeX 公式块(JLatexMath 渲染)
                // v1.97 (P2): 流式期间降级为纯文本,跳过 JLatexMath 渲染(重布局)
                is MarkdownBlock.Formula -> {
                    if (isStreaming) {
                        Text(
                            text = block.latex,
                            style = style.copy(fontFamily = MuseMonoFontFamily),
                            color = quoteTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        FormulaView(block.latex)
                    }
                }

                // v1.97: Markdown 表格
                // v1.97 (P2): 流式期间降级为纯文本预览,跳过 TableView 布局
                is MarkdownBlock.Table -> {
                    if (isStreaming) {
                        val plain = buildString {
                            appendLine(block.headers.joinToString(" | "))
                            block.rows.forEach { appendLine(it.joinToString(" | ")) }
                        }
                        Text(
                            text = plain,
                            style = style.copy(fontFamily = MuseMonoFontFamily),
                            color = quoteTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        TableView(block)
                    }
                }

                MarkdownBlock.Divider -> {
                    if (idx > 0) Spacer(Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                    if (idx < blocks.lastIndex) Spacer(Modifier.height(8.dp))
                }

                MarkdownBlock.Blank -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * v1.97 (P2): 流式期间代码块的轻量预览 — 纯等宽文本 + 暖纸深色背景,无行号/复制/折叠/高亮。
 * 流式结束后由 [CodeBlockView] 接管完整渲染。
 */
@Composable
private fun StreamingCodePreview(
    block: MarkdownBlock.CodeBlock,
    style: TextStyle,
    codeBgColor: Color,
) {
    Surface(
        color = codeBgColor,
        shape = MuseShapes.small,  // v1.115: 令牌化,原裸 RoundedCornerShape(8.dp)
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = block.code,
            style = style.copy(fontFamily = MuseMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * v1.97 (P2): 流式期间 MarkdownText 渲染的块数上限。
 * 超过此值时只渲染最后 [STREAMING_BLOCK_CAP] 个块,顶部用 "…" 占位。
 * 选 30: 普通长回复(几千字)通常 10-25 块,30 足以覆盖;
 *        超长回复(万字级)截断后仍能看到最新内容,流式结束后恢复全量。
 */
private const val STREAMING_BLOCK_CAP = 30

/**
 * Phase 8.3: 支持链接点击的行内格式化 Text。
 *
 * - 渲染 [parseInline] 产出的 AnnotatedString(含 URL tag 注解)
 * - 点击链接区域 → 弹出二次确认对话框 → 用户确认后 Intent.ACTION_VIEW 打开外链
 * - 普通区域点击无响应(不干扰列表/卡片选中)
 *
 * v1.97: URL 点击增加二级确认(MuseDialog),避免误触打开外部链接。
 *        对话框提供"打开"和"复制链接"两个操作。
 */
@Composable
private fun LinkableText(
    annotatedText: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // v1.97: 待确认的 URL(null 表示无待确认链接)
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    // v1.97: 本会话是否已选择"不再确认"(长按或勾选后置 true,本会话内点击直接打开)
    var skipConfirm by remember { mutableStateOf(false) }

    // v1.97: 打开 URL 的公共逻辑(确认后或跳过确认时调用)
    val openUrl: (String) -> Unit = { url ->
        resultOf {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onError { msg, _ ->
            MuseToast.show(context.getString(R.string.markdown_open_link_failed, msg))
        }
    }

    Text(
        text = annotatedText,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotatedText) {
            detectTapGestures(
                // v1.97: 长按直接打开(跳过确认,同时自动开启"本会话不再确认")
                onLongPress = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val pos = layout.getOffsetForPosition(offset)
                    val url = annotatedText.getStringAnnotations("URL", pos, pos)
                        .firstOrNull()?.item ?: return@detectTapGestures
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        MuseToast.show(context.getString(R.string.markdown_only_http))
                        return@detectTapGestures
                    }
                    skipConfirm = true
                    openUrl(url)
                },
                // v1.97: 单击 — 若已选"不再确认"则直接打开,否则弹确认对话框
                onTap = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val pos = layout.getOffsetForPosition(offset)
                    val url = annotatedText.getStringAnnotations("URL", pos, pos)
                        .firstOrNull()?.item ?: return@detectTapGestures
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        MuseToast.show(context.getString(R.string.markdown_only_http))
                        return@detectTapGestures
                    }
                    if (skipConfirm) {
                        openUrl(url)
                    } else {
                        pendingUrl = url
                    }
                },
            )
        },
    )

    // v1.97: URL 点击二级确认对话框(含"本会话不再确认"选项)
    pendingUrl?.let { url ->
        io.zer0.muse.ui.common.MuseDialog(
            onDismissRequest = { pendingUrl = null },
            title = context.getString(R.string.markdown_open_link_confirm_title),
            content = {
                Column {
                    Text(context.getString(R.string.markdown_open_link_confirm_message))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = skipConfirm,
                            onCheckedChange = { skipConfirm = it },
                        )
                        Text(
                            text = context.getString(R.string.markdown_open_link_dont_ask_again),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmText = context.getString(R.string.markdown_open_link_confirm_open),
            onConfirm = {
                pendingUrl = null
                openUrl(url)
            },
            dismissText = context.getString(R.string.markdown_open_link_confirm_copy),
            onDismiss = {
                // v1.97: "复制链接"按钮 — 复制 URL 到剪贴板
                pendingUrl = null
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                MuseToast.show(context.getString(R.string.markdown_link_copied))
            },
        )
    }
}

/**
 * 代码块视图: 等宽字体 + 暖纸深色背景 + 横向滚动 + 行号 + 复制 + 折叠。
 *
 * Phase 8.3 增强:
 *  - 行号: 左侧 gutter 等宽对齐(右对齐数字)
 *  - 复制按钮: 右上角 ContentCopy 图标,Toast "已复制 N 行代码"
 *  - 自动折叠: 超过 30 行的代码块默认折叠(显示前 10 行),底部"展开 N 行"按钮
 */
@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    val context = LocalContext.current
    val codeLines = remember(block.code) { block.code.split("\n") }
    val lineCount = codeLines.size
    val autoCollapseThreshold = 30
    val previewLineCount = 10
    // 默认折叠状态: 超过阈值则默认折叠;rememberSaveable 保证滚动/重组后状态保留
    var expanded by rememberSaveable(block.code) {
        mutableStateOf(lineCount <= autoCollapseThreshold)
    }
    val displayedLines = if (expanded) codeLines else codeLines.take(previewLineCount)
    val hiddenCount = lineCount - previewLineCount

    Spacer(Modifier.height(6.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 顶部行: language 标签 + 复制按钮 + 行数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    block.language?.takeIf { it.isNotBlank() }?.let { lang ->
                        Text(
                            text = lang,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        text = stringResource(R.string.markdown_line_count, lineCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(
                    onClick = {
                        // L6 已知限制: 复制代码未标注敏感(如含密钥的代码块),统一以纯文本写入剪贴板;
                        // 后续可结合内容检测判断是否敏感,暂不实现(工作量较大)。
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        MuseToast.show(context.getString(R.string.markdown_copied_lines, lineCount))
                    },
                    // L-MD17: 复制按钮触摸目标已用 MuseIconSizes.touchTarget(48dp),
                    // 满足 MD3 无障碍红线,无需额外修复
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.markdown_copy_code_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 代码内容: 行号 + 代码
            // Phase 10.5: 行号右对齐 + 固定宽度(按总行数位数计算),1/10/100 对齐美观
            // L-MD15 修复: gutter 宽度用文件级常量计算(原先 10/16 硬编码)
            val gutterWidth = (lineCount.toString().length * GUTTER_CHAR_WIDTH_DP).coerceAtLeast(GUTTER_MIN_WIDTH_DP).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                // 行号 gutter(右对齐,等宽字体保证数字对齐)
                Column(modifier = Modifier.width(gutterWidth).padding(end = 8.dp)) {
                    displayedLines.forEachIndexed { idx, _ ->
                        Text(
                            text = "${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MuseMonoFontFamily,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            ),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // 代码内容
                // v1.48: 折叠时叠加渐变遮罩,符合"折叠带渐变遮罩"约束
                Box {
                    Column {
                        // H-MD1 修复: 用 key() 标识每行,避免折叠/展开切换时
                        // forEachIndexed 位置记忆错乱导致 CodeHighlighter 缓存失效。
                        // 以原始 line 内容为 key(不含折叠时追加的 // … 后缀),
                        // Compose 能跨重组匹配同一行的高亮缓存。
                        displayedLines.forEachIndexed { idx, line ->
                            key(line, idx) {
                                val displayLine = if (!expanded && idx == previewLineCount - 1 && hiddenCount > 0) {
                                    // 折叠时最后一行显示省略号提示
                                    "$line  // …"
                                } else {
                                    line
                                }
                                Text(
                                    text = CodeHighlighter.highlight(displayLine, block.language),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = MuseMonoFontFamily,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // 折叠时在底部叠加从透明到背景色的渐变,营造"未完待续"的视觉提示
                    if (!expanded && hiddenCount > 0) {
                        val bgColor = MaterialTheme.colorScheme.surfaceVariant
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.5f to Color.Transparent,
                                        1.0f to bgColor,
                                    ),
                                ),
                        )
                    }
                }
            }
            // 折叠/展开按钮(仅当超过阈值时显示)
            if (lineCount > autoCollapseThreshold) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = Color.Transparent,
                    shape = MuseShapes.extraSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        // H-MD6 修复: clickable 加 role=Role.Button 无障碍语义;
                        // heightIn(min=48.dp) 保证触摸目标≥48dp(原先 vertical=6.dp 仅约 28dp)
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = { expanded = !expanded },
                        )
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.markdown_collapse_remaining, hiddenCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 列表项视图: 缩进 + 符号(• 或 1.)。 */
@Composable
private fun ListItemView(
    block: MarkdownBlock.ListItem,
    baseStyle: TextStyle,
    baseColor: Color,
    linkColor: Color,
    codeBgColor: Color,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
) {
    val marker = if (block.ordered) "${block.index}." else "•"
    // Phase 8.5 修复: parseInline 用 remember 缓存,与 Paragraph/Heading 路径保持一致
    val annotated = remember(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor) {
        parseInline(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = marker,
            style = baseStyle,
            color = baseColor,
            modifier = Modifier.width(24.dp),
        )
        LinkableText(
            annotatedText = annotated,
            style = baseStyle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 引用块视图: 左侧色条 + 浅背景。 */
@Composable
private fun QuoteView(
    block: MarkdownBlock.Quote,
    baseStyle: TextStyle,
    baseColor: Color,
    linkColor: Color,
    codeBgColor: Color,
    quoteTextColor: Color,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
) {
    // Phase 8.5 修复: parseInline 用 remember 缓存,与 Paragraph/Heading 路径保持一致
    val annotated = remember(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor) {
        parseInline(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(3.dp).height(20.dp),
        ) {}
        Spacer(Modifier.width(8.dp))
        LinkableText(
            annotatedText = annotated,
            style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = quoteTextColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 行内格式解析: 粗体 **...** / 斜体 *...* / 行内代码 `...` / 链接 [...](...) /
 * 引用编号 [N](Phase 8.4 UrlCitation)。
 *
 * 颜色由调用方传入(buildAnnotatedString 内部非 @Composable,无法读 MaterialTheme)。
 * 解析顺序: 链接 [text](url) > 引用编号 [N] > 行内代码 > 粗体 > 斜体(正则 alternation 一次扫描)。
 * 嵌套语法(如 **`code`**)未完全支持,常见用法覆盖即可。
 *
 * Phase 8.4 UrlCitation: 当 [citationUrls] 非空时,文本中的 [N](N 为正整数)会被
 * 渲染为 secondary 色可点击注解,点击跳转到 citationUrls[N-1]。注意 [text](url)
 * 形式的标准 markdown 链接优先匹配,避免误把链接文本里的数字当引用。
 */
fun parseInline(
    text: String,
    baseColor: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    codeBgColor: Color = Color.Unspecified,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
): AnnotatedString = buildAnnotatedString {
    // L2 修复: 正则已提升为文件级 private val(INLINE_REGEX / CITATION_REGEX),此处直接引用
    // Phase 8.4: 单独走 [N] 引用扫描(在主正则之后),避免与 [text](url) 冲突
    var lastEnd = 0
    INLINE_REGEX.findAll(text).forEach { match ->
        // 输出匹配前的普通文本(可能含 [N] 引用或纯文本 URL)
        if (match.range.first > lastEnd) {
            appendSegmentWithCitations(
                text.substring(lastEnd, match.range.first),
                citationUrls,
                citationColor,
                CITATION_REGEX,
                baseColor,
                linkColor,
            )
        }
        when {
            // 链接 [text](url) — group 1=text, 2=url
            match.groupValues[1].isNotEmpty() -> {
                val linkText = match.groupValues[1]
                val url = match.groupValues[2]
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(linkText)
                }
                pop()
            }
            // 行内代码 `...` — group 3
            match.groupValues[3].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        fontFamily = MuseMonoFontFamily,
                        background = codeBgColor,
                    )
                ) {
                    append(match.groupValues[3])
                }
            }
            // 粗体 **...** — group 4
            match.groupValues[4].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[4])
                }
            }
            // 斜体 *...* — group 5
            match.groupValues[5].isNotEmpty() -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[5])
                }
            }
        }
        lastEnd = match.range.last + 1
    }
    // 输出末尾剩余文本(可能含 [N] 引用或纯文本 URL)
    if (lastEnd < text.length) {
        appendSegmentWithCitations(
            text.substring(lastEnd),
            citationUrls,
            citationColor,
            CITATION_REGEX,
            baseColor,
            linkColor,
        )
    }
}

/**
 * Phase 8.4: 把一段普通文本中的 [N] 引用编号渲染为可点击 URL 注解。
 * 仅当 N 在 citationUrls 范围内时才注解,其他 [N] 当普通文本输出。
 *
 * v1.97: 增加纯文本 URL 自动识别(类似 openhanako linkify)。
 * 普通文本中的 http/https URL 会被自动渲染为 primary 色可点击链接。
 * URL 优先于 [N] 引用扫描,避免 URL 内的 [数字] 被误匹配。
 *
 * Phase 8.5 清理: 移除未使用的 mainRanges / segmentOffset 参数(原设计用于防止
 * [N] 引用扫描误入 [text](url) 内部,但 citationRegex 只匹配纯数字 [N],
 * 与 [text](url) 的 [非空文本](...) 结构天然不重叠,无需此保护)。
 */
private fun AnnotatedString.Builder.appendSegmentWithCitations(
    segment: String,
    citationUrls: List<String>,
    citationColor: Color,
    citationRegex: Regex,
    baseColor: Color,
    linkColor: Color = citationColor,
) {
    if (segment.isEmpty()) {
        append(segment)
        return
    }
    // v1.97: 先扫描纯文本 URL,把文本切成 URL 段和普通段
    var urlLast = 0
    URL_AUTOLINK_REGEX.findAll(segment).forEach { urlMatch ->
        // URL 之前的普通文本走 [N] 引用扫描
        if (urlMatch.range.first > urlLast) {
            appendCitationOnly(
                segment.substring(urlLast, urlMatch.range.first),
                citationUrls,
                citationColor,
                citationRegex,
            )
        }
        // URL 本身渲染为 primary 色可点击链接
        val url = urlMatch.value
        pushStringAnnotation(tag = "URL", annotation = url)
        withStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
        ) {
            append(url)
        }
        pop()
        urlLast = urlMatch.range.last + 1
    }
    // URL 之后的剩余文本走 [N] 引用扫描
    if (urlLast < segment.length) {
        appendCitationOnly(
            segment.substring(urlLast),
            citationUrls,
            citationColor,
            citationRegex,
        )
    }
}

/**
 * v1.97: 把一段纯文本中的 [N] 引用编号渲染为可点击 URL 注解(不含 URL 自动识别)。
 * 从 appendSegmentWithCitations 抽出,供 URL 分段后的普通文本复用。
 */
private fun AnnotatedString.Builder.appendCitationOnly(
    text: String,
    citationUrls: List<String>,
    citationColor: Color,
    citationRegex: Regex,
) {
    if (citationUrls.isEmpty() || text.isEmpty()) {
        append(text)
        return
    }
    var segLast = 0
    citationRegex.findAll(text).forEach { cm ->
        if (cm.range.first > segLast) {
            append(text.substring(segLast, cm.range.first))
        }
        val n = cm.groupValues[1].toIntOrNull() ?: 0
        val url = citationUrls.getOrNull(n - 1)
        if (url != null) {
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                SpanStyle(
                    color = citationColor,
                    fontWeight = FontWeight.Bold,
                )
            ) {
                append(cm.value)
            }
            pop()
        } else {
            append(cm.value)
        }
        segLast = cm.range.last + 1
    }
    if (segLast < text.length) {
        append(text.substring(segLast))
    }
}

/**
 * v1.97: Markdown 表格渲染。
 * 表头行用 primary 半透明背景,数据行用 onSurfaceVariant 色,等宽列布局。
 */
@Composable
private fun TableView(table: MarkdownBlock.Table) {
    val headerColor = MaterialTheme.colorScheme.onSurface
    val cellColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 表头行
        Row(modifier = Modifier.fillMaxWidth()) {
            table.headers.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = headerColor,
                    modifier = Modifier
                        .weight(1f)
                        .background(headerBg)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    textAlign = TextAlign.Start,
                )
            }
        }
        HorizontalDivider(color = borderColor, thickness = 1.dp)
        // 数据行
        table.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        color = cellColor,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
            HorizontalDivider(color = borderColor.copy(alpha = 0.5f), thickness = 0.5.dp)
        }
    }
}
