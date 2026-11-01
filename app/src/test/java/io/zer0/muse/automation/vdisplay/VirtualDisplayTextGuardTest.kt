package io.zer0.muse.automation.vdisplay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏文本输入的可行动拒绝原因。
 *
 * 背景：`build()` 对含引号/反斜杠/换行的文本返回 null，调用方原来只回「参数无效」——
 * 模型分不清是格式错还是文字里有特殊字符，只能盲试。这里钉住「原因要具体」，
 * 并保证「reason 为 null 时 build 一定能出命令」，避免两处判断漂移。
 */
class VirtualDisplayTextGuardTest {

    private fun reason(text: String) = VirtualDisplayInputCommand.textRejectionReason(text)

    @Test
    fun `plain ascii and chinese text are accepted`() {
        assertNull(reason("hello world"))
        assertNull(reason("你好，帮我把设置打开"))
        assertNull(reason("a-b_c.123"))
    }

    @Test
    fun `single quote explains that quotes are unsupported`() {
        val r = reason("it's fine")
        assertNotNull(r)
        assertTrue("应说明是引号问题，实际: $r", r!!.contains("单引号"))
        assertTrue("应给出替代方案，实际: $r", r.contains("screen_input") || r.contains("foreground"))
    }

    @Test
    fun `newline and backslash are named in the reason`() {
        assertTrue(reason("line1\nline2")!!.contains("换行"))
        assertTrue(reason("path\\to")!!.contains("反斜杠"))
    }

    @Test
    fun `over long text suggests splitting`() {
        val r = reason("a".repeat(501))
        assertNotNull(r)
        assertTrue("应提示拆分，实际: $r", r!!.contains("拆"))
    }

    @Test
    fun `reason null implies build succeeds for text action`() {
        val ok = "hello world 你好"
        assertNull(reason(ok))
        val command = VirtualDisplayInputCommand.build(
            displayId = 1,
            action = "text",
            args = mapOf("text" to ok),
        )
        assertNotNull("reason 通过时 build 必须能出命令（两处判断不能漂移）", command)
    }
}
