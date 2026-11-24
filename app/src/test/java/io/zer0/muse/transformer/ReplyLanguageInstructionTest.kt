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
        val enPrompt = replyLanguageInstruction("en")
        assertTrue(enPrompt.contains("Reply in the language of the user's current message"))
        assertTrue(enPrompt.contains("app interface language: English"))

        val zhPrompt = replyLanguageInstruction("zh-CN")
        assertTrue(zhPrompt.contains("正文使用用户本轮消息所用的语言"))
        assertTrue(zhPrompt.contains("应用界面语言：中文"))
    }

    @Test
    fun `界面语言名跟随 locale 而不是写死中文`() {
        assertTrue(replyLanguageInstruction("es").contains("Español"))
        assertTrue(replyLanguageInstruction("ja").contains("日本語"))
        assertTrue(replyLanguageInstruction("zh-CN").contains("中文"))
    }

    @Test
    fun `明确禁止因为提示词或历史消息语言不同就擅自切换`() {
        assertTrue(replyLanguageInstruction("en").contains("do not switch languages"))
        assertTrue(replyLanguageInstruction("zh-CN").contains("不要因为系统提示词、工具说明或历史消息是其他语言就切换"))
    }

    @Test
    fun `开关默认开启`() {
        assertTrue(ChatPreferences().replyInUserLanguageEnabled)
        assertFalse(ChatPreferences(replyInUserLanguageEnabled = false).replyInUserLanguageEnabled)
    }
}
