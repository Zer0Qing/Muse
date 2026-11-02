package io.zer0.muse.automation

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动化运行时通道的**失败语义**。
 *
 * 这些通道是给界面用的（预览/接管/交回主屏），最怕的是"没安装却假装成功"：
 * 用户点了"交回主屏"，界面说成功了，实际上什么都没发生。
 * 所以约定很硬：**没安装就返回 null/false**，界面据此显示真实原因。
 */
class AutomationRuntimeTest {

    @Before
    fun setUp() = AutomationRuntime.resetForTests()

    @After
    fun tearDown() = AutomationRuntime.resetForTests()

    @Test
    fun `without install everything reports unavailable instead of pretending`() = runBlocking {
        assertFalse("未安装时通道不可用", AutomationRuntime.isReady)
        assertNull(AutomationRuntime.screenshot(1))
        assertFalse(AutomationRuntime.tap(1, 10, 20))
        assertFalse(AutomationRuntime.destroy(1))
        assertFalse("交回主屏没接上时不能报成功", AutomationRuntime.launchOnMain("com.example.app"))
    }

    @Test
    fun `after install the channels delegate to the provided actions`() = runBlocking {
        var tapped: Triple<Int, Int, Int>? = null
        var launched: String? = null
        AutomationRuntime.install(
            screenshot = { displayId -> byteArrayOf(displayId.toByte()) },
            tap = { displayId, x, y ->
                tapped = Triple(displayId, x, y)
                true
            },
            destroy = { true },
            launchOnMain = { pkg ->
                launched = pkg
                true
            },
        )

        assertTrue(AutomationRuntime.isReady)
        assertArrayEquals(byteArrayOf(7), AutomationRuntime.screenshot(7))
        assertTrue(AutomationRuntime.tap(7, 100, 200))
        assertEqualsTriple(7, 100, 200, tapped)
        assertTrue(AutomationRuntime.launchOnMain("com.example.shop"))
        org.junit.Assert.assertEquals("com.example.shop", launched)
    }

    @Test
    fun `install without launch channel keeps bring to main unavailable`() = runBlocking {
        // 默认参数就是"没接"，避免调用方忘了传却以为能用
        AutomationRuntime.install(
            screenshot = { null },
            tap = { _, _, _ -> false },
            destroy = { false },
        )

        assertFalse(AutomationRuntime.launchOnMain("com.example.app"))
    }

    private fun assertEqualsTriple(a: Int, b: Int, c: Int, actual: Triple<Int, Int, Int>?) {
        org.junit.Assert.assertEquals(Triple(a, b, c), actual)
    }
}
