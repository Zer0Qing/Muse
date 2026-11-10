package io.zer0.muse.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发言指南：默认档必须**一个字都不加**。
 *
 * 这是这个功能最容易出事的地方 —— 一旦默认档多出空行或分隔符，
 * 所有用户的系统提示尾端都会变，而他们什么都没设置过。
 * 参考项目里同一段约束也明确「只约束最终发送正文」，所以文案里必须写清边界。
 */
class SpeechGuidePolicyTest {

    @Test
    fun `默认档原样返回 逐字不改`() {
        val base = "你是一个温柔的角色。"
        assertEquals(base, SpeechGuidePolicy.inject(base, SpeechGuideStyle.OFF))
        assertEquals("", SpeechGuidePolicy.inject("", SpeechGuideStyle.OFF))
    }

    @Test
    fun `口语档在既有提示词后追加 且带分隔`() {
        val base = "你是一个温柔的角色。"
        val out = SpeechGuidePolicy.inject(base, SpeechGuideStyle.CONVERSATIONAL)
        assertTrue("原文必须完整保留", out.startsWith(base))
        assertTrue("要加分隔符，不能直接粘在上一句后面", out.length > base.length)
        assertTrue(out.contains("发言指南"))
        assertTrue(out.contains("口语化"))
    }

    @Test
    fun `文案要写清边界 只约束最终正文`() {
        val text = SpeechGuidePolicy.instruction(SpeechGuideStyle.CONVERSATIONAL)
        assertTrue("必须说明只约束最终正文: $text", text.contains("只约束最终发送的正文"))
        assertTrue("必须说明不约束思考过程: $text", text.contains("不约束思考过程"))
    }

    @Test
    fun `长内容允许结构化 不是一刀切口语`() {
        val text = SpeechGuidePolicy.instruction(SpeechGuideStyle.CONVERSATIONAL)
        assertTrue("长内容/清单/代码要允许结构化表达: $text", text.contains("结构化表达"))
    }

    @Test
    fun `坏值回落默认档而不是抛`() {
        assertEquals(SpeechGuideStyle.OFF, SpeechGuideStyle.fromName(null))
        assertEquals(SpeechGuideStyle.OFF, SpeechGuideStyle.fromName("  "))
        assertEquals(SpeechGuideStyle.OFF, SpeechGuideStyle.fromName("不存在"))
        assertEquals(SpeechGuideStyle.CONVERSATIONAL, SpeechGuideStyle.fromName("conversational"))
    }

    @Test
    fun `空白基础提示词时只给政策段 不产生前导分隔符`() {
        val out = SpeechGuidePolicy.inject("", SpeechGuideStyle.CONVERSATIONAL)
        assertTrue("不该以分隔符开头: $out", !out.startsWith("\n"))
    }
}
