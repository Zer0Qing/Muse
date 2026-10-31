package io.zer0.muse.ui.markdown.card

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.markdown.CardHeightSink
import io.zer0.muse.ui.markdown.MuseCardBridge
import io.zer0.muse.ui.markdown.MuseCardTheme
import io.zer0.muse.ui.markdown.RichContentWebViewClient
import io.zer0.muse.ui.markdown.sanitizeHtml
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer

/**
 * 卡片渲染的唯一实现。
 *
 * ## 它是这条链路里唯一碰 WebView 的地方
 *
 * 装配顺序固定：主题令牌 → 内容 → 脚本。脚本最后注入，保证执行时 DOM 已就绪。
 *
 * ## 高度
 *
 * - `CardSize.Auto`：高度 = 内容高度，卡片自身不滚动（没有可滚的东西）
 * - `CardSize.Fixed`：固定高度，卡内滚动
 * - `CardSize.Ratio`：宽高比，卡内滚动
 *
 * 这三种互斥。旧实现允许「固定高度 + 测量跟随 + 卡内滚动」同时成立，
 * 结果任何一方都能改高度，谁也说不清最终值哪来的。
 */
@Composable
internal fun CardRuntime(
    spec: CardSpec,
    host: CardHost,
    modifier: Modifier = Modifier,
) {
    var contentHeightPx by remember { mutableIntStateOf(0) }

    val sink = remember { CardHeightSink() }
    LaunchedEffect(sink) {
        sink.onHeightChanged = { px -> if (px > 0) contentHeightPx = px }
    }
    DisposableEffect(sink) {
        onDispose { sink.onHeightChanged = null }
    }

    val bridge = remember(host.onAction, host.readData, host.cardId) {
        host.onAction?.let { action ->
            MuseCardBridge(
                onAction = action,
                readCardData = { id -> host.readData?.invoke(id) ?: spec.data },
                cardId = host.cardId,
            )
        }
    }

    // 主题值跟着 Compose 主题走，深浅色一换就重新生成，卡片不需要重载。
    val themeCss = CardTheme.css()
    val componentCss = remember { CardStyles.full() }
    // WebViewClient 本身无状态，建一次就够：每次重组 new 一个会让
    // LifecycleAwareWebViewContainer 里以它为 key 的 remember 恒失效。
    val webViewClient = remember { RichContentWebViewClient() }
    val html = remember(spec, bridge, themeCss, componentCss) {
        assembleHtml(spec, host, themeCss, componentCss)
    }
    val scrollable = spec.size !is CardSize.Auto

    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = null,
        javaScriptEnabled = true,
        webViewClient = webViewClient,
        scrollable = scrollable,
        onWebViewCreated = { webView ->
            // 高度接口无条件注册：与「能不能回传」无关。
            webView.addJavascriptInterface(sink, CardScript.HEIGHT_SINK_NAME)
            bridge?.let { webView.addJavascriptInterface(it, CardScript.BRIDGE_NAME) }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(sizeModifier(spec.size, contentHeightPx)),
    )
}

/**
 * 按尺寸意图给出高度修饰符。
 *
 * Auto 在拿到首次测量前先占一个矮位：宁可先矮一下再长高，
 * 也不要先给一个大盒再往里堆内容（后者会有明显跳动）。
 */
@Composable
private fun sizeModifier(
    size: CardSize,
    measuredPx: Int,
): Modifier = when (size) {
    // 声明的高度是上限，不是铁框。内容比它矮就跟着收缩 ——
    // 一个三十字的卡片被撑成 520dp，剩下全是空白，读起来像加载失败。
    // 只有内容真的超过上限时才固定，让它在卡内滚。
    is CardSize.Fixed -> {
        // 上报值来自 WebView 的 getBoundingClientRect，单位是 CSS px；
        // 而 CSS px 在 WebView 里就等于 dp（devicePixelRatio 即 Android 密度）。
        // 当成物理像素再 /density 会让每张卡按密度倍率缩水。
        val measured = measuredPx.dp
        if (measuredPx > 0 && measured < size.height) {
            Modifier.height(measured.coerceAtLeast(MIN_CARD_HEIGHT))
        } else {
            Modifier.height(size.height)
        }
    }
    is CardSize.Ratio -> Modifier.aspectRatio(size.ratio)
    CardSize.Auto -> if (measuredPx > 0) {
        Modifier.height(measuredPx.dp)
    } else {
        Modifier.height(80.dp)
    }
}

/** 收缩时的下限。再小就连一行标题都放不下了。 */
private val MIN_CARD_HEIGHT = 88.dp

/**
 * 拼出完整页面。
 *
 * 主题令牌与 CSP 由 [MuseCardTheme] 统一提供，卡片自己不定义颜色 —— 深浅色切换
 * 不需要卡片配合。脚本永远最后注入。
 */
private fun assembleHtml(
    spec: CardSpec,
    host: CardHost,
    themeCss: String,
    componentCss: String,
): String {
    val safe = sanitizeHtml(spec.code)
    val idBoot = host.cardId
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->
            "<script>window.muse=window.muse||{};window.muse.cardId=" +
                "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\";</script>"
        }
        .orEmpty()
    // 三段样式按覆盖顺序排列：静态默认 → 当前主题精确值 → 组件词汇。
    val styles = "<style>${MuseCardTheme.styleBlock}</style>" +
        "<style>$themeCss</style>" +
        "<style>$componentCss</style>"
    // 脚本放最后：桥先于图表，图表初始化不依赖桥但卡片可能调用两者。
    val scripts = "<script>${CardScript.build()}</script><script>${CardCharts.build()}</script>"
    val padding = if (spec.size is CardSize.Auto) "0" else "8px"
    return """
        <html><head><meta charset="UTF-8">${MuseCardTheme.VIEWPORT_META}${MuseCardTheme.CSP_META}$styles</head>
        <body style="margin:0;padding:$padding;background:transparent;">$idBoot$safe$scripts</body></html>
    """.trimIndent()
}
