package io.zer0.muse.automation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕读取残缺的可见性（`:accessibility` 待查清单里那条"截断语义"）。
 *
 * `toSummary` 以前会写"还有 N 个节点"（数量截断是可见的），但另外两种残缺完全不可见：
 * 控件树遍历中途抛异常（只留日志）、以及层级超过 30 层被静默丢弃。
 * 模型于是把"没读到"当成"屏幕上没有" —— 找不到控件、误点、或报告"界面上没有这个按钮"。
 */
class ScreenInfoIncompleteTest {

    private fun info(reason: String?) = ScreenInfo(
        packageName = "com.example",
        nodes = listOf(UiNode(text = "确定"), UiNode(text = "取消")),
        screenWidth = 1080,
        screenHeight = 2400,
        source = "accessibility",
        incompleteReason = reason,
    )

    @Test
    fun `读全了就不该出现警告行`() {
        val summary = info(null).toSummary()

        assertFalse("完整读取被标成残缺会让模型不信任所有读屏结果", summary.contains("不完整"))
        assertTrue(summary.contains("控件数: 2"))
    }

    @Test
    fun `残缺提示排在控件清单之前`() {
        // 位置是要害：模型读完前半截就可能已经决定点哪儿了，提示放在末尾等于没放
        val summary = info("层级超过 30 层，更深的控件没有读到").toSummary()

        val warning = summary.indexOf("不完整")
        val firstNode = summary.indexOf("[0]")
        assertTrue("警告行必须存在：$summary", warning >= 0)
        assertTrue("残缺提示要排在控件清单之前（警告 $warning，首个控件 $firstNode）", warning < firstNode)
    }

    @Test
    fun `深度截断与遍历中断各自都进原因`() {
        assertNull(screenReadIssue(depthTruncated = false, error = null, collected = 12, maxDepth = 30))

        val deep = screenReadIssue(depthTruncated = true, error = null, collected = 12, maxDepth = 30)
        assertTrue("该说明深度上限：$deep", deep!!.contains("30 层"))

        val broken = screenReadIssue(
            depthTruncated = false,
            error = "abandoned",
            collected = 12,
            maxDepth = 30,
        )
        assertTrue("该写明是中断：$broken", broken!!.contains("中断"))
        assertTrue("该给出实际拿到几个：$broken", broken.contains("12 个控件"))
    }

    @Test
    fun `两种残缺同时发生时都写出来`() {
        val both = screenReadIssue(
            depthTruncated = true,
            error = "abandoned",
            collected = 3,
            maxDepth = 30,
        )!!

        assertTrue(both.contains("中断"))
        assertTrue(both.contains("30 层"))
        assertTrue("两条要用分隔符连着而不是相互覆盖", both.contains("；"))
    }

    @Test
    fun `默认构造不带残缺标记`() {
        // 其它读取实现（dumpsys / shell）没有这条信息，默认必须是"读全了"而不是空字符串
        assertNull(ScreenInfo(source = "shell").incompleteReason)
        assertEquals("shell", ScreenInfo(source = "shell").source)
    }
}
