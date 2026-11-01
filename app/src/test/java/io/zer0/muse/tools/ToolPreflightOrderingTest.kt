package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结构回归：**通道预检必须早于审批**。
 *
 * 背景：`ToolOrchestrator` 里预检一度排在审批之后 —— 任何注定失败的调用
 * （没开无障碍 / 没装 Shizuku / 自动化总闸关着）都会先弹一次审批，
 * 用户点了「允许」才被告知执行不了。这类「白打扰」在 UI 上极难被测出来，
 * 所以用源码顺序钉住，改回去就红。
 */
class ToolPreflightOrderingTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/tools/ToolOrchestrator.kt").readText()
    }

    @Test
    fun `channel preflight runs before the approval gate`() {
        val preflightAt = source.indexOf("ToolChannelPreflight.checkPreflightable(")
        val approvalAt = source.indexOf("等待审批(APPROVAL_PENDING)")
        assertTrue("应能找到预检调用", preflightAt >= 0)
        assertTrue("应能找到审批等待日志", approvalAt >= 0)
        assertTrue(
            "通道预检必须排在审批之前（现在 preflight=$preflightAt, approval=$approvalAt）：" +
                "否则注定失败的调用会先弹审批，白打扰用户一次",
            preflightAt < approvalAt,
        )
    }

    @Test
    fun `preflight consults the automation master switch`() {
        val call = source.substringAfter("ToolChannelPreflight.checkPreflightable(").take(200)
        assertTrue("预检必须带上总闸判断，实际: $call", call.contains("automationEnabled"))
    }
}
