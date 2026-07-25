package io.zer0.muse.ui.markdown

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.ui.common.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.resume

/**
 * Mermaid 代码块关键字 — 用于 [isMermaidCodeBlock] 启发式识别。
 *
 * 当代码块没有显式 ```mermaid 语言标识时,若代码以这些关键字开头,也判定为 Mermaid。
 */
private val MERMAID_KEYWORDS = arrayOf(
    "graph", "flowchart", "sequenceDiagram", "classDiagram",
    "stateDiagram", "erDiagram", "gantt", "pie", "journey",
)

/**
 * PlantUML 源码起始标记 — 用于 [isPlantUmlCodeBlock] 启发式识别。
 */
private const val PLANTUML_START = "@startuml"

/**
 * Mermaid/PlantUML 渲染失败时的最大重试次数。
 * 超过后回退到代码块纯文本展示,避免无限重试。
 */
private const val MAX_RENDER_RETRY = 2

/**
 * Mermaid WebView 高度轮询间隔(毫秒)。
 * mermaid.js 渲染为异步,需轮询 DOM 取实际高度与状态。
 */
private const val MERMAID_POLL_INTERVAL_MS = 150L

/**
 * Mermaid WebView 高度轮询最大次数(超过判定为失败)。
 * 总等待时间约 = [MERMAID_POLL_INTERVAL_MS] * [MERMAID_POLL_MAX_ATTEMPTS]。
 */
private const val MERMAID_POLL_MAX_ATTEMPTS = 40

/**
 * 判断代码块是否为 Mermaid 图表。
 *
 * 判定规则(满足任一即可):
 *  - 语言标识为 "mermaid"(大小写不敏感,去空白);
 *  - 代码首行非空内容以 [MERMAID_KEYWORDS] 中任一关键字开头。
 *
 * @param lang 代码块声明的语言(可能为 null/空白)
 * @param code 代码块内容
 * @return true 表示应交给 [MermaidBlock] 渲染
 */
fun isMermaidCodeBlock(lang: String?, code: String): Boolean {
    if (lang != null && lang.trim().equals("mermaid", ignoreCase = true)) return true
    val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    val trimmed = firstLine.trim()
    return MERMAID_KEYWORDS.any { kw -> trimmed.startsWith(kw, ignoreCase = true) }
}

/**
 * 判断代码块是否为 PlantUML 图表。
 *
 * 判定规则(满足任一即可):
 *  - 语言标识为 "plantuml"(大小写不敏感,去空白);
 *  - 代码包含 [@startuml] 标记(PlantUML 源码必备)。
 *
 * @param lang 代码块声明的语言(可能为 null/空白)
 * @param code 代码块内容
 * @return true 表示应交给 [PlantUmlBlock] 渲染
 */
fun isPlantUmlCodeBlock(lang: String?, code: String): Boolean {
    if (lang != null && lang.trim().equals("plantuml", ignoreCase = true)) return true
    return code.contains(PLANTUML_START)
}

/**
 * 渲染 Mermaid 图表 — 用 WebView 加载 mermaid.js(本地 assets,无网络依赖)。
 *
 * 实现要点:
 *  - 启用 JavaScript,禁用文件/内容访问(安全纵深);
 *  - WebView 背景透明,适配深/浅色气泡;
 *  - mermaid 内容用 JSON 编码注入,避免 XSS;
 *  - 渲染完成后用 evaluateJavascript 轮询取实际高度,自适应 Compose 高度;
 *  - 加载中显示 [CircularProgressIndicator];失败时显示错误信息 + "显示源码"按钮回退。
 *
 * @param code Mermaid 源码(如 `graph TD; A-->B`)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidBlock(code: String) {
    // 渲染状态:0=加载中 1=成功 2=失败
    var renderState by remember(code) { mutableIntStateOf(0) }
    // WebView 实际高度(px)
    var contentHeightPx by remember(code) { mutableStateOf(0) }
    // 是否回退到源码显示
    var showSource by rememberSaveable(code) { mutableStateOf(false) }
    // 重试计数(避免失败后无限重试)
    var retryCount by remember(code) { mutableIntStateOf(0) }
    // 触发重渲染的 key(retryCount 变化时让 AndroidView 重新执行 update)
    var reloadKey by remember(code) { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val mermaidCd = stringResource(R.string.markdown_mermaid_cd, code.take(80))

    // mermaid 源码 JSON 编码,安全注入 JS 字符串
    val html = remember(code) {
        val encodedSrc = io.zer0.common.AppJson.encodeToString(String.serializer(), code)
        """
        <html><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body { margin: 0; padding: 8px; background: transparent; }
            body { display: flex; justify-content: center; }
            #out { display: inline-block; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px;
                   white-space: pre-wrap; padding: 8px; max-width: 100%; }
        </style>
        <script src="vendor/mermaid.min.js"></script>
        </head><body>
        <div id="out"></div>
        <div id="err"></div>
        <script>
            try {
                mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });
                var src = $encodedSrc;
                mermaid.render('mmd', src).then(function(res) {
                    document.getElementById('out').innerHTML = res.svg;
                }).catch(function(e) {
                    document.getElementById('err').textContent =
                        '渲染失败: ' + (e && e.message ? e.message : e);
                });
            } catch(e) {
                document.getElementById('err').textContent =
                    '渲染失败: ' + (e && e.message ? e.message : e);
            }
        </script>
        </body></html>
        """.trimIndent()
    }

    // 持有 WebView 引用,用于轮询 DOM 状态
    var webViewRef by remember(code) { mutableStateOf<WebView?>(null) }

    // 轮询 WebView 渲染结果:成功取高度,失败标记错误
    LaunchedEffect(reloadKey) {
        if (renderState != 0) return@LaunchedEffect
        var attempts = 0
        while (attempts < MERMAID_POLL_MAX_ATTEMPTS) {
            delay(MERMAID_POLL_INTERVAL_MS)
            val wv = webViewRef ?: run { attempts++; continue }
            // 用 suspendCancellableCoroutine 包装 evaluateJavascript,避免阻塞主线程
            val pollResult = suspendCancellableCoroutine { cont ->
                wv.post {
                    if (cont.isCompleted) return@post
                    wv.evaluateJavascript(
                        """
                        (function() {
                            var errEl = document.getElementById('err');
                            var outEl = document.getElementById('out');
                            if (errEl && errEl.textContent) return 'err';
                            if (outEl && outEl.innerHTML.trim()) {
                                return 'ok:' + document.body.scrollHeight;
                            }
                            return 'pending';
                        })();
                        """.trimIndent(),
                    ) { result ->
                        if (cont.isActive) cont.resume(result ?: "pending")
                    }
                }
            }
            when {
                pollResult == "'err'" || pollResult == "\"err\"" -> {
                    renderState = 2
                    return@LaunchedEffect
                }
                pollResult.startsWith("'ok:") -> {
                    // 解析高度:'ok:240' → 240
                    val heightStr = pollResult.removePrefix("'ok:").removeSuffix("'")
                    heightStr.toIntOrNull()?.let { contentHeightPx = it }
                    renderState = 1
                    return@LaunchedEffect
                }
                pollResult.startsWith("\"ok:") -> {
                    val heightStr = pollResult.removePrefix("\"ok:").removeSuffix("\"")
                    heightStr.toIntOrNull()?.let { contentHeightPx = it }
                    renderState = 1
                    return@LaunchedEffect
                }
            }
            attempts++
        }
        // 超时未拿到结果,判定失败
        renderState = 2
    }

    // 外层卡片:与 RichContentCard 风格一致(surfaceVariant 半透明背景 + medium 圆角)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
            // 头部标签
            ChartHeader(label = stringResource(R.string.markdown_mermaid_label))
            when {
                // 回退显示源码:展示原始代码 + 重试按钮
                showSource -> {
                    ChartSourceView(code = code, onRetry = {
                        showSource = false
                        renderState = 0
                        retryCount = 0
                        reloadKey++
                    })
                }
                renderState == 2 -> {
                    // 渲染失败:错误信息 + 显示源码按钮(失败次数未达上限时附加重试按钮)
                    ChartErrorView(
                        message = stringResource(R.string.markdown_chart_render_failed),
                        onShowSource = { showSource = true },
                        onRetry = if (retryCount < MAX_RENDER_RETRY) {
                            {
                                renderState = 0
                                retryCount++
                                reloadKey++
                            }
                        } else null,
                    )
                }
                else -> {
                    // 加载中 / 成功:WebView 渲染
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .then(
                                if (contentHeightPx > 0) {
                                    Modifier.height(with(density) { contentHeightPx.toDp() })
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
                        // 解决 Activity 后台时 mermaid.js 渲染相关 JS 定时器继续运行耗电的问题。
                        // 通过 onWebViewCreated / onWebViewReleased 维护 webViewRef 供轮询使用。
                        LifecycleAwareWebViewContainer(
                            htmlContent = html,
                            baseUrl = "file:///android_asset/",
                            javaScriptEnabled = true,
                            onWebViewCreated = { webViewRef = it },
                            onWebViewReleased = { webViewRef = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = mermaidCd },
                        )
                        // 加载中浮层:WebView 渲染完成前显示进度
                        if (renderState == 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = MuseShapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.markdown_chart_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染 PlantUML 图表 — 用 Coil 异步加载 plantuml.com 服务端返回的 SVG。
 *
 * 实现要点:
 *  - URL 用 `~1` 前缀简单模式:`https://www.plantuml.com/plantuml/svg/~1{URL_ENCODED_TEXT}`,
 *    PlantUML 服务端会直接对 URL 编码后的文本做渲染,无需客户端 deflate 编码;
 *  - 用 Coil [AsyncImage] 异步加载,加载中显示 [CircularProgressIndicator],
 *    失败时显示错误信息 + "显示源码"按钮回退。
 *
 * 已知限制: `~1` 模式对超长源码(>4KB URL 长度限制)不支持,需要走 deflate 编码;
 *           99% 的 LLM 输出图表在限制内,此处简化处理。
 *
 * @param code PlantUML 源码(含或不含 @startuml/@enduml 标记均可)
 */
@Composable
fun PlantUmlBlock(code: String) {
    val context = LocalContext.current
    // 是否回退到源码显示
    var showSource by rememberSaveable(code) { mutableStateOf(false) }
    // 加载状态:0=加载中 1=成功 2=失败
    var loadState by remember(code) { mutableIntStateOf(0) }

    // 构造 PlantUML 服务端 URL(~1 模式:直接传 URL-encoded 文本)
    val imageUrl = remember(code) { buildPlantUmlUrl(code) }
    val plantumlCd = stringResource(R.string.markdown_plantuml_cd, code.take(80))

    // 外层卡片:与 RichContentCard 风格一致(surfaceVariant 半透明背景 + medium 圆角)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
            // 头部标签
            ChartHeader(label = stringResource(R.string.markdown_plantuml_label))
            when {
                showSource -> {
                    ChartSourceView(code = code, onRetry = {
                        showSource = false
                        loadState = 0
                    })
                }
                loadState == 2 -> {
                    ChartErrorView(
                        message = stringResource(R.string.markdown_chart_render_failed),
                        onShowSource = { showSource = true },
                        onRetry = { loadState = 0 },
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val request = remember(imageUrl) {
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = plantumlCd,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = plantumlCd },
                            contentScale = ContentScale.FillWidth,
                            onSuccess = { loadState = 1 },
                            onError = { loadState = 2 },
                        )
                        if (loadState == 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = MuseShapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.markdown_chart_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 构造 PlantUML 服务端渲染 URL(~1 简单模式)。
 *
 * PlantUML 服务端支持 `~1` 前缀直接传 URL-encoded 文本:
 *  - GET https://www.plantuml.com/plantuml/svg/~1{URL_ENCODED_TEXT}
 *  - 服务端会自动识别 @startuml/@enduml,缺省时补全
 *
 * 对超长源码(超过 URL 安全长度约 4KB),返回空串让加载失败回退到源码。
 */
private fun buildPlantUmlUrl(code: String): String {
    // 若源码未显式包含 @startuml,补上(PlantUML 服务端要求)
    val normalized = if (code.contains(PLANTUML_START)) code else "@startuml\n$code\n@enduml"
    val encoded = Uri.encode(normalized)
    // URL 长度安全限制:超过 4000 字符放弃服务端渲染,让 Coil 触发失败回退
    if (encoded.length > 4000) return ""
    return "https://www.plantuml.com/plantuml/svg/~1$encoded"
}

/**
 * 图表卡片头部标签 — 小号 outline 色文字,与 [RichContentCard] 风格一致。
 */
@Composable
private fun ChartHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(MusePaddings.tightGap))
}

/**
 * 图表渲染失败视图 — 显示错误信息 + "显示源码"按钮(+ 可选"重试"按钮)。
 */
@Composable
private fun ChartErrorView(
    message: String,
    onShowSource: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                OutlinedButton(onClick = onShowSource) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(MusePaddings.tightGap))
                    Text(text = stringResource(R.string.markdown_chart_show_source))
                }
                if (onRetry != null) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(MusePaddings.tightGap))
                        Text(text = stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}

/**
 * 图表源码回退视图 — 等宽字体展示原始代码 + "重试"按钮。
 */
@Composable
private fun ChartSourceView(
    code: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            OutlinedButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(MusePaddings.tightGap))
                Text(text = stringResource(R.string.common_retry))
            }
        }
    }
}
