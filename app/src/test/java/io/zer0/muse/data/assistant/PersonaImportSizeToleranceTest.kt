package io.zer0.muse.data.assistant

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入一份「本来就超常规上限」的人设卡，不能把它永远挡在门外。
 *
 * 常规上限（200k）的作用是防止误操作把巨大的内容写进人设；但如果一份外来卡本身就超过它，
 * 一律拒绝会造成死结：用户导入不了，也就永远没有机会删减它 —— 只能放弃这份人设。
 * 所以导入路径用宽上限：先让它进来，超出的部分由用户自己处理。
 *
 * 真正异常的体积（远超宽上限）仍然要被挡住，否则这条放宽等于把保护撤了。
 */
class PersonaImportSizeToleranceTest {

    private fun cardWith(systemPromptChars: Int): String = PersonaCardCodec.encode(
        PersonaCard(name = "大块头", systemPrompt = "字".repeat(systemPromptChars)),
    )

    @Test
    fun `超常规上限的卡在导入时能进来`() {
        val text = cardWith(300_000)
        val outcome = PersonaCardCodec.decode(text, PersonaCardCodec.IMPORT_MAX_TEXT_CHARS)
        assertTrue("导入应放行略超常规上限的卡: $outcome", outcome is PersonaParse.Ok)
    }

    @Test
    fun `同一份卡在常规校验下仍被拒`() {
        val text = cardWith(300_000)
        val outcome = PersonaCardCodec.decode(text)
        assertTrue("常规校验仍要挡住超限: $outcome", outcome is PersonaParse.Invalid)
    }

    @Test
    fun `远超宽上限的卡导入时也被挡住`() {
        val text = cardWith(PersonaCardCodec.IMPORT_MAX_TEXT_CHARS + 1)
        val outcome = PersonaCardCodec.decode(text, PersonaCardCodec.IMPORT_MAX_TEXT_CHARS)
        assertTrue("异常体积必须拦住: $outcome", outcome is PersonaParse.Invalid)
    }
}
