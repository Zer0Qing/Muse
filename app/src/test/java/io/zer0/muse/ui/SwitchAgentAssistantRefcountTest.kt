package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F4: switchAgentAssistant 的会话引用计数切换不得误释放仍在使用的会话。
 *
 * 原实现在协程内先 release(`_state.value.agentSessionId`)再 acquire。快速 A→B
 * 连续切换时,B 路径读到的旧值可能已是 A 刚 acquire 的新会话,release 会多减引用计数,
 * 5s idle reaper 随后把仍在使用的会话资源清掉。
 *
 * 修复:先快照旧值 → 先 acquire 新会话 → 再 release 快照的旧值(且排除同 id 重复释放)。
 */
class SwitchAgentAssistantRefcountTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatViewModel.kt").readText()

    @Test
    fun `refcount switch snapshots previous session before release`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F4")
        assertTrue("未找到 AUD-UI-F4 防护块", start >= 0)
        val body = src.substring(start, (start + 800).coerceAtMost(src.length))
        assertTrue(
            "必须先快照旧会话 id",
            body.contains("val previousAgentSessionId = _state.value.agentSessionId"),
        )
        assertTrue(
            "必须先 acquire 新会话再释放旧会话(先增后减,不会为负)",
            body.indexOf("sessionManager.acquire(sessionId)") <
                body.indexOf("sessionManager.release(previousAgentSessionId)"),
        )
    }

    @Test
    fun `release skips identical session id`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F4")
        assertTrue("未找到 AUD-UI-F4 防护块", start >= 0)
        val body = src.substring(start, (start + 800).coerceAtMost(src.length))
        assertTrue(
            "同 id 不得既 acquire 又 release",
            body.contains("previousAgentSessionId != sessionId"),
        )
    }
}
