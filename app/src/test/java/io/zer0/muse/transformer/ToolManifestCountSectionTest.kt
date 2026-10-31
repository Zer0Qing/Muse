package io.zer0.muse.transformer

import io.zer0.muse.tools.ToolCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词工具索引头部的计数文案：只报「本轮可调用」，不再拿注册总数吓模型。 */
class ToolManifestCountSectionTest {

    private val counts = ToolCounts(callableInSchema = 12, sessionVisible = 20, registeredTotal = 33)
    private val section =
        SystemPromptAssembler.toolManifestCountSection(
            counts,
            mapOf("built-in" to 3, "file" to 9),
        )

    @Test
    fun `reports the callable count`() {
        assertTrue("必须出现「本轮可调用」", section.contains("本轮可调用"))
        assertTrue(section.contains("本轮可调用工具共 12 个"))
    }

    @Test
    fun `does not claim the registered total as callable`() {
        assertFalse("不应再出现「已注册」措辞", section.contains("已注册"))
        assertFalse("不应把注册总数（33）当成可调用数报出", section.contains("33"))
    }

    @Test
    fun `built-in count comes from the same selection as the total`() {
        assertTrue("分类分布需与 N 同源", section.contains("built-in 3"))
        assertTrue("分类分布需带类别名", section.contains("file 9"))
    }

    @Test
    fun `explains the find_tools tier separately`() {
        assertTrue("需说明 find_tools 的口径更大", section.contains("find_tools 能列出 20 个"))
        assertFalse("不能把 20 与「本轮可调用」混成一个数", section.contains("本轮可调用工具共 20 个"))
    }
}
