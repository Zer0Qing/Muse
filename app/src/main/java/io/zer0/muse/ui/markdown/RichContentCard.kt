package io.zer0.muse.ui.markdown

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

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
 * @param onHtmlPreview HTML/SVG 全屏预览回调,参数为完整 HTML 源码
 *         (SVG 会先包装为完整 HTML 再回调)。仅 svg/html 语言触发,其余语言忽略。
 * @param showPreviewButton 是否显示右上角"全屏预览"按钮。调用方无导航能力时(如 ArtifactViewer
 *         弹窗内)可置为 false,避免出现无效按钮。
 */
@Composable
internal fun RichContentCard(
    language: String,
    content: String,
    modifier: Modifier = Modifier,
    onHtmlPreview: (String) -> Unit = {},
    showPreviewButton: Boolean = true,
) {
    // 仅 HTML / SVG 且调用方未禁用时显示全屏预览按钮
    val showPreview = showPreviewButton && (language == "html" || language == "svg")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 头部行:左侧语言标签 + 右侧预览按钮(仅 HTML/SVG)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (language) {
                        "svg" -> stringResource(R.string.markdown_svg_label)
                        "html" -> stringResource(R.string.markdown_html_label)
                        "chart" -> stringResource(R.string.markdown_chart_label)
                        "mermaid" -> stringResource(R.string.markdown_mermaid_label)
                        else -> language.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (showPreview) {
                    IconButton(
                        onClick = {
                            // SVG 包装为完整 HTML 后再回调,保证 HtmlPreviewScreen 直接渲染
                            val fullHtml = if (language == "svg") {
                                "<html><body style=\"margin:0;padding:8px;\">$content</body></html>"
                            } else {
                                content
                            }
                            onHtmlPreview(fullHtml)
                        },
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = stringResource(R.string.html_preview_button_cd),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            when (language) {
                "svg" -> SvgCard(content)
                "html" -> HtmlCard(content)
                "chart" -> ChartCard(content)
                "mermaid" -> MermaidCard(content)
                else -> Text(content, style = MaterialTheme.typography.bodySmall)
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
private val EVENT_ATTR_REGEX = Regex("""(?i)\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""")
private val JS_PROTO_REGEX = Regex("""(?i)(href|src)\s*=\s*("javascript:[^"]*"|'javascript:[^']*')""")

/**
 * H1 修复: 对 LLM 输出的 HTML/SVG 做基本清洗,移除高危标签与属性。
 *
 * 已知限制(正则清洗的固有限制):
 *  - 无法覆盖所有 XSS 向量(如畸形标签、Unicode 绕过、属性值内嵌套引号等);
 *  - 但能拦截常见威胁:<meta http-equiv="refresh">、<iframe>、<form>、<object>/<embed>、事件属性 onXxx、javascript: 伪协议。
 * 项目无 Jsoup 依赖,这里用正则做基础防护;如需更强保障应引入 HTML 解析库或打包离线资源。
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
    // 移除事件属性 onXxx="..." / onXxx='...' / onXxx=bar
    s = EVENT_ATTR_REGEX.replace(s, "")
    // 移除 javascript: 伪协议(href/src="javascript:...")
    s = JS_PROTO_REGEX.replace(s, """$1="#" """)
    return s
}

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
}

@Composable
private fun SvgCard(svg: String) {
    // H1 修复: 对 LLM 输出的 SVG 做基本清洗,移除事件属性/伪协议等
    val safeSvg = sanitizeHtml(svg)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val svgCd = stringResource(R.string.markdown_svg_cd)
    // 把 SVG 包进 HTML 里,用 WebView 渲染
    val html = """
        <html><body style="margin:0;padding:8px;background:transparent;">
        $safeSvg
        </body></html>
    """.trimIndent()
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 WebView 残留资源占用问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = null,
        // H1 修复: SVG 不需要 JS,保持禁用
        javaScriptEnabled = false,
        webViewClient = RichContentWebViewClient(),
        // M-MD9 修复: height 改为 heightIn(min=...),允许内容超出时自适应
        // M5 修复: 加 contentDescription 供无障碍朗读
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .semantics { contentDescription = svgCd },
    )
}

@Composable
private fun HtmlCard(html: String) {
    // H1 修复: 对 LLM 输出的 HTML 做基本清洗,移除 iframe/form/meta refresh/事件属性/伪协议
    val safeHtml = sanitizeHtml(html)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val htmlCd = stringResource(R.string.markdown_html_cd)
    val wrappedHtml = """
        <html><head><meta charset="UTF-8">
        <style>
            body { margin: 8px; font-family: -apple-system, sans-serif; color: #333; }
            @media (prefers-color-scheme: dark) {
                body { color: #eee; background: transparent; }
            }
        </style></head>
        <body>$safeHtml</body></html>
    """.trimIndent()
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 WebView 残留资源占用问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = wrappedHtml,
        baseUrl = null,
        // H1 修复: HTML 卡片禁用 JS,保持禁用
        javaScriptEnabled = false,
        webViewClient = RichContentWebViewClient(),
        // M-MD9 修复: height 改为 heightIn(min=...)
        // M5 修复: 加 contentDescription
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .semantics { contentDescription = htmlCd },
    )
}

@Composable
private fun ChartCard(json: String) {
    // v1.97: Chart.js 改为从本地 assets 加载,解决 CDN 在中国不可用的问题。
    // LLM 输出的 JSON 直接作为 Chart.js 配置传入(new Chart(ctx, config))。
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), json)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val chartCd = stringResource(R.string.markdown_chart_cd, json)
    val html = """
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
                document.getElementById('err').textContent = '图表渲染失败: ' + e.message + '\n\n原始数据:\n' + raw;
            }
        </script>
        </body></html>
    """.trimIndent()
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
 * v1.57: Mermaid 图表卡片。
 *
 * v1.97: mermaid.js 改为从本地 assets 加载,解决 CDN 在中国不可用的问题。
 * mermaid 内容通过 JSON 编码注入 JS,避免 XSS。
 */
@Composable
private fun MermaidCard(mermaid: String) {
    // JSON 编码 mermaid 源码,安全注入 JS 字符串
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), mermaid)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val mermaidCd = stringResource(R.string.markdown_mermaid_cd, mermaid)
    val html = """
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
                    document.getElementById('err').textContent = '渲染失败: ' + e.message;
                });
            } catch(e) {
                document.getElementById('err').textContent = '渲染失败: ' + e.message;
            }
        </script>
        </body></html>
    """.trimIndent()
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 mermaid.js 渲染相关 JS 定时器继续运行耗电的问题(原 L9 已知限制已消除)。
    // 原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        // Mermaid 必须 JS
        javaScriptEnabled = true,
        webViewClient = RichContentWebViewClient(),
        // M-MD9 修复: height 改为 heightIn(min=...)
        // M5 修复: 加 contentDescription
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp)
            .semantics { contentDescription = mermaidCd },
    )
}
