package io.zer0.muse.ui.markdown.card

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.markdown.CardHeightSink
import io.zer0.muse.ui.markdown.MuseCardBridge
import io.zer0.muse.ui.markdown.MuseCardTheme
import io.zer0.muse.ui.markdown.RichContentWebViewClient
import io.zer0.muse.ui.markdown.SanitizedHtmlCache
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
internal fun CardRuntime(spec: CardSpec, host: CardHost, modifier: Modifier = Modifier) {
    var contentHeightPx by remember(spec.code) { mutableIntStateOf(0) }

    val sink = remember { CardHeightSink() }
    LaunchedEffect(sink) {
        sink.onHeightChanged = { px -> if (px > 0) contentHeightPx = px }
    }
    DisposableEffect(sink) {
        onDispose { sink.onHeightChanged = null }
    }

    val coroutineScope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    val bridge = remember(host.onAction, host.onSilentAsk, host.onSaveState, host.readData, host.cardId) {
        val canAttach = host.onAction != null || host.onSilentAsk != null || host.onSaveState != null
        if (!canAttach) {
            null
        } else {
            MuseCardBridge(
                onAction = host.onAction,
                readCardData = { id -> host.readData?.invoke(id) ?: spec.data },
                cardId = host.cardId,
                onSilentAsk = host.onSilentAsk?.let { askHandler ->
                    { reqId, payloadJson ->
                        coroutineScope.launch {
                            val responseJson = runCatching {
                                askHandler(host.cardId, payloadJson)
                            }.getOrElse {
                                """{"ok":false,"error":"internal"}"""
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                webViewRef?.evaluateJavascript(CardScript.askResolveJs(reqId, responseJson), null)
                            }
                        }
                    }
                },
                onSaveState = host.onSaveState,
            )
        }
    }

    // 主题值跟着 Compose 主题走，深浅色一换就重新生成，卡片不需要重载。
    // 按配色缓存：高度每次上报都会触发重组，而这份 CSS 只跟配色有关，
    // 缓存后同一个配色返回同一引用，remember 键的相等判断退化为引用相等。
    val colorScheme = MaterialTheme.colorScheme
    val themeCss = remember(colorScheme) { CardTheme.cssOf(colorScheme) }
    val componentCss = remember { CardStyles.full() }
    // 推送能力是「宿主支不支持」，只在本页装配时决定一次；token 的取值变化不该重建 HTML。
    val pushEnabled = host.pushToken != null
    // 只以真正决定 HTML 内容的字段为键：`spec.data` 不参与装配（卡片通过桥读数据），
    // 若拿整个 spec 当键，每次 saveState 写回数据都会重拼并重载 WebView，
    // 正在进行的 ask Promise 和内存中的棋盘状态都会被一并冲掉。
    val html = remember(spec.language, spec.code, spec.size, bridge, themeCss, componentCss, pushEnabled) {
        assembleHtml(spec, host, themeCss, componentCss, pushEnabled).also { assembled ->
            // 卡片 HTML 每装配一次，WebView 就要重载一次。这行是「卡片重载次数」的唯一权威来源，
            // 流式生成一张卡时数它就知道渐进渲染到底把重载压到了几次（P1 的核心断言）。
            Logger.d("CardPerf", "assemble card=${host.cardId} len=${assembled.length}")
        }
    }
    var pageReady by remember(html) { mutableStateOf(false) }
    val onPageFinishedAction by rememberUpdatedState { pageReady = true }
    // WebViewClient 引用保持稳定：仅更新回调目标，避免 LifecycleAwareWebViewContainer 重绑。
    val webViewClient = remember { RichContentWebViewClient { onPageFinishedAction() } }
    // 36: tap-to-focus。未聚焦的 Auto 卡保持原样（外层列表滚），仅「聚焦且溢出」才卡内滚，
    // 故不会回退 39②（卡内滚抢手势）——聚焦是用户点出来的，不是默认状态。
    val focusController = LocalCardFocusController.current
    val focusedId by focusController.state.collectAsStateWithLifecycle()
    val cardId = host.cardId
    val focused = cardId != null && focusedId == cardId
    val capDp = MAX_INLINE_CARD_DP
    val hasOverflow = hasInlineOverflow(contentHeightPx, capDp)
    val internalScroll = spec.size is CardSize.Auto &&
        resolveCardScrollMode(focused, hasOverflow) == CardScrollMode.InternalScroll
    // Fixed 卡片在未超出时也按内容高度展开，仅 Ratio 与聚焦溢出模式才启用卡内滚动。
    val scrollable = (spec.size is CardSize.Ratio) || internalScroll

    // 数据推送：绑定数据一变就通知卡片重读，取代每张卡常驻的 800ms 轮询。
    // 保留 LaunchedEffect(webViewRef, host.pushToken) 与早退契约（CardDataPushWiringTest），
    // 并额外在 WebView onPageFinished(pageReady) 后补推一次，防止首帧加载完成前写入的更新丢失。
    LaunchedEffect(webViewRef, host.pushToken) {
        val view = webViewRef ?: return@LaunchedEffect
        if (host.pushToken == null) return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        view.evaluateJavascript(CardScript.DATA_EVENT_JS, null)
    }
    LaunchedEffect(webViewRef, pageReady) {
        val view = webViewRef ?: return@LaunchedEffect
        if (!pageReady || host.pushToken == null) return@LaunchedEffect
        view.evaluateJavascript(CardScript.DATA_EVENT_JS, null)
    }

    Box(modifier = modifier.fillMaxWidth()) {
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
                webViewRef = webView
            },
            onWebViewReleased = { webViewRef = null },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (internalScroll) {
                        Modifier.height(effectiveInlineHeightPx(contentHeightPx, capDp).dp)
                    } else {
                        sizeModifier(spec.size, contentHeightPx)
                    },
                ),
        )
        // 只在「Auto 卡未聚焦且内容溢出」时盖一层透明点击层：点一下聚焦，竖滑不消费、交回聊天列表。
        // 不溢出时绝不能盖 —— 否则 WebView 里的按钮、链接与 JS 交互全都收不到点击。
        val tapFocusId = cardId
        if (tapFocusId != null &&
            shouldCaptureTapForFocus(spec.size is CardSize.Auto, focused, hasOverflow, hasCardId = true)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(tapFocusId) {
                        detectTapGestures(onTap = { focusController.focus(tapFocusId) })
                    },
            )
        }
    }
}

/**
 * 按尺寸意图给出高度修饰符。
 *
 * Auto 在拿到首次测量前先占一个矮位：宁可先矮一下再长高，
 * 也不要先给一个大盒再往里堆内容（后者会有明显跳动）。
 */
@Composable
private fun sizeModifier(size: CardSize, measuredPx: Int): Modifier = when (size) {
    // 39②: 声明高度只当测量前的占位，不再当上限。未聚焦时内容多高卡片就多高、由外层聊天列表滚，
    // 不默认卡内滚（卡内滚会与外层抢手势）。36 的 tap-to-focus 只在「用户点聚焦且内容溢出上限」时
    // 改走限高 + 卡内滚（见 CardRuntime 主体），故不默认回退 39②。收缩下限仍保留。
    is CardSize.Fixed -> {
        // 上报值来自 WebView 的 getBoundingClientRect，单位是 CSS px；
        // 而 CSS px 在 WebView 里就等于 dp（devicePixelRatio 即 Android 密度）。
        // 当成物理像素再 /density 会让每张卡按密度倍率缩水。
        val measured = measuredPx.dp
        if (measuredPx > 0) {
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
private fun assembleHtml(spec: CardSpec, host: CardHost, themeCss: String, componentCss: String, pushEnabled: Boolean): String {
    val safe = SanitizedHtmlCache.get(spec.code)
    val idBoot = host.cardId
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->
            "<script>window.muse=window.muse||{};window.muse.cardId=" +
                "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\";</script>"
        }
        .orEmpty()
    // 推送能力标记：卡片脚本据此决定订阅走事件还是退回 800ms 轮询。
    val pushBoot = if (pushEnabled) "<script>window.__museDataPush=true;</script>" else ""
    // 门面必须在卡片正文之前：卡片内联脚本在挂载时就会同步调用
    // window.muse.getJSON() / data.onChange()，晚一步就只能抛 TypeError 并彻底错过订阅。
    // 顺序：身份 → 推送标记 → 门面 → 图表 → 卡片正文 → 高度观测。
    val preludeScripts = PRELUDE_SCRIPTS_TAG
    // 三段样式按覆盖顺序排列：静态默认 → 当前主题精确值 → 组件词汇。
    val styles = "<style>${MuseCardTheme.styleBlock}</style>" +
        "<style>$themeCss</style>" +
        "<style>$componentCss</style>"
    // 脚本分两段：门面与图表放正文前（卡片脚本依赖它们），高度观测放正文后（要 document.body）。
    val scripts = OBSERVER_SCRIPT_TAG
    val padding = if (spec.size is CardSize.Auto) "0" else "8px"
    return """
        <html><head><meta charset="UTF-8">${MuseCardTheme.VIEWPORT_META}${MuseCardTheme.CSP_META}$styles</head>
        <body style="margin:0;padding:$padding;background:transparent;">$idBoot$pushBoot$preludeScripts$safe$scripts</body></html>
    """.trimIndent()
}

/** 卡片正文之前注入：交互门面与图表渲染器。 */
private val PRELUDE_SCRIPTS_TAG: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    "<script>${CardScript.facadeJs()}</script><script>${CardCharts.build()}</script>"
}

/** 卡片正文之后注入：高度观测（需要 document.body 已存在）。 */
private val OBSERVER_SCRIPT_TAG: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    "<script>${CardScript.observerJs()}</script>"
}
