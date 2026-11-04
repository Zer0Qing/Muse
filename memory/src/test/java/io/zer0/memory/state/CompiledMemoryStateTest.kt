package io.zer0.memory.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编译产物规范化（`:memory` 体检第五处）。
 *
 * 修的是注释与实现相反的一处：注释说保留 facts 段内的 `### Work` / `### Personal` 子分类，
 * 实现却把开头**任意**标题都剥掉 —— 正文以子分类开头时分类名就没了，
 * 事实被摊平成一大片，用户看不出哪条属于哪类。现在只剥「模型复述的段标题」与 1~2 级前言。
 */
class CompiledMemoryStateTest {

    @Test
    fun `正文开头的子分类标题不许被剥掉`() {
        val body = "### Work\n- 周三交报告\n\n### Personal\n- 喜欢美式咖啡"

        val normalized = CompiledMemoryState.normalizeSectionBody(body)

        assertTrue("分类名要留着: $normalized", normalized.startsWith("### Work"))
        assertTrue(normalized.contains("### Personal"))
        assertTrue(normalized.contains("周三交报告"))
    }

    @Test
    fun `模型复述的段标题要剥掉`() {
        listOf(
            "### 重要事实\n- 对青霉素过敏",
            "#### 重要事实\n- 对青霉素过敏",
            "### Key Facts\n- allergic",
            "## 事情经过\n- 讨论了体检",
        ).forEach { body ->
            val normalized = CompiledMemoryState.normalizeSectionBody(body)
            assertFalse("段标题不该留在正文开头: $normalized", normalized.startsWith("#"))
            assertTrue("内容要保住", normalized.isNotBlank())
        }
    }

    @Test
    fun `文档级前言被剥掉而三级分类保留`() {
        val normalized = CompiledMemoryState.normalizeSectionBody("# 摘要\n## Overview\n### Work\n- 一条")

        assertEquals("只该剩到子分类那层", "### Work\n- 一条", normalized)
    }

    @Test
    fun `剥离只管开头 正文里的标题原样留着`() {
        val normalized = CompiledMemoryState.normalizeSectionBody("- 第一条\n\n### 补充\n- 第二条")

        assertTrue("正文中段的标题是内容的一部分", normalized.contains("### 补充"))
        assertTrue(normalized.startsWith("- 第一条"))
    }

    @Test
    fun `think 块闭合与未闭合都剥得掉`() {
        val closed = CompiledMemoryState.normalizeSectionBody("<think>我在想</think>- 一条事实")
        val open = CompiledMemoryState.normalizeSectionBody("<thinking>很长的思考")
        val upper = CompiledMemoryState.normalizeSectionBody("<THINK>x</THINK>- 一条")

        assertEquals("- 一条事实", closed)
        assertTrue("未闭合的思考块整段丢掉", open.isEmpty())
        assertEquals("- 一条", upper)
    }

    @Test
    fun `孤立未闭合 think 标签能被识别`() {
        assertTrue(CompiledMemoryState.hasDanglingLeadingThinkTag("<think>abc"))
        assertFalse("有闭合就不算孤立", CompiledMemoryState.hasDanglingLeadingThinkTag("<think>abc</think>"))
        assertFalse(CompiledMemoryState.hasDanglingLeadingThinkTag("正常正文"))
    }

    @Test
    fun `JSON 字符串数组转成 bullet 且保留转义`() {
        val normalized = CompiledMemoryState.normalizeSectionBody("""["对青霉素过敏", "喜欢\"美式\""]""")

        assertEquals("- 对青霉素过敏\n- 喜欢\"美式\"", normalized)
    }

    @Test
    fun `连续空行压成一个空行 空输入给空串`() {
        assertEquals("- a\n\n- b", CompiledMemoryState.normalizeSectionBody("- a\n\n\n\n\n- b"))
        assertEquals("", CompiledMemoryState.normalizeSectionBody(""))
    }

    @Test
    fun `围栏被剥掉`() {
        val fenced = CompiledMemoryState.normalizeLlmResult("```markdown\n### 重要事实\n- 一条\n```", "compileFacts")

        assertEquals("### 重要事实\n- 一条", fenced)
        assertEquals("", CompiledMemoryState.normalizeLlmResult("", "compileFacts"))
    }

    @Test
    fun `编译产物文件清单是这五份`() {
        assertEquals(listOf("memory.md", "facts.md", "today.md", "week.md", "longterm.md"), CompiledMemoryState.COMPILED_FILES)
    }
}
