package io.zer0.muse.intent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-20261011-TASKER-ABUSE（.audit REPORT_SECURITY F1 / 性能 F2）：
 *
 * TaskerTriggerReceiver 是 exported=true 且无 permission 的广播入口
 * （AndroidManifest.xml:263-269），任意应用可循环发送
 * io.zer0.muse.action.TASKER_TRIGGER。缓解已验证：completeText 不带 tools，
 * 外部广播无法间接触发工具执行、无用户数据外泄面。但缺三道成本防线：
 *  1) 无开关 —— 用户从未启用过 Tasker 联动也会响应；
 *  2) 无长度上限 —— 超长 extra 直通计费 LLM；
 *  3) 无限频 —— 可循环触发连续调用。
 *
 * 判定抽成纯函数以便单测（Receiver 依赖 BroadcastReceiver + Koin，单元内不可直接构造）。
 */
class TaskerTriggerPolicyTest {

    @Test
    fun `disabled by default rejects the trigger`() {
        assertEquals(
            "默认关闭时必须拒绝(Tasker 联动是可选功能,不该默认敞开)",
            TaskerTriggerGuard.Decision.REJECTED_DISABLED,
            TaskerTriggerGuard.evaluate(
                enabled = false,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `blank prompt is rejected`() {
        assertEquals(
            TaskerTriggerGuard.Decision.REJECTED_BLANK,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 0,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `oversized prompt is rejected`() {
        assertEquals(
            "超长 prompt 必须拒绝,避免外部应用把巨型文本直通计费模型",
            TaskerTriggerGuard.Decision.REJECTED_TOO_LONG,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 100_000,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `rapid retrigger inside the interval is throttled`() {
        assertEquals(
            "限频窗口内的重复广播必须节流,否则可被循环触发刷计费",
            TaskerTriggerGuard.Decision.REJECTED_THROTTLED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = 800L,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `first trigger with valid prompt is accepted`() {
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `trigger after the interval elapsed is accepted`() {
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 10_000L,
                lastAcceptedAtMs = 1_000L,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `clock regression does not permanently block`() {
        // 系统时间回拨:now 早于 lastAccepted,不得判定为"永不限频"
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 500L,
                lastAcceptedAtMs = 9_000L,
                minIntervalMs = 5_000L,
            ),
        )
    }
}
