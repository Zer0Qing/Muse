package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户偏好提示词渲染的测试。
 *
 * 「硬约束」能不能生效，一半在存储，一半在措辞。写成「尽量」模型就当建议，
 * 所以这里钉住：空列表不注入、每条都列出、措辞是强制的。
 */
class UserPreferencePromptTest {

    private fun render(rules: List<String>) = SystemPromptAssembler.renderUserPreferences(rules)

    @Test
    fun `no preferences means no section at all`() {
        assertEquals("", render(emptyList()))
    }

    @Test
    fun `blank rules do not produce an empty section`() {
        assertEquals("", render(listOf("", "   ", "\n")))
    }

    @Test
    fun `each rule is listed`() {
        val out = render(listOf("回答不要用空泛的鼓励", "不要列三点总结"))

        assertTrue(out.contains("回答不要用空泛的鼓励"))
        assertTrue(out.contains("不要列三点总结"))
    }

    @Test
    fun `wording marks it as a hard constraint not a suggestion`() {
        val out = render(listOf("别用空泛鼓励"))

        assertTrue("必须说清是硬约束", out.contains("必须遵守"))
        assertTrue("要明确优先级", out.contains("优先级高于"))
        assertFalse("不能写成软性建议", out.contains("尽量"))
    }

    @Test
    fun `conflict handling is spelled out`() {
        val out = render(listOf("别用空泛鼓励"))

        assertTrue("冲突时以偏好为准要说清", out.contains("以此为准"))
        assertTrue("无法满足时要说明而不是静默忽略", out.contains("说明原因"))
    }

    @Test
    fun `rules are trimmed`() {
        val out = render(listOf("  别用空泛鼓励  "))

        assertTrue(out.contains("- 别用空泛鼓励"))
    }
}
