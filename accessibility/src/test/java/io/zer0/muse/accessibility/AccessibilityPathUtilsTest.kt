package io.zer0.muse.accessibility

import org.junit.Assert.assertEquals
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
}
