package io.zer0.muse.intent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F10: Tasker 结果通知不得硬编码 policy="always"。
 *
 * 主链路(ChatGenerationController)把用户设置的 never/when_unfocused/always
 * 传入 notifyChatCompletedWithPolicy,而 Tasker 路径此前硬编码 always,
 * 导致用户选“从不通知”时每次 Tasker 触发仍弹通知。
 */
class TaskerNotificationPolicyTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt").readText()

    @Test
    fun `tasker notification reads user policy`() {
        val src = source()
        val start = src.indexOf("AUD-CHANNEL-F10")
        assertTrue("未找到 AUD-CHANNEL-F10 防护块", start >= 0)
        val body = src.substring(start, (start + 600).coerceAtMost(src.length))
        assertTrue(
            "必须读取用户通知策略",
            body.contains("settings?.notificationPolicyFlow?.first()"),
        )
        assertTrue(
            "必须把读到的策略传给通知 API",
            body.contains("policy = policy"),
        )
    }

    @Test
    fun `no hardcoded always policy remains`() {
        val src = source()
        val code = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "仍存在硬编码 policy = \"always\"（注释除外）",
            !code.contains("policy = \"always\""),
        )
    }
}
