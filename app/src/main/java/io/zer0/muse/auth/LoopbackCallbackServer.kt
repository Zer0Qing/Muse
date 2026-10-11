@file:Suppress("TooGenericExceptionCaught", "MayBeConst")

package io.zer0.muse.auth

import io.zer0.common.Logger
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.6.0: OAuth loopback 回调服务器（Authorization Code Flow 专用）。
 *
 * 背景：OpenAI Codex CLI 同款公开 client_id 只允许 `http://localhost:<port>/…`
 * 形式的回调 URI，自定义 scheme（io.zer0.muse://oauth/callback）会被授权服务器
 * 拒绝。因此当 redirectUri 是 loopback 时，启动临时 HTTP 服务器接收浏览器重定向。
 *
 * 实现：原生 [ServerSocket] 手写极简 HTTP GET 解析 —— 只需读取请求行 +
 * 解析 query，无任何 HTTP 框架依赖（Android 无 com.sun.net.httpserver，
 * Ktor/Netty 对单请求场景过重）。
 *
 * 生命周期：launchAuthorizationCodeFlow 打开浏览器前 start()，拿到 code 或
 * 超时/取消后 stop()。单次授权一个 server，不复用。
 *
 * 安全：仅绑定 loopback（外部无法访问）；state 校验复用
 * [OAuthManager.completeAuthorizationCodeFlow] 路径；给浏览器返回可读页面。
 */
internal object LoopbackCallbackServer {

    private const val TAG = "LoopbackCallbackServer"

    private val SUCCESS_HTML =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Muse OAuth</title></head>" +
            "<body style=\"font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">" +
            "<div style=\"text-align:center\"><h2>&#10003; 授权成功</h2><p>可以关闭此页面，回到 Muse 继续。</p></div></body></html>"

    private val FAILURE_HTML =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Muse OAuth</title></head>" +
            "<body style=\"font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">" +
            "<div style=\"text-align:center\"><h2>&#10007; 授权失败</h2><p>请回到 Muse 查看错误信息后重试。</p></div></body></html>"

    /**
     * 在 redirectUri 指定的端口启动 loopback 服务器。
     * @param redirectUri 形如 http://localhost:1455/auth/callback
     * @param pending 当前等待回调的授权流程（code 经 state 校验后 resolve）；
     *   保留参数以维持与 completeAuthorizationCodeFlow 的关联文档语义
     */
    fun start(redirectUri: String, @Suppress("UnusedParameter") pending: OAuthManager.PendingAuthCode): LoopbackServerHandle {
        val slash = redirectUri.removePrefix("http://")
        val hostEnd = slash.indexOf('/')
        val hostPort = if (hostEnd > 0) slash.take(hostEnd) else slash
        val port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80
        val serverSocket = ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))
        Logger.i(TAG, "Loopback 回调服务器已启动: 127.0.0.1:$port")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "oauth-loopback") }
        val closed = AtomicBoolean(false)
        executor.execute {
            try {
                // 单请求：授权回调只发生一次；accept 阻塞由 stop() close socket 打断
                val socket: Socket = serverSocket.accept()
                socket.use { handle(it) }
            } catch (_: java.net.SocketException) {
                // stop() 关闭 socket 的正常打断
            } catch (t: Throwable) {
                Logger.e(TAG, "loopback 回调服务器异常", t)
            }
        }
        return LoopbackServerHandle(serverSocket, executor, closed)
    }

    // CyclomaticComplexMethod 15/15：状态机三态(error/缺参/state 校验)×解析容错，
    // 拆分会把回调协议处理散落到多个函数，按项目惯例按点豁免。
    @Suppress("CyclomaticComplexMethod")
    private fun handle(socket: Socket) {
        var ok = false
        try {
            val requestLine = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
            val rawUri = requestLine.split(" ").getOrNull(1) ?: ""
            val query = rawUri.substringAfter('?', "")
            val params = query.split("&")
                .filter { it.isNotBlank() }
                .associate {
                    val idx = it.indexOf('=')
                    if (idx <= 0) {
                        it to ""
                    } else {
                        URLDecoder.decode(it.take(idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                    }
                }
            val error = params["error"]
            val code = params["code"]
            val state = params["state"]
            ok = when {
                error != null -> {
                    Logger.w(TAG, "授权服务器返回 error=$error")
                    false
                }
                code.isNullOrBlank() || state.isNullOrBlank() -> {
                    Logger.w(TAG, "回调缺少 code/state")
                    false
                }
                // 复用既有 state 校验 + deferred resolve 路径
                !OAuthManager.completeAuthorizationCodeFlow(code, state) -> {
                    Logger.w(TAG, "state 校验失败或无 pending 流程")
                    false
                }
                else -> true
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "解析 loopback 回调失败", t)
        }
        try {
            val body = (if (ok) SUCCESS_HTML else FAILURE_HTML).toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 ${if (ok) 200 else 400} ${if (ok) "OK" else "Bad Request"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { out ->
                out.write(headers.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "写回响应失败: ${t.message}")
        }
    }
}

/** loopback 服务器句柄；stop() 幂等（关闭 socket + 关闭线程池）。 */
internal class LoopbackServerHandle(
    private val serverSocket: ServerSocket,
    private val executor: java.util.concurrent.ExecutorService,
    private val closed: AtomicBoolean,
) {
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        runCatching { executor.shutdownNow() }
        Logger.i("LoopbackCallbackServer", "Loopback 回调服务器已停止")
    }
}
