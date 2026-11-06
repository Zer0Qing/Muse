package io.zer0.muse.transformer

import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实机走查 实-W3-04 / 三-8：英文界面用 "hola" 提问却收到中文回复。
 * 语言规则原先只挂在「思考语言」段尾，模型把它当成对推理过程的约束；
 * 这里钉住独立成段的正文语言契约。
 */
class ReplyLanguageInstructionTest {

    @Test
    fun `正文语言段给出跟随用户与回落界面语言两条`() {
        val prompt = replyLanguageInstruction("en")
        assertTrue(prompt.contains("正文使用用户本轮消息所用的语言"))
        assertTrue(prompt.contains("应用界面语言：English"))
    }

    @Test
    fun `界面语言名跟随 locale 而不是写死中文`() {
        assertTrue(replyLanguageInstruction("es").contains("Español"))
        assertTrue(replyLanguageInstruction("ja").contains("日本語"))
        assertTrue(replyLanguageInstruction("zh-CN").contains("中文"))
    }

    @Test
    fun `明确禁止因为提示词是中文就改用中文`() {
        // 提示词是模型面、固定中文，这一句是这条 bug 的直接对症项。
        assertTrue(replyLanguageInstruction("en").contains("历史消息是中文就改用中文"))
    }

    @Test
    fun `开关默认开启`() {
        assertTrue(ChatPreferences().replyInUserLanguageEnabled)
        assertFalse(ChatPreferences(replyInUserLanguageEnabled = false).replyInUserLanguageEnabled)
    }
}
