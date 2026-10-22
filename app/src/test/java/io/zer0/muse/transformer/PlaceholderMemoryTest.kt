package io.zer0.muse.transformer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编译记忆空壳判定的测试。
 *
 * 助手自审第 19 条：「跨会话的性格一致性锚 —— 让新会话的我，别一上来就像陌生人」。
 *
 * 核查发现机制其实存在（`buildLongTermMemorySection` 按 assistantId 跨会话注入），
 * 但有个真缺陷：`MemoryCompiler.assembleCompiledMarkdown` 对四个空段一律填「（暂无）」，
 * **永远返回非空字符串**。于是新会话会稳定注入：
 *
 *   重要事实：（暂无）／今天：（暂无）／本周早些时候：（暂无）／长期情况：（暂无）
 *
 * 那不是记忆，是噪音 —— 占 token，还让模型以为「我记得些什么」却什么都说不出来。
 */
class PlaceholderMemoryTest {

    private fun isPlaceholder(md: String) = SystemPromptAssembler.isPlaceholderOnly(md)

    /** 与 MemoryCompiler.assembleCompiledMarkdown 输出完全一致的空壳。 */
    private val emptyShell =
        """
        ## 重要事实

        （暂无）

        ## 今天

        （暂无）

        ## 本周早些时候

        （暂无）

        ## 长期情况

        （暂无）
        """.trimIndent() + "\n"

    @Test
    fun `all-placeholder markdown is detected as empty shell`() {
        assertTrue("四段全「（暂无）」就是空壳", isPlaceholder(emptyShell))
    }

    @Test
    fun `english placeholders are detected too`() {
        val en =
            """
            ## Key facts

            (none)

            ## Today

            (none)

            ## Earlier this week

            (none)

            ## Long-term context

            (none)
            """.trimIndent()

        assertTrue(isPlaceholder(en))
    }

    @Test
    fun `real facts are not treated as placeholder`() {
        val real =
            """
            ## 重要事实

            - 用户在做 Android 项目 Muse，偏好简洁回答
            - 用户在 2026-10 提到要做外包

            ## 今天

            （暂无）

            ## 本周早些时候

            （暂无）

            ## 长期情况

            （暂无）
            """.trimIndent()

        assertFalse("有任何真实内容就不能当成空壳", isPlaceholder(real))
    }

    @Test
    fun `a single real line among placeholders is enough to keep it`() {
        val mostlyEmpty = emptyShell.replaceFirst("（暂无）", "- 用户在写一个音乐播放器卡片")

        assertFalse("宁可漏判也不能误判丢掉真记忆", isPlaceholder(mostlyEmpty))
    }

    @Test
    fun `blank markdown is handled by the earlier isBlank check not this one`() {
        // 空串在调用点已被 md.isBlank() 拦掉；这里只确认不抛异常。
        assertTrue(isPlaceholder(""))
    }

    @Test
    fun `headings alone do not count as content`() {
        val onlyHeadings =
            """
            ## 重要事实
            ## 今天
            ## 本周早些时候
            ## 长期情况
            """.trimIndent()

        assertTrue("只有标题没有正文，仍是空壳", isPlaceholder(onlyHeadings))
    }

    @Test
    fun `markdown bullets and emphasis do not count as content`() {
        val symbolsOnly =
            """
            ## 重要事实
            - ** **
            - __ __
            """.trimIndent()

        assertTrue(isPlaceholder(symbolsOnly))
    }
}
