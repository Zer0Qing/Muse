package io.zer0.muse.automation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标越界的判定（`:accessibility` 待查清单「手势坐标是否防越界」那一件）。
 *
 * 查清的结论：越界时 Android 的 `dispatchGesture` 是**直接拒收且不回调**，
 * 上层只看到"手势未被接受"。真正的毛病不是崩，而是**原因传不到模型**：
 * 工具以前回一句"坐标越界或通道未就绪"，两种原因混在一起，
 * 模型于是拿同一组坐标反复重试、白烧步数。
 */
class CoordinateBoundsTest {

    @Test
    fun `界内坐标不报问题`() {
        assertNull(boundsIssue(0, 0, 1080, 2400))
        assertNull(boundsIssue(1079, 2399, 1080, 2400))
        assertNull(boundsIssue(540, 1200, 1080, 2400))
    }

    @Test
    fun `边界外的点报出允许范围`() {
        // 右下边缘是 width-1 / height-1，取到 width 就已经出去了
        assertNotNull(boundsIssue(1080, 1200, 1080, 2400))
        assertTrue(boundsIssue(1080, 1200, 1080, 2400)!!.contains("0..1079"))
        assertNotNull(boundsIssue(-1, 100, 1080, 2400))
        assertNotNull(boundsIssue(100, 2400, 1080, 2400))
    }

    @Test
    fun `屏幕尺寸未知时一律放行`() {
        // 这条不是可选项：把「没量到屏幕尺寸」当成「全都越界」，
        // 自动化会一个点都点不动 —— 那是把保护变成故障。
        assertNull(boundsIssue(9999, 9999, 0, 0))
        assertNull(boundsIssue(9999, 9999, -1, 2400))
        assertNull(pointsBoundsIssue(listOf(9999 to 9999), 0, 2400))
    }

    @Test
    fun `多点动作把所有越界的点都列出来`() {
        val issue = pointsBoundsIssue(listOf(500 to 800, 1500 to 900, 500 to 3000), 1080, 2400)!!

        // 只报第一个点会让模型改一个又撞第二个，步数照样烧完
        assertTrue("该列出起终点两个越界点：$issue", issue.contains("(1500,900)"))
        assertTrue(issue.contains("(500,3000)"))
        assertTrue("在界内的那个点不该被牵连：$issue", !issue.contains("(500,800)"))
        assertTrue(issue.contains("0..1079"))
    }

    @Test
    fun `多点动作全在界内时不报问题`() {
        assertNull(pointsBoundsIssue(listOf(10 to 10, 1000 to 2000), 1080, 2400))
        // 空列表不算越界（调用方给了个没内容的手势，不是坐标错）
        assertNull(pointsBoundsIssue(emptyList(), 1080, 2400))
    }

    @Test
    fun `原因里带的是模型能直接照做的信息`() {
        // 「越界」两个字本身没用：要给具体边界，模型才能换算或重新读屏
        val issue = boundsIssue(2000, -50, 1080, 2400)!!

        assertTrue(issue.contains("2000"))
        assertTrue(issue.contains("-50"))
        assertTrue(issue.contains("0..1079"))
        assertTrue(issue.contains("0..2399"))
        assertEquals(true, issue.startsWith("坐标越界"))
    }
}
