package io.zer0.muse.tools

import io.zer0.muse.ui.SsrfGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-33: SSRF 冷域名解析不再阻塞主线程(WebView 回调路径)。
 *
 * 通过 [SsrfGuard.setResolverForTest] 注入「慢解析器」替身,确定性验证:
 *  - 解析发生在 Dispatchers.IO 工作线程,绝不在调用线程(主线程)上执行 DNS;
 *  - 首次解析的结果写入缓存,后续 [WebViewSsrfGate.isBlockedNow] / [WebViewSsrfGate.isBlockedBlocking]
 *    调用都不再触发 DNS;
 *  - 缓存未命中时主线程路径 fail-closed 立即拦截(而不是同步等待 DNS),预热完成后可重试放行。
 */
class WebViewSsrfGateTest {

    /** 慢解析器替身:记录调用次数与解析线程,并放大延迟以模拟冷域名 DNS。 */
    private class SlowResolver(
        private val delayMs: Long,
        private val addresses: Array<InetAddress>,
    ) : SsrfGuard.HostResolver {
        val calls = AtomicInteger(0)
        val resolveThreads = CopyOnWriteArrayList<String>()

        override fun resolve(host: String): Array<InetAddress> {
            calls.incrementAndGet()
            resolveThreads.add(Thread.currentThread().name)
            val deadline = System.currentTimeMillis() + delayMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // 解析替身不响应中断:模拟真实 DNS 不可取消
                }
            }
            return addresses
        }
    }

    @After
    fun tearDown() {
        SsrfGuard.resetForTest()
    }

    private fun gate(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) = WebViewSsrfGate(
        prewarmScope = scope,
        callbackDispatcher = Dispatchers.Unconfined,
        cachedVerdict = SsrfGuard::cachedVerdictOrNull,
        resolveAsync = SsrfGuard::isBlockedAsync,
        resolveBlocking = SsrfGuard::isBlocked,
    )

    /** 字面量 IP 不触发真实 DNS,保证单测离线可重复。 */
    private fun publicAddresses(): Array<InetAddress> = arrayOf(InetAddress.getByName("8.8.8.8"))

    private fun privateAddresses(): Array<InetAddress> = arrayOf(InetAddress.getByName("127.0.0.1"))

    @Test
    fun `cold host resolution runs on io thread not on the calling thread`() = runBlocking {
        val resolver = SlowResolver(delayMs = 300, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()

        val callerThread = Thread.currentThread().name
        val blocked = gate.isBlockedAsync("http://cold-ssrf-a.example/")

        assertFalse("公网地址应放行", blocked)
        assertEquals(1, resolver.calls.get())
        assertNotEquals("解析绝不能发生在调用线程(主线程)上", callerThread, resolver.resolveThreads.single())
        assertTrue(
            "解析应落在 Dispatchers.IO 工作线程,实际=${resolver.resolveThreads.single()}",
            resolver.resolveThreads.single().startsWith("DefaultDispatcher-worker"),
        )
    }

    @Test
    fun `first resolution is written into cache so later lookups do no dns`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-b.example/"

        assertFalse(gate.isBlockedAsync(url))
        // 首次解析已进缓存 → 主线程只读缓存即可判定
        assertEquals(false, SsrfGuard.cachedVerdictOrNull(url))
        val startedAt = System.nanoTime()
        assertFalse(gate.isBlockedNow(url))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("缓存命中不应阻塞调用线程(实测 ${elapsedMs}ms)", elapsedMs < 100)
        assertFalse(gate.isBlockedBlocking(url))
        assertEquals("同一主机只解析一次(首次解析也被缓存覆盖)", 1, resolver.calls.get())
    }

    @Test
    fun `cold host on main thread fails closed without waiting and heals after prewarm`() = runBlocking {
        val resolver = SlowResolver(delayMs = 250, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-c.example/"
        val retried = AtomicBoolean(false)

        val startedAt = System.nanoTime()
        val blocked = gate.isBlockedNow(url) { retried.set(true) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("冷主机必须 fail-closed", blocked)
        assertTrue("主线程路径不得等待 DNS(实测 ${elapsedMs}ms)", elapsedMs < 100)
        assertFalse("预热未完成前不重试", retried.get())

        withTimeout(5_000) {
            while (!retried.get()) delay(10)
        }
        assertEquals(1, resolver.calls.get())
        // 预热完成 → 同一主机后续判定直接命中缓存,不再拦截
        assertFalse(gate.isBlockedNow(url))
    }

    @Test
    fun `cold private host stays blocked and resolves at most once`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = privateAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-d.example/"

        assertTrue("内网地址必须拦截", gate.isBlockedAsync(url))
        assertEquals(true, SsrfGuard.cachedVerdictOrNull(url))
        assertTrue(gate.isBlockedNow(url))
        assertTrue(gate.isBlockedBlocking(url))
        assertEquals(1, resolver.calls.get())
    }

    @Test
    fun `off main thread guard resolves on demand and warms cache for main thread callbacks`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-e.example/"

        // shouldInterceptRequest 在 WebView 后台线程回调 → 允许同步解析并写缓存
        assertFalse(gate.isBlockedBlocking(url))
        assertEquals(1, resolver.calls.get())
        // 主线程回调(重定向/onPageStarted)随后直接命中缓存
        assertFalse(gate.isBlockedNow(url))
        assertEquals(1, resolver.calls.get())
    }
}
