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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentSpaceEngine
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.markdown.card.CardHost
import io.zer0.muse.ui.markdown.card.CardLanguage
import io.zer0.muse.ui.markdown.card.CardPushToken
import io.zer0.muse.ui.markdown.card.CardRuntime
import io.zer0.muse.ui.markdown.card.CardSpec
import io.zer0.muse.ui.markdown.card.CardStyles
import io.zer0.muse.ui.markdown.card.CardTheme
import io.zer0.muse.ui.markdown.card.parseCardSize
import io.zer0.muse.ui.screen.HtmlPreviewScreen
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import org.koin.compose.koinInject
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
internal val LocalRichPreviewHandler = compositionLocalOf<((language: String, content: String, title: String?) -> Unit)?> { null }

internal val NO_OP_HTML_PREVIEW: (String) -> Unit = {}

// v2.6.0: 新增 inlineCardData 参数后,签名漂移使既有 baseline 条目失配。
// 这些为屏幕级 @Composable 函数的固有结构(长函数/多参数/复杂条件/命名),按点豁免。
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun RichContentCard(
    language: String,
    content: String,
    modifier: Modifier = Modifier,
    onHtmlPreview: (String) -> Unit = NO_OP_HTML_PREVIEW,
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
    /**
     * v2.6.11: 模型声明的卡片尺寸（show_card 的 height 参数）。
     *
     * 卡片被裁的根因之一是「高度只能靠测」。模型生成时是知道这张卡要多高的，
     * 让声明优先于测量：说了就按说的来，没说才去测。
     *
     * @param onPinToSpaces 可选：把这张卡片钉入 Spaces。null 时对带 cardId 的 HTML/SVG 卡片
     *        启用内置钉入动作，已经钉入的卡片不再重复展示入口。
     * @param onRefineInChat 可选：带着这张卡片回到对话里继续修改。
     */
    heightSpec: String? = null,
    onPinToSpaces: (() -> Unit)? = null,
    onRefineInChat: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 渐进渲染节流 —— 流式期间源码持续增长，每交一次新内容给 WebView 就是一次整页重载
    // （重解析 HTML + 重跑卡片脚本 + 图表重新初始化）。这里只在翻倍档位上推进快照：
    // 一张 8KB 图表卡片的整页重载从 68 次降到 7 次，见 [ProgressiveCard]。
    // 档位不变时返回的是等值前缀，下游 remember 与 WebView 的「内容未变不重载」都能命中。
    // 流式结束后（progressive=false）直接用最新完整内容。
    val effectiveContent = if (!progressive) content else ProgressiveCard.snapshot(content)
    // 统一全屏预览状态：无论 chart/mermaid 还是无外部导航回调的 html/svg，都能在卡片内打开全屏预览
    var showFullscreenPreview by remember { mutableStateOf(false) }
    val localPreviewHandler = LocalRichPreviewHandler.current
    // v2.x 统一化: 行内动作收敛为「更多」菜单(防止窄屏文本行被多个按钮挤压)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    val supportsPreview = richContentSupportsPreview(language)
    val typeLabel = when (language.lowercase().trim()) {
        "svg" -> stringResource(R.string.markdown_svg_label)
        "html" -> stringResource(R.string.markdown_html_label)
        "chart" -> stringResource(R.string.markdown_chart_label)
        "mermaid" -> stringResource(R.string.markdown_mermaid_label)
        else -> language.uppercase()
    }

    // 内置钉入入口只对可渲染为卡片的 HTML/SVG 生效；已经有 cardId 的卡片先查一次
    // 是否已经在 Spaces 里，避免在「小应用」页重复出现钉入按钮。
    val appKoin = remember { org.koin.core.context.GlobalContext.getOrNull() }
    val stableCardId = cardId?.takeIf { it.isNotBlank() }
    val normalizedLanguage = language.lowercase().trim()
    val cardEntityDao = remember(appKoin, stableCardId) {
        if (stableCardId == null) {
            null
        } else {
            appKoin?.getOrNull<io.zer0.muse.data.session.MuseDb>()?.cardEntityDao()
        }
    }
    val spaceEngine = remember(appKoin, stableCardId, normalizedLanguage) {
        if (stableCardId == null || normalizedLanguage !in PINNABLE_LANGUAGES) {
            null
        } else {
            appKoin?.getOrNull<AgentSpaceEngine>()
        }
    }
    var alreadyPinned by remember(stableCardId) { mutableStateOf(false) }
    LaunchedEffect(stableCardId) {
        alreadyPinned = stableCardId?.let { id -> cardEntityDao?.getById(id)?.spacePinned } == true
    }
    val coroutineScope = rememberCoroutineScope()
    val builtInPinAction: (() -> Unit)? = if (
        onPinToSpaces == null &&
        !alreadyPinned &&
        stableCardId != null &&
        spaceEngine != null &&
        normalizedLanguage in PINNABLE_LANGUAGES
    ) {
        val targetCardId = stableCardId
        val engine = spaceEngine
        {
            coroutineScope.launch {
                runCatching {
                    engine.pinMessageCard(
                        messageId = targetCardId,
                        title = AgentSpaceEngine.deriveSpaceTitle(content, normalizedLanguage == "svg"),
                        code = content,
                        isSvg = normalizedLanguage == "svg",
                        dataJson = inlineCardData,
                    )
                }.onSuccess { card ->
                    if (card != null) alreadyPinned = true
                }.onFailure {
                    Logger.w("RichContentCard", "pin to spaces failed: " + it.message, it)
                }
            }
        }
    } else {
        null
    }
    val pinToSpaces = onPinToSpaces ?: builtInPinAction
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
                if (pinToSpaces != null) {
                    MuseTactileButton(
                        icon = MuseIcons.pin,
                        onClick = pinToSpaces,
                        contentDescription = stringResource(R.string.agent_space_pin_card_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                if (onRefineInChat != null) {
                    MuseTactileButton(
                        icon = MuseIcons.edit,
                        onClick = onRefineInChat,
                        contentDescription = stringResource(R.string.agent_space_refine_card_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
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
            // 有 cardId 的卡片始终装配数据桥：Spaces 常驻卡不带交互回调，但仍要能读
            // CardDataStore 里的绑定数据并接收后台刷新推送。无回调时用无操作接收器。
            val dataBridgeAction = onCardAction ?: if (cardId != null) NoopCardAction else null
            when (language.lowercase().trim()) {
                "svg" -> SvgCard(effectiveContent, dataBridgeAction, inlineCardData, cardId, heightSpec)
                "html" -> HtmlCard(effectiveContent, dataBridgeAction, inlineCardData, cardId, heightSpec)
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
                        // v2.6.13: 收藏排在最前 —— 卡片是“值得留着的东西”，
                        // 保存为文件是导出手段，放到第二位。
                        MuseActionSheetRow(
                            icon = MuseIcons.star,
                            text = stringResource(R.string.card_favorite),
                            onClick = {
                                menuExpanded = false
                                onCardAction.invoke(CardAction.Favorite(language, content, typeLabel, cardId))
                            },
                        )
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
                                when {
                                    localPreviewHandler != null -> {
                                        localPreviewHandler(normalizedLanguage, content, typeLabel)
                                    }
                                    normalizedLanguage in RICH_LOCAL_PREVIEW_LANGUAGES -> {
                                        showFullscreenPreview = true
                                    }
                                    onHtmlPreview !== NO_OP_HTML_PREVIEW -> {
                                        val fullHtml = wrapRichContentForExternalPreview(normalizedLanguage, content)
                                        onHtmlPreview(fullHtml)
                                    }
                                    else -> {
                                        showFullscreenPreview = true
                                    }
                                }
                            },
                        )
                    }
                }
            },
            // 39①: 这是动作菜单，不是确认弹窗。卡片常常已经嵌在产物弹层里，底层已有一个
            // 「关闭」；这里再带取消/关闭就同屏两个关闭。去掉按钮行，点动作即关、点外部/返回也关。
            dismissText = null,
            onConfirm = null,
            onDismiss = null,
        )
    }

    // 统一走全屏预览宿主（html/svg/chart/mermaid 共用同一套带主题/源码/分享的预览页）
    if (showFullscreenPreview && supportsPreview) {
        Dialog(
            onDismissRequest = { showFullscreenPreview = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            MuseDialogWindowEffect(forceFullScreen = true)
            HtmlPreviewScreen(
                html = content,
                language = normalizedLanguage,
                title = typeLabel,
                onBack = { showFullscreenPreview = false },
            )
        }
    }
}

/**
 * 支持全屏预览的富内容语言(html/svg 走外部导航预览;chart/mermaid 走卡片内全屏对话框)。
 * Phase 2: 从原"仅 html/svg"扩展到 chart/mermaid。
 */
internal fun richContentSupportsPreview(language: String): Boolean = language.lowercase().trim() in RICH_PREVIEW_LANGUAGES

/** 全屏预览语言集合。 */
private val RICH_PREVIEW_LANGUAGES = setOf("html", "svg", "chart", "mermaid")

/** 可钉入 Spaces 的语言：只有真正的 HTML/SVG 卡片源码能作为常驻小应用渲染。 */
private val PINNABLE_LANGUAGES = setOf("html", "svg")

/** 只读宿主的数据桥接收器：卡片可读绑定数据，但没有回传动作。 */
private val NoopCardAction: (CardAction) -> Unit = {}

/**
 * 依赖本地 assets 脚本渲染、需在卡片内全屏 WebView 预览的语言。
 * HtmlPreviewScreen 以 about:blank 为 baseUrl,相对路径的 vendor 脚本无法加载。
 */
internal val RICH_LOCAL_PREVIEW_LANGUAGES = setOf("chart", "mermaid")

/** 包装 SVG 为基础 HTML（供老签名回调兼容用）。 */
internal fun wrapRichContentForExternalPreview(language: String, content: String): String =
    if (language.lowercase().trim() == "svg" && !content.trimStart().startsWith("<html", ignoreCase = true)) {
        "<html><body style=\"margin:0;padding:8px;\">$content</body></html>"
    } else {
        content
    }

/**
 * 统一全屏富内容预览预处理（[HtmlPreviewScreen] 与内联全屏共用）：
 * 注入当前主题 CSS 变量与安全清洗策略，避免深色模式黑底黑字或脚本外逸。
 * 返回 `(assembledHtml, baseUrl)`。
 */
internal fun prepareRichPreview(
    language: String,
    rawContent: String,
    colorScheme: ColorScheme,
    errorPrefix: String,
    rawDataPrefix: String,
): Pair<String, String> {
    val lang = language.lowercase().trim()
    return when (lang) {
        "chart" -> buildChartHtml(rawContent, errorPrefix, rawDataPrefix, colorScheme) to "file:///android_asset/"
        "mermaid" -> buildMermaidHtml(rawContent, errorPrefix, colorScheme) to "file:///android_asset/"
        else -> {
            val wrapped = wrapRichContentForExternalPreview(lang, rawContent)
            val themeCss = CardTheme.cssOf(colorScheme)
            val componentCss = CardStyles.ALL
            val cleaned = sanitizeHtml(wrapped)
            val injectedStyle = "<style>$themeCss\n$componentCss\n" +
                "html,body{margin:0;color:var(--muse-fg);background:var(--muse-surface);}</style>"
            val htmlOpenMatch = Regex("(?i)<html[^>]*>").find(cleaned)
            val assembled = when {
                cleaned.contains("</head>", ignoreCase = true) ->
                    cleaned.replaceFirst(Regex("(?i)</head>"), "$injectedStyle</head>")
                htmlOpenMatch != null ->
                    cleaned.replaceRange(htmlOpenMatch.range, "${htmlOpenMatch.value}<head>$injectedStyle</head>")
                else ->
                    "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                        "$injectedStyle</head><body>$cleaned</body></html>"
            }
            assembled to "about:blank"
        }
    }
}

/** 复制富内容源码到剪贴板,并 Toast 反馈。 */
private fun copyRichContentSource(context: Context, source: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("muse_rich_content", source))
    MuseToast.show(context.getString(R.string.markdown_source_copied))
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
internal fun sanitizeHtml(input: String): String {
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

/**
 * [sanitizeHtml] 的进程级 LRU。
 *
 * 清洗是 7 段正则 + 一次整篇标签重写，纯函数且只与输入有关。而这条路径会被
 * 反复走：消息滚出屏幕再滚回来（LazyColumn 销毁并重建组合）、深浅色切换
 * （themeCss 变 → 重新装配 HTML）都会对**同一份卡片源码**再洗一遍。
 *
 * 键用内容原文而不是哈希：正则清洗的结果不允许有碰撞风险，而条目上限很小
 * （[MAX_ENTRIES]），把原文留在内存里是可控的代价。超长内容（[MAX_CONTENT_LENGTH]
 * 以上）不进缓存，避免大卡片把缓存撑满。
 */
internal object SanitizedHtmlCache {

    private const val MAX_ENTRIES = 12
    private const val MAX_CONTENT_LENGTH = 64_000

    private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_ENTRIES
    }

    /** 取清洗结果；同一份内容第二次进来直接命中原对象。 */
    fun get(code: String): String {
        if (code.length > MAX_CONTENT_LENGTH) return sanitizeHtml(code)
        return synchronized(entries) {
            entries.getOrPut(code) { sanitizeHtml(code) }
        }
    }

    /** 仅供测试：当前条目数。 */
    internal fun size(): Int = synchronized(entries) { entries.size }

    /** 仅供测试：清空。 */
    internal fun clear() = synchronized(entries) { entries.clear() }
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
internal class RichContentWebViewClient(
    private val onPageFinishedCallback: (() -> Unit)? = null,
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback?.invoke()
    }

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

/**
 * 订阅这张卡自己的绑定数据，产出推送凭据。
 *
 * 只盯 `cardId` 这一个键：整张 cardId→data 映射变了但本卡没变时不产出新凭据，
 * 也就不会给卡片发无意义的通知。存储缺失（无 CardDataStore 的宿主）或卡无身份时
 * 返回 null，卡片侧据此退回 800ms 轮询。
 */
@Composable
private fun rememberCardPushToken(store: io.zer0.muse.data.card.CardDataStore?, cardId: String?): CardPushToken? {
    if (store == null || cardId.isNullOrBlank()) return null
    val value by remember(store, cardId) {
        store.data.map { it[cardId] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = store.get(cardId))
    return remember(value) { CardPushToken(value) }
}

@Suppress("FunctionNaming")
@Composable
private fun SvgCard(
    svg: String,
    onCardAction: ((CardAction) -> Unit)? = null,
    inlineCardData: String? = null,
    cardId: String? = null,
    heightSpec: String? = null,
) {
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = koinInject()
    val silentAi: io.zer0.muse.data.card.CardSilentAiCoordinator? = koinInject()
    val appScope: CoroutineScope = koinInject()
    val store = cardDataStore
    // 读数据回调只依赖存储与内联数据，按这两个缓存：每帧新建会让卡片桥
    // （以它为 key 的 remember）重建，进而重拼整段卡片 HTML。
    // 内联数据会随 saveState / 数据推送更新，但读数据回调必须保持引用稳定：
    // 它一变，卡片桥跟着重建，整段 HTML 重拼、WebView 重载，卡片内存状态全部丢失。
    val inlineDataState = rememberUpdatedState(inlineCardData)
    val readData = remember(store) { { id: String -> store?.get(id) ?: inlineDataState.value } }
    val onSilentAsk = remember(silentAi, cardId) {
        silentAi?.let { coord ->
            val handler: suspend (String?, String) -> String = { id, payload ->
                coord.handleAsk(id ?: cardId, payload)
            }
            handler
        }
    }
    val onSaveState = remember(silentAi, appScope) {
        silentAi?.let { coord ->
            val handler: (String, String) -> Unit = { id, stateJson ->
                appScope.launch(start = CoroutineStart.UNDISPATCHED) { coord.saveCardState(id, stateJson) }
            }
            handler
        }
    }
    CardRuntime(
        spec = CardSpec(
            language = CardLanguage.SVG,
            code = svg,
            size = parseCardSize(heightSpec),
            data = inlineCardData,
        ),
        host = CardHost(
            onAction = onCardAction,
            readData = readData,
            cardId = cardId,
            pushToken = rememberCardPushToken(store, cardId),
            onSilentAsk = onSilentAsk,
            onSaveState = onSaveState,
        ),
    )
}

@Suppress("FunctionNaming")
@Composable
private fun HtmlCard(
    html: String,
    onCardAction: ((CardAction) -> Unit)? = null,
    inlineCardData: String? = null,
    cardId: String? = null,
    heightSpec: String? = null,
) {
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = koinInject()
    val silentAi: io.zer0.muse.data.card.CardSilentAiCoordinator? = koinInject()
    val appScope: CoroutineScope = koinInject()
    val store = cardDataStore
    // 同 SvgCard：读数据回调必须跨重组稳定，否则卡片桥跟着重建。
    // 内联数据会随 saveState / 数据推送更新，但读数据回调必须保持引用稳定：
    // 它一变，卡片桥跟着重建，整段 HTML 重拼、WebView 重载，卡片内存状态全部丢失。
    val inlineDataState = rememberUpdatedState(inlineCardData)
    val readData = remember(store) { { id: String -> store?.get(id) ?: inlineDataState.value } }
    val onSilentAsk = remember(silentAi, cardId) {
        silentAi?.let { coord ->
            val handler: suspend (String?, String) -> String = { id, payload ->
                coord.handleAsk(id ?: cardId, payload)
            }
            handler
        }
    }
    val onSaveState = remember(silentAi, appScope) {
        silentAi?.let { coord ->
            val handler: (String, String) -> Unit = { id, stateJson ->
                appScope.launch(start = CoroutineStart.UNDISPATCHED) { coord.saveCardState(id, stateJson) }
            }
            handler
        }
    }
    CardRuntime(
        spec = CardSpec(
            language = CardLanguage.HTML,
            code = html,
            size = parseCardSize(heightSpec),
            data = inlineCardData,
        ),
        host = CardHost(
            onAction = onCardAction,
            readData = readData,
            cardId = cardId,
            pushToken = rememberCardPushToken(store, cardId),
            onSilentAsk = onSilentAsk,
            onSaveState = onSaveState,
        ),
    )
}

@Composable
private fun ChartCard(json: String) {
    val chartCd = stringResource(R.string.markdown_chart_cd, json)
    val errorPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
    val rawDataPrefix = stringResource(R.string.artifact_raw_data_prefix)
    val colorScheme = MaterialTheme.colorScheme
    val html = remember(json, errorPrefix, rawDataPrefix, colorScheme) {
        buildChartHtml(json, errorPrefix, rawDataPrefix, colorScheme)
    }
    var chartHeightPx by remember(html) { mutableStateOf(0) }
    val heightSink = remember { CardHeightSink() }
    LaunchedEffect(heightSink) {
        heightSink.onHeightChanged = { px -> if (px > 0) chartHeightPx = px }
    }
    androidx.compose.runtime.DisposableEffect(heightSink) {
        onDispose { heightSink.onHeightChanged = null }
    }
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        javaScriptEnabled = true,
        webViewClient = remember { RichContentWebViewClient() },
        onWebViewCreated = { webView ->
            webView.addJavascriptInterface(heightSink, CardHeightSink.NAME)
        },
        scrollable = false,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (chartHeightPx > 0) {
                    Modifier.height(chartHeightPx.dp)
                } else {
                    Modifier.height(260.dp)
                },
            )
            .semantics { contentDescription = chartCd },
    )
}

private fun Color.toHtmlHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * 构建 Chart.js 渲染页面(卡片与全屏预览共用)，按当前 Compose 主题注入文字与网格色。
 */
private fun buildChartHtml(json: String, errorPrefix: String, rawDataPrefix: String, colorScheme: ColorScheme): String {
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), json)
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    val encodedRawPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), rawDataPrefix)
    val textColor = colorScheme.onSurface.toHtmlHex()
    val gridColor = colorScheme.outlineVariant.toHtmlHex()
    val errorColor = colorScheme.error.toHtmlHex()
    return """
        <html><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body { margin: 0; padding: 0; background: transparent; }
            body { color: $textColor; font-family: -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif; font-size: 14px; }
            #wrap { position: relative; width: 100%; height: 240px; }
            #err { color: $errorColor; font-family: monospace; font-size: 12px; white-space: pre-wrap; padding: 8px; }
        </style>
        <script src="vendor/chart.umd.min.js"></script>
        </head><body>
        <div style="padding:8px">
          <div id="wrap"><canvas id="cv"></canvas></div>
          <div id="err"></div>
        </div>
        <script>
            try {
                Chart.defaults.color = '$textColor';
                Chart.defaults.borderColor = '$gridColor';
                var raw = $encoded;
                var config = JSON.parse(raw);
                if (!config.type) { config.type = 'bar'; }
                var ctx = document.getElementById('cv');
                new Chart(ctx, config);
            } catch(e) {
                document.getElementById('wrap').style.display = 'none';
                document.getElementById('err').textContent = $encodedPrefix + e.message + '\n\n' + $encodedRawPrefix + raw;
            }
        </script>
        <script>$HEIGHT_OBSERVER_SCRIPT</script>
        </body></html>
    """.trimIndent()
}

/**
 * 构建 Mermaid 渲染页面（全屏预览用，跟随深浅色主题）。
 */
private fun buildMermaidHtml(mermaid: String, errorPrefix: String, colorScheme: ColorScheme): String {
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), mermaid)
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    val isDark = colorScheme.background.luminance() < 0.5f
    val mermaidTheme = if (isDark) "dark" else "neutral"
    val errorColor = colorScheme.error.toHtmlHex()
    return """
        <html><head><meta charset="UTF-8">
        <style>
            body { margin: 0; padding: 8px; background: transparent; display: flex; justify-content: center; }
            #err { color: $errorColor; font-family: monospace; font-size: 12px; white-space: pre-wrap; }
        </style>
        <script src="vendor/mermaid.min.js"></script>
        </head><body>
        <div id="out"></div>
        <div id="err"></div>
        <script>
            try {
                mermaid.initialize({ startOnLoad: false, theme: '$mermaidTheme', securityLevel: 'strict' });
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
