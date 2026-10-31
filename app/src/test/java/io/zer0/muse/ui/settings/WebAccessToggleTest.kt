package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「启用网页版」开关的副作用序列。
 *
 * 页面的开关只调 [applyWebAccessEnabled]，所以这里锁定的是用户按下开关后会发生什么：
 * 配置一定写入，服务按需启动或停止，而且各自只发生一次。
 */
class WebAccessToggleTest {

    /** 记录调用的服务替身。 */
    private class RecordingHost(private val startResult: Boolean = true) : WebAccessHost {
        val saved = mutableListOf<WebServerConfig>()
        var startCalls = 0
        var stopCalls = 0
        var startAttemptsAfterSave = 0

        override suspend fun saveConfig(config: WebServerConfig) {
            saved += config
        }

        override suspend fun startServer(): Boolean {
            startCalls++
            startAttemptsAfterSave = saved.size
            return startResult
        }

        override suspend fun stopServer() {
            stopCalls++
        }
    }

    private val disabled = WebServerConfig(enabled = false, port = 8765, pin = "123456")

    @Test
    fun `enabling writes config then starts the server once`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled, enabled = true, running = false)

        assertTrue(running)
        assertEquals(1, host.saved.size)
        assertTrue("配置里的 enabled 必须落盘", host.saved.single().enabled)
        assertEquals("端口与 PIN 不能被开关改动", "123456", host.saved.single().pin)
        assertEquals(1, host.startCalls)
        assertEquals(0, host.stopCalls)
        assertEquals("启动必须发生在配置写入之后", 1, host.startAttemptsAfterSave)
    }

    /** 服务已经在跑时再开一次开关，不重复启动。 */
    @Test
    fun `enabling while running does not start again`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled.copy(enabled = true), enabled = true, running = true)

        assertTrue(running)
        assertEquals("已运行时不应重复启动", 0, host.startCalls)
        assertEquals(0, host.stopCalls)
    }

    @Test
    fun `disabling stops the server once and clears enabled`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled.copy(enabled = true), enabled = false, running = true)

        assertFalse(running)
        assertFalse("配置里的 enabled 必须落盘为 false", host.saved.single().enabled)
        assertEquals(1, host.stopCalls)
        assertEquals(0, host.startCalls)
    }

    /** 本来就没在跑时关开关：配置照写，但不做无意义的 stop。 */
    @Test
    fun `disabling while stopped skips the stop call`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled, enabled = false, running = false)

        assertFalse(running)
        assertEquals(1, host.saved.size)
        assertEquals(0, host.stopCalls)
    }

    /** 启动失败时把 false 交回页面，页面据此弹「启动失败」。 */
    @Test
    fun `start failure is reported back to the caller`() = runTest {
        val host = RecordingHost(startResult = false)

        val running = applyWebAccessEnabled(host, disabled, enabled = true, running = false)

        assertFalse(running)
        assertEquals(1, host.startCalls)
    }
}
