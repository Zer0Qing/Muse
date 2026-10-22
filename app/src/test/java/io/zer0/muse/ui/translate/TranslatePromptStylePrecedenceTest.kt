package io.zer0.muse.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定单条与批量翻译的指令一致性。
 *
 * 起因：把批量翻译接上术语表时，我一开始照抄了批量的旧写法（内置风格 +
 * 自定义风格叠加），而单条翻译是「自定义命中则覆盖内置」。同一个风格选项在
 * 两条路径上产生不同译风 —— 这类分歧不会让任何一侧报错，只能靠断言钉住。
 */
class TranslatePromptStylePrecedenceTest {

    private val custom = TranslateViewModel.CustomStyle(name = "古风", prompt = "用文言文翻译")

    private fun countWhere(prompt: String, needle: String): Int = prompt.lineSequence().count { it.contains(needle) }

    @Test
    fun `custom style overrides builtin style in single prompt`() {
        val prompt = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "中文",
            style = "古风",
            customStyles = listOf(custom),
        )
        assertTrue(prompt.contains("用文言文翻译"))
        // 内置兜底指令不得同时出现
        assertFalse(prompt.contains("使用通用、准确、自然的表达方式"))
    }

    @Test
    fun `batch prompt obeys custom style the same way as single`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "古风",
            customStyles = listOf(custom),
        )
        assertTrue("批量应使用自定义风格", batch.contains("用文言文翻译"))
        assertFalse(
            "批量不得把内置风格与自定义风格叠加（与单条保持同一优先级）",
            batch.contains("使用通用、准确、自然的表达方式"),
        )
    }

    @Test
    fun `unknown style falls back to builtin in both prompts`() {
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "中文",
            style = "学术",
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "学术",
        )
        assertTrue(single.contains("使用学术、正式、严谨的表达方式"))
        assertTrue(batch.contains("使用学术、正式、严谨的表达方式"))
    }

    @Test
    fun `glossary line appears exactly once in each prompt`() {
        val glossary = mapOf("API" to "应用程序接口")
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "use API",
            targetLanguage = "中文",
            glossary = glossary,
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("use API"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "通用",
            glossary = glossary,
        )
        // 抽取共享 helper 后，术语表行只可能来自一处；若将来又出现重复，这里先失败
        assertEquals(1, countWhere(single, "请参考以下术语表"))
        assertEquals(1, countWhere(batch, "请参考以下术语表"))
        assertEquals(1, countWhere(single, "API→应用程序接口"))
        assertEquals(1, countWhere(batch, "API→应用程序接口"))
    }
}
