package io.zer0.muse.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「屏幕读不全要说得出来」这条链路的接线契约（`:accessibility` §5 里我原本只能说"代码读查"）。
 *
 * 纯函数侧已经有 `ScreenInfoIncompleteTest` 钉住原因文案与排布顺序；
 * 这里补的是另一半 —— **读屏的实现真的把原因填上了**。
 * 少了这一半，`incompleteReason` 就永远是个空的漂亮字段：
 * 模型仍会把"没读到"当成"屏幕上没有"，于是找不到控件、误点、或报告"界面上没这个按钮"。
 *
 * 为什么用源码断言：要触发这两条分支得有一棵真实的 `AccessibilityNodeInfo` 树
 * （或者树被系统中途回收），单测里造不出来；而真正会发生的退化是"改代码时把填值删了"。
 */
class ScreenReadCompletenessWiringTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/automation/executors/AccessibilityExecutor.kt"),
            File("app/src/main/java/io/zer0/muse/automation/executors/AccessibilityExecutor.kt"),
        )
        // requireNotNull 而不是 `?: fail(...)`：junit 的 fail 返回 void，
        // 放进 elvis 会让表达式退化成 Any，后面 .readText() 就找不到了。
        val file = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 AccessibilityExecutor.kt，工作目录=${File(".").absolutePath}"
        }
        return file.readText()
    }

    @Test
    fun `遍历状态被记录且传给 ScreenInfo`() {
        val code = source()

        assertTrue("深度超限必须记录（否则静默丢控件没人知道）", code.contains("depthTruncated = true"))
        assertTrue("超限的那一步要真的走到记录，不能只定义字段", code.contains("state.depthTruncated"))
        assertTrue(
            "ScreenInfo 必须拿到残缺原因（用 screenReadIssue 合成，别自己拼一份文案）",
            code.contains("incompleteReason = screenReadIssue("),
        )
    }

    @Test
    fun `中断与深度两条来源都接上`() {
        val code = source()

        // 两条独立来源：遍历中途抛异常、层级超过上限
        assertTrue("必须捕获遍历异常并留下原因", code.contains("collectError = "))
        assertTrue("原因里要带实际拿到的控件数", code.contains("collected = nodes.size"))
        assertTrue("要带上深度上限这一个事实", code.contains("maxDepth = MAX_TREE_DEPTH"))
    }

    @Test
    fun `原因只有一处定义`() {
        val code = source()

        // 防"就地拼文案"：文案归 screenReadIssue，实现里再拼一份就是第二处真相
        val localWording = listOf("只拿到中断前的", "更深的控件没有读到")
        localWording.forEach { wording ->
            assertTrue("文案不该在读取实现里重复出现（应在 screenReadIssue 里）：$wording", !code.contains(wording))
        }
    }
}
