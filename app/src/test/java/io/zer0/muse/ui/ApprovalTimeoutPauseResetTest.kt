package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F12: 审批超时暂停状态必须在离开组合时复位。
 *
 * 原实现只用 LaunchedEffect(approvals.size, approvalsExpanded) 驱动:
 * 离开会话页时 pause=true,返回时若 key 恢复成同一组合,effect 不重跑,
 * VM 的 approvalTimeoutPaused 残留 true —— 新会话审批被错误地永久暂停,
 * 不再触发 30s 自动拒绝。
 */
class ApprovalTimeoutPauseResetTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatScreen.kt").readText()

    @Test
    fun `disposable effect resets pause on leaving composition`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F12")
        assertTrue("未找到 AUD-UI-F12 防护块", start >= 0)
        val body = src.substring(start, (start + 600).coerceAtMost(src.length))
        assertTrue(
            "必须用 DisposableEffect 在离开时复位",
            body.contains("DisposableEffect(Unit)"),
        )
        assertTrue(
            "onDispose 必须复位暂停状态",
            body.contains("onDispose { viewModel.setApprovalTimeoutPaused(false) }"),
        )
    }
}
