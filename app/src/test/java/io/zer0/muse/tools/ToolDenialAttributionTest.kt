package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拒绝归因：谁决定的，就得写成谁（P4 协作语义续）。
 *
 * 缺陷原样：审批有三条**不是用户**的路也会落到 `ToolApprovalState.Denied`
 * （`ToolCallGate` 的 30 秒超时、`ParamPolicies` 的策略拦截、通道未就绪/生成已停止），
 * 而消费方一律写死 `"Tool denied by user"`，审计备注一律写 `"user_denied"`。
 *
 * 后果是双向的：模型读到"用户拒绝了"会去道歉、不会改道；
 * 用户在任务卡与日志里被记了一件**没做过**的事。
 */
class ToolDenialAttributionTest {

    @Test
    fun `四种来源各有一句独立的话`() {
        val labels = DenySource.entries.map { denialErrorLabel(it) }

        assertTrue("每句都要有内容", labels.all { it.isNotBlank() })
        assertEquals("四种来源不能挤成同一句", DenySource.entries.size, labels.distinct().size)
    }

    @Test
    fun `只有用户真的点了拒绝才写 denied by user`() {
        assertTrue(denialErrorLabel(DenySource.USER).contains("denied by user"))

        // 其余三类都不许出现"by user"：那是把系统行为算到用户头上
        listOf(DenySource.TIMEOUT, DenySource.POLICY, DenySource.SYSTEM).forEach { source ->
            val label = denialErrorLabel(source)
            assertFalse("$source 不该被写成用户拒绝: $label", label.contains("user"))
        }
    }

    @Test
    fun `超时要让模型看得出是没等到答复而不是被驳回`() {
        // 区别很实际：被驳回该改方案，没等到答复该缩短等待或换个时机再问
        assertTrue(denialErrorLabel(DenySource.TIMEOUT).contains("timed out"))
        assertTrue(denialErrorLabel(DenySource.POLICY).contains("policy"))
        assertTrue(denialErrorLabel(DenySource.SYSTEM).contains("could not be resolved"))
    }

    @Test
    fun `审计备注只有用户那一支保持原 token`() {
        // 日志口径与既有测试都认 user_denied，不能为了这次修复把它改掉
        assertEquals("user_denied", denialAuditNote(DenySource.USER))
        assertEquals("approval_timed_out", denialAuditNote(DenySource.TIMEOUT))
        assertEquals("policy_denied", denialAuditNote(DenySource.POLICY))
        assertEquals("approval_unresolved", denialAuditNote(DenySource.SYSTEM))
    }

    @Test
    fun `默认来源是用户 老调用点语义不变`() {
        // UI 上用户按下"拒绝"那条路径没有传 source：默认必须是 USER，
        // 否则这次修复会把真实拒绝也一并洗成"系统行为"
        val fromUi = ToolApprovalState.Denied("用户点了拒绝")

        assertEquals(DenySource.USER, fromUi.source)
        assertTrue(denialErrorLabel(fromUi.source).contains("denied by user"))
    }

    @Test
    fun `拒绝仍算终态 不因加了来源字段而漏判`() {
        val denied = ToolApprovalState.Denied("审批等待超时(30s)", source = DenySource.TIMEOUT)

        assertTrue(denied.isTerminal)
        assertFalse("被拒的调用不能被判成可执行", denied.isExecutable)
        assertTrue(denied.reason.contains("30"))
    }
}
