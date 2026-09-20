package io.zer0.muse.tools

import io.zer0.muse.ui.SsrfGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2-33: WebView 用的 SSRF 判定闸门 — 把「同步 DNS」从回调线程彻底移走。
 *
 * WebView 的 [android.webkit.WebViewClient] 回调分两类线程:
 *  - [android.webkit.WebViewClient.onPageStarted] / [android.webkit.WebViewClient.shouldOverrideUrlLoading]
 *    在**主线程**回调,回调内部必须同步返回判定结果 — 在此发起 DNS 就是冷域名首屏 ANR 的根因;
 *  - [android.webkit.WebViewClient.shouldInterceptRequest] 在 WebView 后台线程回调,可同步解析。
 *
 * 因此:
 *  - [isBlockedNow] 主线程安全:只消费 [SsrfGuard] 缓存;未命中(冷主机)时 fail-closed 立即拦截,
 *    并在后台线程解析补齐缓存([prewarm]);可选的 [onVerified] 在解析确认安全后回主线程重试该跳,
 *    所以冷域名不会永久卡住合法重定向,也不会阻塞主线程;
 *  - [isBlockedBlocking] 仅限已在后台线程的回调(shouldInterceptRequest)使用;
 *  - [isBlockedAsync] 供挂起调用方(navigate 主框架)等待后台解析结果 — 首次解析同样写入缓存。
 *
 * 安全语义保持不变:解析结果与判定结果严格一致,绝不「先放行再重新解析」;无法确认时一律拒绝。
 */
internal class WebViewSsrfGate(
    /** 后台预热协程作用域(生产:IO + SupervisorJob;测试可注入受控作用域)。 */
    private val prewarmScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 回调重试需要回到的回调线程(生产:主线程;测试:Unconfined 便于同步断言)。 */
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val cachedVerdict: (String) -> Boolean? = SsrfGuard::cachedVerdictOrNull,
    private val resolveAsync: suspend (String) -> Boolean = SsrfGuard::isBlockedAsync,
    private val resolveBlocking: (String) -> Boolean = SsrfGuard::isBlocked,
) {

    /**
     * 挂起判定:解析发生在 [Dispatchers.IO](缓存命中的首次调用也在此写入缓存)。
     * 供 [BrowserManager.navigate] 在主框架加载前等待判定 — 主线程只消费结果。
     */
    suspend fun isBlockedAsync(url: String): Boolean = resolveAsync(url)

    /**
     * 主线程安全的同步判定:只消费缓存,不发起 DNS。
     *
     * 冷主机(缓存未命中)保守判为拦截,并后台预热;若解析确认安全且提供了 [onVerified],
     * 则在 [callbackDispatcher] 上执行重试动作(仅一次,后续同主机命中缓存)。
     *
     * @return true 表示本次应拦截。
     */
    fun isBlockedNow(url: String, onVerified: (() -> Unit)? = null): Boolean {
        cachedVerdict(url)?.let { return it }
        prewarmScope.launch {
            if (!resolveAsync(url) && onVerified != null) {
                withContext(callbackDispatcher) { onVerified() }
            }
        }
        return true
    }

    /** 后台线程回调用的阻塞判定(shouldInterceptRequest);解析结果进缓存供主线程复用。 */
    fun isBlockedBlocking(url: String): Boolean = resolveBlocking(url)
}
