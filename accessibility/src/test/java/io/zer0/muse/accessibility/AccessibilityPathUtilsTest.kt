package io.zer0.muse.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8-05: 无障碍路径解析与文本转义纯函数测试。 */
class AccessibilityPathUtilsTest {

    @Test
    fun parsesValidNodePath() {
        assertEquals(listOf(0, 1, 2), AccessibilityPathUtils.parseNodePath("0.1.2"))
    }

    @Test
    fun rejectsInvalidNodePath() {
        assertTrue(AccessibilityPathUtils.parseNodePath("1.2").isEmpty())
        assertTrue(AccessibilityPathUtils.parseNodePath("").isEmpty())
        assertTrue(AccessibilityPathUtils.parseNodePath("0.a").isEmpty())
    }

    @Test
    fun escapesTextForSingleLineOutput() {
        val escaped = AccessibilityPathUtils.escapeText("a]b\nc\td\\e")
        assertEquals("a\\]b\\nc\\td\\\\e", escaped)
    }

    @Test
    fun rejectsNegativeNodePath() {
        // nodeId 是模型给的工具参数：指到一半没有是常态而不是异常。
        // 以前 "0.-1" 能通过解析，再把 -1 交给 AccessibilityNodeInfo.getChild() ——
        // 那会抛还是返回 null 取决于 ROM，现在在纯函数层统一成「非法」。
        assertTrue(AccessibilityPathUtils.parseNodePath("0.-1").isEmpty())
        assertTrue(AccessibilityPathUtils.parseNodePath("-1").isEmpty())
        assertTrue(AccessibilityPathUtils.parseNodePath("0.1.-2").isEmpty())

        // 根路径本身合法（定位到 root 自己）
        assertEquals(listOf(0), AccessibilityPathUtils.parseNodePath("0"))
    }

    @Test
    fun escapesBothBrackets() {
        // 层级输出用 `[0.1.2]` 当节点前缀，所以文本里的方括号**左右都要**转义；
        // 注释以前写「方括号」却只转义了 `]`。
        assertEquals("按钮 \\[确定\\] 与 \\[ 号", AccessibilityPathUtils.escapeText("按钮 [确定] 与 [ 号"))
    }

    @Test
    fun passwordNodeTextIsNeverExposedToAutomation() {
        assertNull(AccessibilityPathUtils.textForModel("user-secret", isPassword = true))
        assertEquals("Search", AccessibilityPathUtils.textForModel("Search", isPassword = false))
    }
}
