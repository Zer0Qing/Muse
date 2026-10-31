package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批倒计时冻结判据。
 *
 * 除「用户折叠阅读待审批列表」外，审批所属会话不在前台时也应冻结：
 * 否则用户切去别的会话几分钟再回来，只会看到一条早已超时的拒绝记录，
 * 模型还会把这次超时当成用户拒绝而改道。
 */
class ApprovalTimeoutFocusPauseTest {

    @Test
    fun `focused session keeps counting down`() {
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unfocused session freezes the countdown`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s2",
            ),
        )
    }

    @Test
    fun `folded reading freezes the countdown even when focused`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = true,
                displayedSessionId = "s1",
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unknown focus keeps the original counting behavior`() {
        // 会话尚未初始化时拿不到焦点，此时不能判成失焦——否则审批会被永久冻结。
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = null,
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unfocused pause budget is bounded`() {
        // 失焦冻结必须有上限，否则用户不再回来时协程与会话轮次会永久挂住。
        assertTrue(ChatToolApprovalCoordinator.APPROVAL_UNFOCUSED_PAUSE_MAX_MS > 0L)
        assertTrue(
            ChatToolApprovalCoordinator.APPROVAL_UNFOCUSED_PAUSE_MAX_MS >
                ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS,
        )
    }
}
