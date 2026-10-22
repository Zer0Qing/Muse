package io.zer0.muse.tools

import io.zer0.muse.tools.ToolFailureClassifier.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具失败分类的测试。
 *
 * 助手自审：「失败只允许修正一次这条约束是对的，但错误信息含糊时我只能猜，一猜就废」。
 * 这里钉住分类与可重试性：权限类必须不可重试（重试无用），参数类可重试，
 * 判断不了时保守判不可重试。
 */
class ToolFailureClassifierTest {

    private fun classify(text: String, timedOut: Boolean = false) = ToolFailureClassifier.classify(text, timedOut)

    @Test
    fun `timeout is retryable`() {
        val v = classify("[超时] 工具 web_fetch 120 秒未响应,已终止")

        assertEquals(FailureKind.TIMEOUT, v.kind)
        assertTrue("超时通常可安全重试一次", v.retryable)
    }

    @Test
    fun `timeout flag wins even when text looks like something else`() {
        val v = classify("网络连接失败", timedOut = true)

        assertEquals(FailureKind.TIMEOUT, v.kind)
    }

    @Test
    fun `cancellation is not a failure and must not be retried`() {
        val v = classify("[取消] 用户停止生成")

        assertEquals(FailureKind.CANCELLED, v.kind)
        assertFalse("取消不是错误,重试会违背用户意图", v.retryable)
    }

    @Test
    fun `permission failure is not retryable`() {
        val v = classify("[错误] 缺少 Shizuku 授权,无法执行 shell 命令")

        assertEquals(FailureKind.PERMISSION, v.kind)
        assertFalse("重试不会让权限凭空出现", v.retryable)
        assertTrue("建议应指向 tool_status", v.hint.contains("tool_status"))
    }

    @Test
    fun `accessibility channel failure is classified as permission`() {
        assertEquals(FailureKind.PERMISSION, classify("[错误] 无障碍服务未启用").kind)
    }

    @Test
    fun `not found is not retryable with the same value`() {
        val v = classify("[错误] 文件不存在或不是普通文件: notes/todo.md")

        assertEquals(FailureKind.NOT_FOUND, v.kind)
        assertFalse(v.retryable)
    }

    @Test
    fun `network hiccup is retryable`() {
        // 用「连接失败」而非「timeout」:后者会被更靠前的 TIMEOUT 分支优先命中,那是正确行为。
        val v = classify("[错误] 网络连接失败: 连接被重置")

        assertEquals(FailureKind.TRANSIENT, v.kind)
        assertTrue(v.retryable)
    }

    @Test
    fun `rate limit is retryable`() {
        assertEquals(FailureKind.TRANSIENT, classify("[错误] 429 Too Many Requests").kind)
    }

    @Test
    fun `blocked by approval is not retryable`() {
        val v = classify("[错误] 操作被安全策略拦截")

        assertEquals(FailureKind.BLOCKED, v.kind)
        assertFalse("不要绕过审批", v.retryable)
    }

    @Test
    fun `invalid argument is retryable after fixing`() {
        val v = classify("[错误] 缺少必填参数: path")

        assertEquals(FailureKind.INVALID_ARGUMENT, v.kind)
        assertTrue(v.retryable)
    }

    @Test
    fun `unrecognised failure defaults to not retryable`() {
        val v = classify("[错误] 未知的内部状态 xyz")

        assertEquals(FailureKind.UNKNOWN, v.kind)
        assertFalse("判断不了就不要鼓励重试", v.retryable)
    }

    @Test
    fun `receipt line is machine readable`() {
        val line = ToolFailureClassifier.receiptLine(classify("[错误] 权限不足"))

        assertTrue(line.contains("kind=permission"))
        assertTrue(line.contains("retryable=false"))
        assertTrue("应带 hint", line.contains("hint="))
    }

    @Test
    fun `english permission text is recognised`() {
        assertEquals(FailureKind.PERMISSION, classify("Error: permission denied for this operation").kind)
    }

    @Test
    fun `cancellation wins over permission wording`() {
        // 取消消息里常带「权限」以外的词,不能被别的分支抢走
        val v = classify("[取消] 等待授权时用户中断")

        assertEquals(FailureKind.CANCELLED, v.kind)
    }
}
