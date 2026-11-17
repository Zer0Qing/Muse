package io.zer0.muse.ui.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.terminal.TerminalSessionManager
import io.zer0.muse.terminal.TerminalSessionStore
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.ArrayDeque

private const val TAG = "TerminalScreen"

/** 终页诊断探针开关:黑屏之谜已收敛,默认关闭;排查时置 true 重新启用。 */
private const val TERMINAL_PROBES_ENABLED = false

/**
 * v2.x 终端一期:应用沙盒终端页。
 *
 * - 显示:xterm.js(assets/terminal)运行在 WebView 中,ANSI 颜色与 UTF-8 完整支持;
 * - 会话:[TerminalSessionStore] 进程级单例,离开页面会话不杀,回来继续;
 * - 输入:底部 Compose 输入行(IME 友好),本地回显后写入会话 stdin;
 * - 后续:PTY 引擎接入后,交互式程序与 Ctrl-C 信号在此页直接可用,UI 无需改动。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun TerminalScreen(onBack: () -> Unit, onSendToAi: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val session = remember { TerminalSessionStore.get(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    // M5c: 手动「把这轮发给 AI」用的最近命令与输出尾巴。
    var lastCommand by remember { mutableStateOf("") }
    val recentOutput = remember { StringBuilder() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    // 未就绪时的输出先入队,ready 后一次性冲刷(主线程访问,无并发问题)
    val pendingJs = remember { ArrayDeque<String>() }

    fun pushToTerminal(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val js = "window.MuseTerm && window.MuseTerm.writeB64('$b64');"
        Logger.d(TAG, "push ${bytes.size}B (webReady=$webReady, queued=${pendingJs.size})")
        mainHandler.post {
            val wv = webViewRef
            if (wv != null && webReady) {
                wv.evaluateJavascript(js, null)
            } else {
                pendingJs.addLast(js)
            }
        }
    }

    fun pushText(text: String) = pushToTerminal(text.toByteArray(Charsets.UTF_8))

    // 调试探针:回读 WebView 内 xterm 真实状态(尺寸/行列/字元/写入计数/JS 错误)
    fun runProbe(wv: WebView, tag: String) {
        val probe =
            "(function(){try{var t=window.__t;var q=document.querySelector('.xterm');" +
                "var cell=(t&&t._core&&t._core._renderService)?t._core._renderService.dimensions.css.cell:null;" +
                "var te=document.getElementById('term');" +
                "return 'rows='+(t?t.rows:'?')+' cols='+(t?t.cols:'?')+' ih='+window.innerHeight" +
                "+' iw='+window.innerWidth+' termH='+(te?te.offsetHeight:'?')+' xtermH='+(q?q.offsetHeight:'?')" +
                "+' cellW='+(cell?cell.width:'?')+' cellH='+(cell?cell.height:'?')" +
                "+' pos='+(te?getComputedStyle(te).position:'?')+' ch='+(te?getComputedStyle(te).height:'?')" +
                "+' writes='+(window.__writes||0)+' err='+(window.__lastErr||'none')}catch(e){return 'PROBE_ERR:'+e}})()"
        wv.evaluateJavascript(probe) { r -> Logger.i(TAG, "probe($tag): $r") }
    }

    DisposableEffect(Unit) {
        session.setListener(
            object : TerminalSessionManager.Listener {
                override fun onOutput(bytes: ByteArray) {
                    if (recentOutput.length < 16_000) recentOutput.append(String(bytes, Charsets.UTF_8))
                    pushToTerminal(bytes)
                }

                override fun onExit(code: Int) {
                    recentOutput.append("\n[exit=$code]\n")
                    pushText("\r\n" + context.getString(R.string.terminal_session_exited, code) + "\r\n")
                }
            },
        )
        // v2.2.1: ensureStarted 含进程启动/文件 IO,移到 IO 线程执行(消除 StrictMode 主线程告警)
        val startJob = scope.launch(Dispatchers.IO) { session.ensureStarted() }
        onDispose {
            startJob.cancel()
            session.setListener(null)
            // 会话保留(进程级单例),仅回收 WebView,避免泄漏;下次进入重载缓冲
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ── 顶栏 ─────────────────────────────────────────────
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTopBarIconButton(
                icon = MuseIcons.arrowLeft,
                contentDescription = stringResource(R.string.terminal_back_cd),
                onClick = onBack,
                tint = MaterialTheme.colorScheme.onSurface,
                solid = false,
            )
            Icon(
                imageVector = MuseIcons.terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.terminal_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseTopBarIconButton(
                icon = MuseIcons.trash,
                contentDescription = stringResource(R.string.terminal_clear_cd),
                onClick = {
                    webViewRef?.evaluateJavascript("window.MuseTerm && window.MuseTerm.clearTerm();", null)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                solid = false,
            )
            if (onSendToAi != null && lastCommand.isNotBlank()) {
                MuseTopBarIconButton(
                    icon = MuseIcons.share,
                    contentDescription = stringResource(R.string.terminal_send_to_ai_cd),
                    onClick = {
                        val outputTail = recentOutput.toString().takeLast(4_000)
                        onSendToAi(
                            "【终端回执】\n命令：$lastCommand\n输出（末尾）：\n$outputTail",
                        )
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    solid = false,
                )
            }
            MuseTopBarIconButton(
                icon = MuseIcons.refresh,
                contentDescription = stringResource(R.string.terminal_restart_cd),
                onClick = {
                    session.restart()
                    pushText("\r\n" + context.getString(R.string.terminal_session_restarted) + "\r\n")
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                solid = false,
            )
            if (session.isPty) {
                MuseTopBarIconButton(
                    icon = MuseIcons.power,
                    contentDescription = stringResource(R.string.terminal_interrupt_cd),
                    onClick = { session.sendInterrupt() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    solid = false,
                )
            }
        }

        // ── 终端画面(xterm.js) ───────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.parseColor("#101014"))
                    // 首次布局/尺寸变化时重算终端行列(fit 在加载时可能测到 0 高度,导致 rows=1)
                    addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val changed =
                            (right - left) != (oldRight - oldLeft) ||
                                (bottom - top) != (oldBottom - oldTop)
                        if (changed) {
                            evaluateJavascript(
                                "window.MuseTerm && window.MuseTerm.refit && window.MuseTerm.refit();",
                                null,
                            )
                        }
                    }
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                Logger.i(TAG, "WebView 加载完成: $url")
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                Logger.w(TAG, "WebView 加载错误: ${error?.errorCode} ${error?.description} ${request?.url}")
                            }
                        }
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                Logger.w(
                                    TAG,
                                    "WebView console[${consoleMessage.messageLevel()}]: ${consoleMessage.message()} " +
                                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                                )
                                return true
                            }
                        }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onReady() {
                                mainHandler.post {
                                    webReady = true
                                    Logger.i(TAG, "WebView onReady 到达:pending=${pendingJs.size}")
                                    val wv = webViewRef
                                    if (wv != null) {
                                        while (pendingJs.isNotEmpty()) {
                                            wv.evaluateJavascript(pendingJs.removeFirst(), null)
                                        }
                                        if (TERMINAL_PROBES_ENABLED) {
                                            runProbe(wv, "onReady")
                                            val after3 = context.getString(R.string.terminal_probe_after_seconds, 3)
                                            val after8 = context.getString(R.string.terminal_probe_after_seconds, 8)
                                            mainHandler.postDelayed({ webViewRef?.let { runProbe(it, after3) } }, 3000)
                                            mainHandler.postDelayed({ webViewRef?.let { runProbe(it, after8) } }, 8000)
                                        }
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onResize(rows: Int, cols: Int) {
                                session.resize(rows, cols)
                            }
                        },
                        "MuseBridge",
                    )
                    loadUrl("file:///android_asset/terminal/terminal.html?t=" + System.currentTimeMillis())
                }.also { webViewRef = it }
            },
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ── 输入行 ───────────────────────────────────────────
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MusePaddings.screen)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "❯",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.terminal_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    inner()
                },
            )
            MuseTopBarIconButton(
                icon = MuseIcons.send,
                contentDescription = stringResource(R.string.terminal_send_cd),
                onClick = {
                    val cmd = input.trimEnd()
                    if (cmd.isNotBlank()) {
                        lastCommand = cmd
                        recentOutput.clear()
                        // PTY 下由终端自身回显输入与提示符;管道引擎本地回显
                        if (!session.isPty) pushText("❯ $cmd\r\n")
                        session.write(cmd + "\n")
                    }
                    input = ""
                },
                tint = MaterialTheme.colorScheme.primary,
                solid = false,
            )
        }
    }
}
