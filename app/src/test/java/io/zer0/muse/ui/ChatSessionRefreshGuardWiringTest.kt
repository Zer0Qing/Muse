package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F3: 前台从数据库刷新会话(refreshDisplayedSessionFromDatabase)
 * 必须在写 messages 前用 SessionSwitchGuard 校验本次加载是否已被更新的切换取代,
 * 并通过 CAS accessor 写入。
 *
 * 背景(REPORT_UI.md F3):该函数只在写前查一次 displayedSessionId,存在切会话
 * 竞态窗口 —— 旧会话的加载结果可能盖到新会话界面上。
 */
class ChatSessionRefreshGuardWiringTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatViewModel.kt").readText()

    @Test
    fun `refresh path begins a switch token before async load`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("未找到 refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        assertTrue(
            "刷新前必须取得 SessionSwitchGuard token",
            body.contains("SessionSwitchGuard.begin()"),
        )
    }

    @Test
    fun `refresh path drops stale results before writing messages`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("未找到 refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        val staleChecks = body.split("SessionSwitchGuard.isStale(").size - 1
        assertTrue(
            "写入 messages 前与提交前都应有过期校验(实测 $staleChecks 处)",
            staleChecks >= 2,
        )
    }

    @Test
    fun `refresh path writes messages through CAS accessor`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("未找到 refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        // ChatViewModel 自身实现了 ChatStateAccessor,内部可直接调用 updateMessages
        assertTrue(
            "刷新写入必须走 updateMessages(CAS 变换),不能整体赋值",
            body.contains("updateMessages { messages }"),
        )
        assertTrue(
            "不应再对 _messages.value 整体赋值",
            !body.contains("_messages.value = messages"),
        )
    }
}
