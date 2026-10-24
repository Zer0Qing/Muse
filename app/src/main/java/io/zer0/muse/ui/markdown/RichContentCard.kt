package io.zer0.muse.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream

/**
 * v0.48: 富媒体内容卡片 — 渲染 SVG / HTML / 图表。
 *
 * LLM 在回复中用特殊代码块语言标识触发:
 * - ```svg ... ``` → SVG 卡片(WebView 渲染)
 * - ```html ... ``` → HTML 卡片(WebView 渲染)
 * - ```chart {...} ``` → 图表卡片(本期简化为 JSON 展示,后续可接 Vico)
 * - ```mermaid ... ``` → Mermaid 图表卡片(v1.57: WebView + mermaid.js 渲染流程图/时序图等;v1.97 改为本地 assets 加载)
 *
 * 安全说明: v1.97 起 Chart.js/mermaid.js/KaTeX 均从本地 assets/vendor/ 加载(base URL 为
 * file:///android_asset/),不再依赖任何远程 CDN,无 SRI/劫持风险。RichContentWebViewClient
 * 拦截所有顶层导航。H1 修复: 对 LLM 输出的 HTML/SVG 做基本正则清洗,防止 meta refresh/iframe/事件属性等威胁。
 *
 * Phase 2 补齐:
 *  - 头部新增"复制源码"动作(所有语言可用),写入剪贴板 + Toast 反馈;
 *  - 全屏预览扩展到 chart/mermaid:html/svg 仍通过 [onHtmlPreview] 交给 HtmlPreviewScreen;
 *    chart/mermaid 依赖本地 assets 脚本(在 HtmlPreviewScreen 的 about:blank baseUrl 下
 *    无法加载),改用卡片内全屏 WebView 对话框渲染同一份 HTML,保证路径可用。
 *
 * @param onHtmlPreview HTML/SVG 全屏预览回调,参数为完整 HTML 源码
 *         (SVG 会先包装为完整 HTML 再回调)。仅 svg/html 语言触发,其余语言忽略。
 * @param showPreviewButton 是否显示右上角"全屏预览"按钮。调用方无导航能力时(如 ArtifactViewer
 *         弹窗内)可置为 false,避免出现无效按钮。
 */
// v2.6.0: 新增 inlineCardData 参数后,签名漂移使既有 baseline 条目失配。
// 这些为屏幕级 @Composable 函数的固有结构(长函数/多参数/复杂条件/命名),按点豁免。
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun RichContentCard(
    language: String,
    content: String,
    modifier: Modifier = Modifier,
    onHtmlPreview: (String) -> Unit = {},
    showPreviewButton: Boolean = true,
    /**
     * v1.0.92: 卡桥回传回调(聊天场景提供)。
     *
     * v2.6.8: null 只意味着「卡片没有回传通道」，**不再意味着「卡片无脚本」** ——
     * JS 与桥已解耦，无桥卡片照常运行自己的脚本与本地交互（见 HtmlCard/SvgCard 注释）。
     */
    onCardAction: ((CardAction) -> Unit)? = null,
    /**
     * v2.6.6: 渐进渲染模式——源码正在流式增长时置 true。
     * 内部对传递给子 WebView 的内容做节流（每增长固定字符数才刷新一次），
     * 避免每个 token 重载 WebView。
     */
    progressive: Boolean = false,
    /**
     * v2.6.0: 内联卡片数据(show_card 的 data 参数)。
     *
     * 非空时，卡片脚本的 `window.muse.getData(cardId)` 无条件返回这份数据
     * （不再依赖 CardDataStore 的 cardId 查找 —— show_card 的 cardId 在执行时才生成，
     * 渲染侧拿不到）。null 时维持原行为（按 cardId 查存储）。
     */
    inlineCardData: String? = null,
    /**
     * v2.6.7: 卡片身份（聊天场景传消息 id）。
     *
     * 注入给卡片脚本的 `window.muse.cardId`，使卡片能读自己的绑定数据、
     * 回传时也能带上来源，解决「卡片在卡片里结束」的断点。
     * null 时卡片端 cardId 为 null（只读渲染 / 非聊天场景）。
     */
    cardId: String? = null,
) {
    val context = LocalContext.current
    // v2.6.6: 渐进渲染节流 —— 流式期间源码持续增长，若每 token 都把新内容传给 WebView，
    // 会触发频繁重载（卡死）。这里按固定字符档位量化：只有增长跨过一档才推进渲染。
    // 流式结束后（progressive=false）直接用最新完整内容。
    val effectiveContent =
        if (!progressive) {
            content
        } else {
            content.take(content.length - content.length % PROGRESSIVE_RENDER_STEP)
                .ifBlank { content }
        }
    // Phase 2: chart/mermaid 的全屏预览在卡片内完成(本地 assets 脚本可正常加载)
    var showFullscreenPreview by remember { mutableStateOf(false) }
    // v2.x 统一化: 行内动作收敛为「更多」菜单(防止窄屏文本行被多个按钮挤压)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    val supportsPreview = richContentSupportsPreview(language)
    val isLocalPreview = language.lowercase().trim() in RICH_LOCAL_PREVIEW_LANGUAGES
    val typeLabel = when (language.lowercase().trim()) {
        "svg" -> stringResource(R.string.markdown_svg_label)
        "html" -> stringResource(R.string.markdown_html_label)
        "chart" -> stringResource(R.string.markdown_chart_label)
        "mermaid" -> stringResource(R.string.markdown_mermaid_label)
        else -> language.uppercase()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 头部行:左侧语言标签 + 右侧动作(复制源码 / 全屏预览)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: 语言标签（非正文）限 1 行 —— 右侧两个 48dp 图标按钮在放大字号下
                    // 会把标签列挤成竖排（线上事故形态）；用户正文在下方单独渲染，不受影响。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Phase 2: 复制源码 — 所有语言可用,不再局限于 HTML/SVG
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = { copyRichContentSource(context, content) },
                    contentDescription = stringResource(R.string.html_preview_copy_source_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                if (onCardAction != null || (supportsPreview && showPreviewButton)) {
                    MuseTactileButton(
                        icon = MuseIcons.moreVertical,
                        onClick = { menuExpanded = true },
                        contentDescription = moreCd,
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            when (language.lowercase().trim()) {
                "svg" -> SvgCard(effectiveContent, onCardAction, inlineCardData, cardId)
                "html" -> HtmlCard(effectiveContent, onCardAction, inlineCardData, cardId)
                "chart" -> ChartCard(effectiveContent)
                "mermaid" -> MermaidBlock(effectiveContent)
                else -> Text(effectiveContent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onCardAction != null) {
                        MuseActionSheetRow(
                            icon = MuseIcons.bookmark,
                            text = stringResource(R.string.card_save_cd),
                            onClick = {
                                menuExpanded = false
                                onCardAction.invoke(CardAction.Save(language, content))
                            },
                        )
                    }
                    if (supportsPreview && showPreviewButton) {
                        MuseActionSheetRow(
                            icon = MuseIcons.eye,
                            text = stringResource(R.string.html_preview_button_cd),
                            onClick = {
                                menuExpanded = false
                                if (isLocalPreview) {
                                    // chart/mermaid:卡片内全屏 WebView,加载本地 assets 脚本
                                    showFullscreenPreview = true
                                } else {
                                    // SVG 包装为完整 HTML 后再回调,保证 HtmlPreviewScreen 直接渲染
                                    val fullHtml = if (language.lowercase().trim() == "svg") {
                                        "<html><body style=\"margin:0;padding:8px;\">$content</body></html>"
                                    } else {
                                        content
                                    }
                                    onHtmlPreview(fullHtml)
                                }
                            },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }

    // Phase 2: chart/mermaid 全屏预览对话框(与卡片渲染同一份 HTML)
    if (showFullscreenPreview && isLocalPreview) {
        // P3-2: 错误前缀从资源注入(JS 内不再硬编码中文)
        val renderFailedPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
        val previewHtml = remember(content, language) { buildLocalPreviewHtml(language, content, renderFailedPrefix) }
        RichContentFullscreenPreview(
            title = typeLabel,
            html = previewHtml,
            onDismiss = { showFullscreenPreview = false },
        )
    }
}

/**
 * 支持全屏预览的富内容语言(html/svg 走外部导航预览;chart/mermaid 走卡片内全屏对话框)。
 * Phase 2: 从原"仅 html/svg"扩展到 chart/mermaid。
 */
internal fun richContentSupportsPreview(language: String): Boolean = language.lowercase().trim() in RICH_PREVIEW_LANGUAGES

/** 全屏预览语言集合。 */
private val RICH_PREVIEW_LANGUAGES = setOf("html", "svg", "chart", "mermaid")

/**
 * v2.6.6: 渐进渲染的字符量化档位 —— 流式期间源码每增长这么多字符才把新内容
 * 交给 WebView 重渲染，避免每个 token 都重载 WebView。
 */
private const val PROGRESSIVE_RENDER_STEP = 120

/**
 * 依赖本地 assets 脚本渲染、需在卡片内全屏 WebView 预览的语言。
 * HtmlPreviewScreen 以 about:blank 为 baseUrl,相对路径的 vendor 脚本无法加载。
 */
internal val RICH_LOCAL_PREVIEW_LANGUAGES = setOf("chart", "mermaid")

/** 生成卡片内全屏预览用 HTML(与卡片渲染使用同一构建函数)。 */
private fun buildLocalPreviewHtml(language: String, content: String, errorPrefix: String): String = when (language.lowercase().trim()) {
    "chart" -> buildChartHtml(content, errorPrefix)
    "mermaid" -> buildMermaidHtml(content, errorPrefix)
    else -> content
}

/** 复制富内容源码到剪贴板,并 Toast 反馈。 */
private fun copyRichContentSource(context: Context, source: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("muse_rich_content", source))
    MuseToast.show(context.getString(R.string.markdown_source_copied))
}

/**
 * Phase 2: 卡片内全屏预览对话框 — 顶部标题 + 关闭按钮,主体为占满屏幕的 WebView。
 *
 * 使用与卡片相同的 [LifecycleAwareWebViewContainer] + `file:///android_asset/` baseUrl,
 * 因此 chart(Chart.js)/mermaid(mermaid.js)的本地 vendor 脚本可正常加载。
 */
@Composable
private fun RichContentFullscreenPreview(title: String, html: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 铺满整屏(含状态栏)：否则全屏预览的底色/遮罩从状态栏下方开始，
            // 上方会留一条没被覆盖的亮带。
            decorFitsSystemWindows = false,
        ),
    ) {
        // v1.0.92: window 铺满整屏(含状态栏),与 BrowserCapsule / CommandPalette /
        // FullScreenMediaViewer 的既有处理对齐;否则预览底色从状态栏下沿开始。
        MuseDialogWindowEffect(forceFullScreen = true)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = MusePaddings.screen, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 全屏预览对话框标题（非正文）限 1 行 —— 右侧关闭按钮会挤压标题列
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                LifecycleAwareWebViewContainer(
                    htmlContent = html,
                    baseUrl = "file:///android_asset/",
                    javaScriptEnabled = true,
                    webViewClient = RichContentWebViewClient(),
                    // v2.6.7: 全屏预览允许内部滚动 —— 卡片可能远超一屏。
                    scrollable = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * v1.131: HTML/SVG 清洗正则 — 文件级常量,避免每次 [sanitizeHtml] 调用都新建 9 个 Regex。
 * 原实现每次 LLM 输出富内容都重建一次,在长 SVG/HTML 输入下 GC 压力明显。
 */
private val IFRAME_BLOCK_REGEX = Regex("""(?is)<iframe\b[^>]*>.*?</iframe>""")
private val FORM_BLOCK_REGEX = Regex("""(?is)<form\b[^>]*>.*?</form>""")
private val OBJECT_BLOCK_REGEX = Regex("""(?is)<object\b[^>]*>.*?</object>""")
private val EMBED_BLOCK_REGEX = Regex("""(?is)<embed\b[^>]*>.*?</embed>""")
private val IFRAME_SELF_CLOSED_REGEX = Regex("""(?is)<iframe\b[^>]*/?>""")
private val EMBED_SELF_CLOSED_REGEX = Regex("""(?is)<embed\b[^>]*/?>""")
private val META_HTTP_EQUIV_REGEX = Regex("""(?is)<meta\b[^>]*http-equiv[^>]*>""")

// v2.6.7: EVENT_ATTR_REGEX 已由 [rewriteInlineHandlers] 取代 —— 事件属性改为
// 重写成 addEventListener，不再直接移除。
private val JS_PROTO_REGEX = Regex("""(?i)(href|src)\s*=\s*("javascript:[^"]*"|'javascript:[^']*')""")

/**
 * H1 修复: 对 LLM 输出的 HTML/SVG 做基本清洗,移除高危标签与属性。
 *
 * v2.6.7 交互修复: `onXxx` 事件属性不再直接删掉 —— 删掉就等于把卡片的交互
 * 能力全部硫掉（模型写的 onclick 正是点击交互的唯一载体），而这正是
 * “卡片能看不能点”的直接原因。
 *
 * 改为：先把 `onXxx="handler"` 收集起来，从属性里移除，再重写成等价的
 * `<script>` 事件绑定（`el.addEventListener`）。因为 CSP 已允许
 * `script-src 'unsafe-inline'`（本地渲染、无外部网络），重写后行为不变，
 * 但不再依赖内联属性执行，也让代码路径只有一条。
 *
 * 已知限制(正则清洗的固有限制):
 *  - 无法覆盖所有 XSS 向量(如畸形标签、Unicode 绕过、属性值内嵌套引号等);
 *  - 但能拦截常见威胁:<meta http-equiv="refresh">、<iframe>、<form>、<object>/<embed>、
 *    javascript: 伪协议。
 *  - 卡片本身运行在禁一切外部网络（connect-src 'none'）+ 导航全拦截的沙箱里，
 *    所以允许内联交互脚本的风险面被限制在“卡片自己的 DOM”内。
 */
private fun sanitizeHtml(input: String): String {
    var s = input
    // 移除 iframe / form / object / embed(含内容,跨行)
    s = IFRAME_BLOCK_REGEX.replace(s, "")
    s = FORM_BLOCK_REGEX.replace(s, "")
    s = OBJECT_BLOCK_REGEX.replace(s, "")
    s = EMBED_BLOCK_REGEX.replace(s, "")
    // 移除自闭合 iframe/embed(无闭合标签)
    s = IFRAME_SELF_CLOSED_REGEX.replace(s, "")
    s = EMBED_SELF_CLOSED_REGEX.replace(s, "")
    // 移除 meta http-equiv(refresh 跳转)
    s = META_HTTP_EQUIV_REGEX.replace(s, "")
    // v2.6.7: 事件属性改写成 addEventListener 绑定（保留交互）
    s = rewriteInlineHandlers(s)
    // 移除 javascript: 伪协议(href/src="javascript:...")
    s = JS_PROTO_REGEX.replace(s, """$1="#" """)
    return s
}

/** 匹配 `onXxx="code"` / `onXxx='code'` / `onXxx=code`。 */
private val INLINE_HANDLER_REGEX =
    Regex("""\s+(on[a-z]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/**
 * v2.6.7: 把内联事件属性改写为 `<script>` 里的 addEventListener。
 *
 * 做法：每个带事件的元素打上 `data-muse-h="mN"` 标记，
 * 事件名与代码收到表里，最后拼一段脚本统一绑定。
 * 既保留模型写的交互，又不依赖内联属性执行。
 */
private fun rewriteInlineHandlers(html: String): String {
    val handlers = mutableListOf<Triple<String, String, String>>() // (标记, 事件名, 代码)
    var seq = 0

    val out = TAG_REGEX.replace(html) { tagMatch ->
        val tag = tagMatch.value
        // 当前标签内的事件：先收集，再决定是否打标记。
        val local = mutableListOf<Triple<String, String, String>>()
        val stripped = INLINE_HANDLER_REGEX.replace(tag) { m ->
            val event = m.groupValues[1].lowercase()
            val code = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            if (code.isBlank()) {
                ""
            } else {
                val marker = "m${seq++}"
                local += Triple(marker, event, code)
                ""
            }
        }
        if (local.isEmpty()) return@replace tag

        handlers += local
        val markerAttr = " data-muse-h=\"${local.joinToString(" ") { it.first }}\""
        stripped.replaceFirst(Regex("""^(<[a-zA-Z][\w-]*)"""), "$1" + markerAttr)
    }

    if (handlers.isEmpty()) return out

    val bindings = handlers.joinToString("\n") { (marker, event, code) ->
        "document.querySelectorAll('[data-muse-h~=\"$marker\"]').forEach(function(el){" +
            "el.addEventListener('$event', function(ev){try{$code}catch(e){console.warn(e);}});});"
    }
    return out + "\n<script>\n" + bindings + "\n</script>"
}

/** 粗略匹配一个完整标签（不跨 > ），用于在标签内做属性改写。 */
private val TAG_REGEX = Regex("""<[a-zA-Z][^>]*>""")

/**
 * 富内容 WebView 统一导航拦截 — 全部拒绝。
 *
 * v1.0.10: 修复白名单逻辑漏洞。旧版放行 cdn.jsdelivr.net,但 Chart.js/mermaid.js/KaTeX
 * 已改为从本地 assets/vendor/ 加载(见 buildHtml),不再需要任何远程域名白名单。
 * 旧的白名单反而成为安全口子:LLM 若输出指向 jsdelivr 的恶意页面会被放行。
 *
 * 现策略:顶层导航一律拦截(return true),杜绝 LLM 输出的 <a>/meta refresh
 * 跳转到任何外部页面或伪协议。本地资源(file:///android_asset/)作为子资源加载,
 * 不受 shouldOverrideUrlLoading 影响。
 */
internal class RichContentWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return true
    }

    /**
     * v2.6.6 安全加固: 拦截一切外部网络请求。
     *
     * 卡片的所有依赖(Chart.js / mermaid.js / KaTeX)均从本地 `file:///android_asset/`
     * 加载,子资源不受 shouldOverrideUrlLoading 影响,这里再补一道:
     * 非本地(asset/file/data/blob)一律阻断。防止模型产卡片偷偷请求外部服务(跟踪/回传)。
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString()
        val allowed =
            url == null ||
                url.startsWith("file://") ||
                url.startsWith("data:") ||
                url.startsWith("blob:") ||
                url.startsWith("about:") ||
                url.startsWith("javascript:")
        return if (allowed) {
            null // null = 走默认处理
        } else {
            // 阻断外部请求:返回空响应
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SvgCard(svg: String, onCardAction: ((CardAction) -> Unit)? = null, inlineCardData: String? = null, cardId: String? = null) {
    // H1 修复: 对 LLM 输出的 SVG 做基本清洗,移除事件属性/伪协议等
    val safeSvg = sanitizeHtml(svg)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val svgCd = stringResource(R.string.markdown_svg_cd)
    // v1.0.92: 卡桥 — 仅当调用方提供回传回调时注入(引导脚本在清洗后拼接,不受清洗影响)
    // v2.6.8: 桥的有无只影响“能不能回传”，不决定 JS 开关（见下方 javaScriptEnabled）。
    // v2.0: 卡桥同时携带数据读取能力(卡片数据绑定)
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = org.koin.compose.koinInject()
    val bridge = remember(onCardAction, cardDataStore, inlineCardData, cardId) {
        onCardAction?.let {
            MuseCardBridge(
                onAction = it,
                readCardData = { id -> inlineCardData ?: cardDataStore?.get(id) },
                cardId = cardId,
            )
        }
    }
    // v2.6.7: SVG 也用实测高度，不再固定最小 200dp —— 大图会被拉伸变形或裁切。
    var svgHeightPx by remember(safeSvg) { mutableStateOf(0) }
    val svgDensity = LocalDensity.current
    // v2.6.9: 同 HtmlCard —— 高度走专用接口，不看交互桥脸色。
    // 旧实现在无桥路径上 svgHeightPx 永远是 0，于是只能退回 heightIn(min=200.dp)
    // 这个固定容器，跟内容完全没关系（图小则空、图大则切）。
    val svgHeightSink = remember { CardHeightSink() }
    androidx.compose.runtime.LaunchedEffect(svgHeightSink) {
        svgHeightSink.onHeightChanged = { px -> if (px > 0) svgHeightPx = px }
    }
    androidx.compose.runtime.DisposableEffect(svgHeightSink) {
        onDispose { svgHeightSink.onHeightChanged = null }
    }
    val bridgeBoot = if (bridge != null) "<script>$CARD_BRIDGE_BOOTSTRAP_JS</script>" else ""
    // 把 SVG 包进 HTML 里,用 WebView 渲染；v2.6.6 同步注入主题令牌（SVG 可用 var(...)；
    // body 内联样式会覆盖背景/边距，不影响 SVG 布局）。
    val html = """
        <html><head><meta charset="UTF-8">${MuseCardTheme.styleTag}</head><body style="margin:0;padding:8px;background:transparent;">
        $bridgeBoot$safeSvg<script>$HEIGHT_OBSERVER_SCRIPT</script>
        </body></html>
    """.trimIndent()
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 WebView 残留资源占用问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = null,
        // v2.6.8: JS 始终启用。
        //
        // 旧实现把 JS 开关绑在「桥是否存在」上，结果把两件事焊在了一起：
        //  —「卡片能不能回传」与「卡片能不能动」被当成同一件事。
        // 结果是「没有回传回调」的场景（任务卡步骤、全屏预览、工件查看）里 JS 整个关闭，
        // 卡片的静态脚本、CSS 动画、switch 视觉全部不执行，长按还退化成 WebView 默认选字菜单 ——
        // 用户看到的就是“控件完全没反应”。
        //
        // 现在的取舍：JS 只负责“卡片自己跑起来”，桥只负责“回传”。两者独立：
        //  - 没有桥时 window.muse 不存在，卡片里的回传调用会抛错（被卡片脚本自己 catch 或静默），
        //    不影响渲染与本地交互；
        //  - 安全边界不变：CSP 已禁一切外部网络（connect-src 'none'）、导航全拦截
        //    （RichContentWebViewClient + shouldInterceptRequest）、文件/内容访问关闭，
        //    脚本只能动自己这颗 DOM。
        javaScriptEnabled = true,
        webViewClient = RichContentWebViewClient(),
        onWebViewCreated = { webView ->
            webView.addJavascriptInterface(svgHeightSink, CardHeightSink.NAME)
            bridge?.let { webView.addJavascriptInterface(it, MuseCardBridge.BRIDGE_NAME) }
        },
        // M-MD9 修复: height 改为 heightIn(min=...),允许内容超出时自适应
        // M5 修复: 加 contentDescription 供无障碍朗读
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (svgHeightPx > 0) {
                    Modifier.heightIn(min = 60.dp, max = 4000.dp)
                        .height(with(svgDensity) { svgHeightPx.toDp() })
                } else {
                    Modifier.heightIn(min = 200.dp, max = 4000.dp)
                },
            )
            .semantics { contentDescription = svgCd },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun HtmlCard(html: String, onCardAction: ((CardAction) -> Unit)? = null, inlineCardData: String? = null, cardId: String? = null) {
    // H1 修复: 对 LLM 输出的 HTML 做基本清洗,移除 iframe/form/meta refresh/事件属性/伪协议
    val safeHtml = sanitizeHtml(html)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val htmlCd = stringResource(R.string.markdown_html_cd)
    // v1.0.92: 卡桥 — 仅当调用方提供回传回调时注入(引导脚本在清洗后拼接,不受清洗影响)
    // v2.6.8: 桥的有无只影响“能不能回传”，不决定 JS 开关（见下方 javaScriptEnabled）。
    // v2.0: 卡桥同时携带数据读取能力(卡片数据绑定)
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = org.koin.compose.koinInject()
    val bridge = remember(onCardAction, cardDataStore, inlineCardData, cardId) {
        onCardAction?.let {
            MuseCardBridge(
                onAction = it,
                readCardData = { id -> inlineCardData ?: cardDataStore?.get(id) },
                cardId = cardId,
            )
        }
    }
    val bridgeBoot = if (bridge != null) "<script>$CARD_BRIDGE_BOOTSTRAP_JS</script>" else ""
    // v2.6.6: 注入统一主题令牌 + 组件片段样式（跟随深浅色，模型只需用变量/class）。
    // 同时注入 CSP：仅允许本地资源与内联样式/脚本（卡片交互需要 inline script + bridge），
    // 禁一切外部网络（img/script/style/connect/frame 均限 'self' 与 data:）。
    val wrappedHtml = """
        <html><head><meta charset="UTF-8">${MuseCardTheme.VIEWPORT_META}${MuseCardTheme.CSP_META}${MuseCardTheme.styleTag}</head>
        <body>$bridgeBoot$safeHtml<script>$HEIGHT_OBSERVER_SCRIPT</script></body></html>
    """.trimIndent()
    // v2.6.6: 高度自适应 —— 有 JS 时取真实内容高度（带上下限）；无 JS 时回退固定最小高。
    var contentHeightPx by remember(wrappedHtml) { mutableStateOf(0) }
    val density = LocalDensity.current
    // v2.6.7: 除了 onPageFinished 的首测，还接受卡片自己上报的高度（ResizeObserver）。
    // 修「大卡片被挤压」：异步渲染/展开后高度变大，旧逻辑永远用首测值裁切。
    // v2.6.9: 高度改由【专用接口】上报，与交互桥解耦。
    // 旧实现挂 bridge.onHeightChanged，而没有回传回调的渲染路径不注册桥，
    // ResizeObserver 的调用被静默吞掉 → 高度只剩 onPageFinished 那一次首测，
    // 卡片脚本异步建 DOM 的话首测量小，最终就固定成一个压缩小盒。
    val heightSink = remember { CardHeightSink() }
    androidx.compose.runtime.LaunchedEffect(heightSink) {
        heightSink.onHeightChanged = { px -> if (px > 0) contentHeightPx = px }
    }
    androidx.compose.runtime.DisposableEffect(heightSink) {
        onDispose { heightSink.onHeightChanged = null }
    }
    // v2.6.7: 卡片高度完全由内容决定，**内部不滚动**。
    //
    // 旧实现：未测到高度时用 `heightIn(min = 240.dp)`，而 WebView 默认允许滚动，
    // 于是内容超过 240dp 就出现内部滚动条 —— 用户看到的是“卡片里有个小窗口”，
    // 既不好看也抢走了外层消息列表的滚动。
    //
    // 现在的取向：高度是内容的函数（这正是模型期望的语义），
    // 外层聊天列表负责滚动，卡片自己永不滚。
    val heightModifier = if (contentHeightPx > 0) {
        Modifier.heightIn(min = 1.dp, max = 20000.dp)
            .height(with(density) { contentHeightPx.toDp() })
    } else {
        // 首帧未知高度时的占位。取小值而非 240dp：宁可先矮一下再长高，
        // 也不要先给一个大盒再堆内容（那会闪一下滚动条）。
        Modifier.heightIn(min = 80.dp, max = 20000.dp)
    }
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 WebView 残留资源占用问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = wrappedHtml,
        baseUrl = null,
        // v2.6.8: JS 始终启用 —— 与桥解耦。理由同 SvgCard：
        // 卡片自身的静态脚本/动画/样式全靠 JS，“有没有回传回调”不该决定它能不能动。
        // 无桥时 window.muse 不存在，回传调用失败但不影响渲染；
        // 安全边界（CSP connect-src 'none' + 导航全拦截 + 文件访问关闭）不变。
        javaScriptEnabled = true,
        webViewClient = RichContentWebViewClient(),
        onWebViewCreated = { webView ->
            // v2.6.9: 高度接口无条件注册（不管有没有交互桥）。
            webView.addJavascriptInterface(heightSink, CardHeightSink.NAME)
            bridge?.let { webView.addJavascriptInterface(it, MuseCardBridge.BRIDGE_NAME) }
        },
        // v2.6.8: 首测不再以桥为前提 —— JS 已始终启用，height 是内容自身的属性，
        // 不该因为“这次没有回传回调”就回退到固定高（那会让无桥卡被裁切）。
        onPageFinished = { wv, _ ->
            wv.post {
                wv.evaluateJavascript(
                    // v2.6.9: 量 body 的 scrollHeight/offsetHeight 取大值。
                    // 不用 documentElement.scrollHeight —— 内容比视口矮时它等于视口高，
                    // 会让卡片永远缩不回去。
                    "document.body ? Math.ceil(Math.max(document.body.scrollHeight, " +
                        "document.body.offsetHeight)) : 0",
                ) { result ->
                    result?.trim()?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { contentHeightPx = it }
                }
            }
        },
        // M-MD9 修复: height 改为 heightIn(min=...)
        // M5 修复: 加 contentDescription
        modifier = Modifier
            .fillMaxWidth()
            .then(heightModifier)
            .semantics { contentDescription = htmlCd },
    )
}

@Composable
private fun ChartCard(json: String) {
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val chartCd = stringResource(R.string.markdown_chart_cd, json)
    // P3-2: 错误前缀从资源注入
    val errorPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
    // Phase 2: HTML 构建抽出为纯函数,卡片与全屏预览共用同一份渲染页面。
    val html = remember(json) { buildChartHtml(json, errorPrefix) }
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 Chart.js 动画继续运行耗电的问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        // Chart.js 需要 JS
        javaScriptEnabled = true,
        webViewClient = RichContentWebViewClient(),
        // M-MD9 修复: height 改为 heightIn(min=...)
        // M5 修复: 加 contentDescription
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp)
            .semantics { contentDescription = chartCd },
    )
}

/**
 * 构建 Chart.js 渲染页面(卡片与全屏预览共用)。
 *
 * v1.97: Chart.js 从本地 assets/vendor 加载,解决 CDN 在中国不可用的问题。
 * LLM 输出的 JSON 直接作为 Chart.js 配置传入(new Chart(ctx, config))。
 */
private fun buildChartHtml(json: String, errorPrefix: String): String {
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), json)
    // P3-2: JS 错误前缀从资源注入(JSON 编码为 JS 字面量),消除硬编码中文
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    return """
        <html><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body { margin: 0; padding: 8px; background: transparent; }
            #wrap { position: relative; width: 100%; height: 240px; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px; white-space: pre-wrap; padding: 8px; }
        </style>
        <script src="vendor/chart.umd.min.js"></script>
        </head><body>
        <div id="wrap"><canvas id="cv"></canvas></div>
        <div id="err"></div>
        <script>
            try {
                var raw = $encoded;
                var config = JSON.parse(raw);
                if (!config.type) { config.type = 'bar'; }
                var ctx = document.getElementById('cv');
                new Chart(ctx, config);
            } catch(e) {
                document.getElementById('wrap').style.display = 'none';
                document.getElementById('err').textContent = $encodedPrefix + e.message + '\n\n原始数据:\n' + raw;
            }
        </script>
        </body></html>
    """.trimIndent()
}

/**
 * 构建 Mermaid 渲染页面(全屏预览用)。
 * P3-2: 卡片渲染已收敛到 [io.zer0.muse.ui.markdown.ChartRenderer.MermaidBlock]
 * (自带轮询取高与资源化错误前缀),此处仅保留全屏预览的静态 WebView 页面。
 *
 * v1.97: mermaid.js 从本地 assets/vendor 加载;mermaid 源码通过 JSON 编码注入 JS,避免 XSS。
 */
private fun buildMermaidHtml(mermaid: String, errorPrefix: String): String {
    // JSON 编码 mermaid 源码,安全注入 JS 字符串
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), mermaid)
    // P3-2: 错误前缀从资源注入,消除硬编码中文('渲染失败: ')
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    return """
        <html><head><meta charset="UTF-8">
        <style>
            body { margin: 0; padding: 8px; background: transparent; display: flex; justify-content: center; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px; white-space: pre-wrap; }
        </style>
        <script src="vendor/mermaid.min.js"></script>
        </head><body>
        <div id="out"></div>
        <div id="err"></div>
        <script>
            try {
                // M1 修复: securityLevel 改为 strict,禁用 click 回调与 HTML 嵌入,降低 LLM 内容风险
                mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });
                var src = $encoded;
                mermaid.render('mmd', src).then(function(res) {
                    document.getElementById('out').innerHTML = res.svg;
                }).catch(function(e) {
                    document.getElementById('err').textContent = $encodedPrefix + e.message;
                });
            } catch(e) {
                document.getElementById('err').textContent = $encodedPrefix + e.message;
            }
        </script>
        </body></html>
    """.trimIndent()
}
