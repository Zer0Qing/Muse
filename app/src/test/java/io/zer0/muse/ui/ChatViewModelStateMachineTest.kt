package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-06: ChatViewModel 发送/继续/重生成状态机纯函数测试。
 * 多版本切换由 ConversationTreeTest 覆盖(selectUserVariant/selectAssistantVariant)。
 */
class ChatViewModelStateMachineTest {

    @Test
    fun `buildSendText keeps raw text when no documents`() {
        assertEquals("hello", buildSendText("hello", emptyList()))
        assertEquals("", buildSendText("", emptyList()))
    }

    @Test
    fun `buildSendText uses document text when input blank`() {
        assertEquals("doc1\n\n---\n\ndoc2", buildSendText("", listOf("doc1", "doc2")))
    }

    @Test
    fun `buildSendText combines documents and user text`() {
        assertEquals("doc\n\n---\n\nhi", buildSendText("hi", listOf("doc")))
    }

    @Test
    fun `canContinueGeneration allows interrupted assistant message`() {
        val last = UIMessage(role = MessageRole.ASSISTANT, content = "partial\n\n[已中断]")
        assertTrue(canContinueGeneration(isStreaming = false, lastMessage = last))
    }

    @Test
    fun `canContinueGeneration blocks streaming and non assistant`() {
        val interrupted = UIMessage(role = MessageRole.ASSISTANT, content = "x[已中断]")
        assertFalse(canContinueGeneration(isStreaming = true, lastMessage = interrupted))
        assertFalse(canContinueGeneration(isStreaming = false, lastMessage = null))
        assertFalse(
            canContinueGeneration(
                isStreaming = false,
                lastMessage = UIMessage(role = MessageRole.USER, content = "x[已中断]"),
            ),
        )
        assertFalse(
            canContinueGeneration(
                isStreaming = false,
                lastMessage = UIMessage(role = MessageRole.ASSISTANT, content = "x"),
            ),
        )
    }

    @Test
    fun `resumeFromInterrupted strips both marker forms`() {
        assertEquals("partial", resumeFromInterrupted("partial\n\n[已中断]"))
        assertEquals("partial", resumeFromInterrupted("partial[已中断]"))
        assertEquals("plain", resumeFromInterrupted("plain"))
    }

    @Test
    fun `canRegenerate requires not streaming session and selected variant`() {
        assertTrue(canRegenerate(isStreaming = false, hasSession = true, hasSelectedUserVariant = true))
        assertFalse(canRegenerate(isStreaming = true, hasSession = true, hasSelectedUserVariant = true))
        assertFalse(canRegenerate(isStreaming = false, hasSession = false, hasSelectedUserVariant = true))
        assertFalse(canRegenerate(isStreaming = false, hasSession = true, hasSelectedUserVariant = false))
    }
}
