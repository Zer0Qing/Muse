package io.zer0.muse.crash

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * 崩溃上报必须绕开用户代理。
 *
 * 回归背景：共享 `named("chat")` client 装的是**动态 ProxySelector**，而 OkHttp 在建立
 * 连接时**优先使用 proxySelector**，`proxy(NO_PROXY)` 会被它覆盖。旧实现只设了
 * `proxy(NO_PROXY)`，于是用户配的代理依然劫走崩溃上报 —— 表现就是「服务器明明是好的，
 * 客户端却一直报不可达」。
 */
class CrashReporterDirectClientTest {

    /** 模拟共享 client 上的动态代理选择器：总是返回一个代理。 */
    private class AlwaysProxySelector : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1080)))

        override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
    }

    private fun baseWithProxy(): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(AlwaysProxySelector())
        .build()

    @Test
    fun `direct client replaces dynamic proxy selector with no-proxy`() {
        val base = baseWithProxy()
        // 前置确认：共享 client 确实会把请求代理走。
        val before = base.proxySelector.select(URI("https://museai.ltd/api/crash-report"))
        assertTrue("共享 client 应选择代理", before.first().type() != Proxy.Type.DIRECT)

        val direct = CrashReporterFactory.directClient(base)
        val after = direct.proxySelector.select(URI("https://museai.ltd/api/crash-report"))
        assertEquals("上报链路必须直连", Proxy.NO_PROXY, after.first())
    }

    @Test
    fun `no proxy selector always returns direct`() {
        val selected = CrashReporterFactory.NoProxySelector.select(URI("https://example.com"))
        assertEquals(listOf(Proxy.NO_PROXY), selected)
        assertEquals(Proxy.NO_PROXY, selected.first())
    }

    @Test
    fun `direct client clears proxy authenticator`() {
        val direct = CrashReporterFactory.directClient(baseWithProxy())
        // 直连请求不应携带代理凭据：authenticator 对 407 返回 null。
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Authentication Required")
            .build()
        assertNull("直连不应补代理认证头", direct.proxyAuthenticator.authenticate(null, response))
    }

    @Test
    fun `direct client keeps base interceptors and timeouts`() {
        val direct = CrashReporterFactory.directClient(baseWithProxy())
        assertNotNull(direct)
        // 超时按上报口径收紧，避免崩溃后把用户卡住。
        assertEquals(CrashReporterFactory.CONNECT_TIMEOUT_SECONDS * 1000, direct.connectTimeoutMillis.toLong())
        assertEquals(CrashReporterFactory.READ_TIMEOUT_SECONDS * 1000, direct.readTimeoutMillis.toLong())
    }
}
