package io.zer0.muse.ui.chat

import io.zer0.muse.tools.SessionPermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-20261011-A2A-ALLOWALL（.audit REPORT_AGENT F1 / 安全链路）：
 *
 * `appRunAllowAllTools` 是**进程级**状态位（用户在任意审批卡勾一次"本次开启期间批准全部"
 * 即置 true），此前它被无条件映射为 TRUSTED —— 而 A2A 远程请求经 chatViewModel.send()
 * 走同一条审批链，且 /a2a 只有 JWT 鉴权、没有焦点校验。后果：用户勾过一次后，
 * 远程无人在屏幕前即可静默执行 device_shell / app_clear_data / ui_agent 等 HIGH 设备工具
 * （TRUSTED 在 ToolPermissionResolver 直通 Auto，不走 Pending、30s 审批超时兜底也不生效）。
 *
 * 修复口径：全局放行位只在"用户当前正看着该会话"时升为 TRUSTED；
 * 非焦点会话（A2A 新建/劫持的会话、后台生成）回落到该会话自己的权限模式
 * （默认 ASK → HIGH 风险仍走审批/超时拒绝）。
 */
class RemoteApprovalModeTest {

    @Test
    fun `allow-all applies to the session the user is looking at`() = runBlocking {
        assertEquals(
            SessionPermissionMode.TRUSTED,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = "s1",
                sessionId = "s1",
                focusedMode = SessionPermissionMode.ASK,
                persistedModeProvider = { SessionPermissionMode.STRICT },
            ),
        )
    }

    @Test
    fun `allow-all does not apply to a session the user is not looking at`() = runBlocking {
        // A2A 远程触发:焦点在用户会话 s1,生成却跑在被切走的 s2 / A2A 新建会话
        assertEquals(
            "远程/后台会话不得继承全局放行位,否则无人在屏幕前也会静默执行 HIGH 设备工具",
            SessionPermissionMode.ASK,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = "s1",
                sessionId = "s2",
                focusedMode = SessionPermissionMode.TRUSTED,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }

    @Test
    fun `focused session without allow-all uses the focused permission mode`() = runBlocking {
        assertEquals(
            SessionPermissionMode.STRICT,
            resolveApprovalMode(
                allowAllToolsThisRun = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                focusedMode = SessionPermissionMode.STRICT,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }

    @Test
    fun `unfocused session without allow-all uses the persisted session mode`() = runBlocking {
        assertEquals(
            SessionPermissionMode.TRUSTED,
            resolveApprovalMode(
                allowAllToolsThisRun = false,
                displayedSessionId = "s1",
                sessionId = "s2",
                focusedMode = SessionPermissionMode.ASK,
                persistedModeProvider = { SessionPermissionMode.TRUSTED },
            ),
        )
    }

    @Test
    fun `null focus never escalates to trusted`() = runBlocking {
        assertEquals(
            SessionPermissionMode.ASK,
            resolveApprovalMode(
                allowAllToolsThisRun = true,
                displayedSessionId = null,
                sessionId = "s2",
                focusedMode = SessionPermissionMode.ASK,
                persistedModeProvider = { SessionPermissionMode.ASK },
            ),
        )
    }
}
