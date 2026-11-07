package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.MessageMarkers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只有状态记号的助手消息仍然要渲染」这条不变量（走查 §十六）。
 *
 * 真机实测过一次回归：清洗层把 `[已中断]` 当内部标签剥掉后正文变成空串，
 * 下面这条判据随之把整条气泡从列表里滤掉——用户看到的是"助手根本没回话"，
 * 而不是"回了但被打断"。这里把两个方向都钉住。
 */
class ChatMessageRenderabilityTest {
    private fun assistant(content: String) = UIMessage(role = MessageRole.ASSISTANT, content = content)

    @Test
    fun interruptedOnlyMessageStaysRenderable() {
        assertTrue(
            "新记号：正文只剩一句状态时也必须进列表",
            isRenderableChatMessage(assistant(MessageMarkers.INTERRUPTED)),
        )
        assertTrue(
            "存量数据：修复前落库的是中文原句",
            isRenderableChatMessage(assistant("[已中断]")),
        )
    }

    @Test
    fun interruptedMarkerSurvivesTheDisplayPipeline() {
        // 气泡正文取的是清洗后的文本；记号若在这一步被吃掉，界面就只剩一条空壳。
        val shown = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent("[已中断]")
        assertTrue("清洗后仍要留得住，实际=[$shown]", shown.isNotBlank())
    }

    @Test
    fun truncatedOnlyMessageStaysRenderable() {
        assertTrue(isRenderableChatMessage(assistant(MessageMarkers.TRUNCATED)))
    }

    @Test
    fun trulyEmptyAssistantMessageIsStillDropped() {
        assertFalse("空壳助手消息不能渲染成白色长条", isRenderableChatMessage(assistant("")))
        assertFalse(isRenderableChatMessage(assistant("   ")))
    }
}
