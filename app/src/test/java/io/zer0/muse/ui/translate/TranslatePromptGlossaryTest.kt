package io.zer0.muse.ui.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: 批量翻译的提示词必须与单条一致地遵守术语表与自定义风格。
 *
 * 回归背景：buildTranslationPrompt（单条）一直传 glossary/customStyles，
 * buildBatchPrompt（批量）两者都不传 —— 用户设好的术语表只在单条里生效，
 * 批量整段忽略。这属于"同一功能两条路径只接了一半"的典型半成品。
 */
class TranslatePromptGlossaryTest {

    private val glossary = mapOf("API" to "应用程序接口", "GPU" to "图形处理器")

    @Test
    fun `batch prompt includes glossary like single prompt does`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("Use the API", "The GPU is fast"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "通用",
            glossary = glossary,
        )

        assertTrue("批量提示词应带术语表", batch.contains("请参考以下术语表"))
        assertTrue("应含 API 映射", batch.contains("API→应用程序接口"))
        assertTrue("应含 GPU 映射", batch.contains("GPU→图形处理器"))

        // 单条必须同样带上，两条路径不能漂开
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "Use the API",
            targetLanguage = "中文",
            sourceLanguage = "英语",
            glossary = glossary,
        )
        assertTrue("单条提示词应带术语表", single.contains("请参考以下术语表"))
    }

    @Test
    fun `empty glossary adds no instruction to either prompt`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "通用",
        )
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "中文",
            sourceLanguage = "英语",
        )
        assertFalse(batch.contains("请参考以下术语表"))
        assertFalse(single.contains("请参考以下术语表"))
    }

    @Test
    fun `batch prompt honours custom style`() {
        val custom = TranslateViewModel.CustomStyle(
            name = "技术文档",
            prompt = "术语保持业界通用译法,缩写不翻译",
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("the API"),
            targetLanguage = "中文",
            sourceLanguage = "英语",
            style = "技术文档",
            customStyles = listOf(custom),
        )
        assertTrue("批量应使用自定义风格指令", batch.contains("术语保持业界通用译法,缩写不翻译"))
    }
}
