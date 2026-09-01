package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.hook.HookRegistry
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mood 解析回归测试：验证流式收尾使用的同一更新入口会把 Mood 从正文/推理通道
 * 分离出来，保证后续 MessageBubble 有数据可渲染，而不是只在 prompt 里要求模型输出。
 */
class ChatStreamMoodTest {

    @Test
    fun `mood is persisted separately and removed from visible content`() {
        val messageId = Uuid.random()
        val accessor = MutableMessagesAccessor(
            listOf(UIMessage(id = messageId, role = MessageRole.ASSISTANT, content = "")),
        )
        val coordinator = coordinator(accessor)

        coordinator.updateAssistant(
            id = messageId,
            content = """
                <mood>
                Vibe: calm
                Sparks: a small idea
                Reflections: keep the answer clear
                Will: help the user
                </mood>
                这是正文
            """.trimIndent(),
            reasoning = "<think>internal reasoning</think>",
            isStreaming = false,
        )

        val updated = accessor.messages.single()
        assertEquals("这是正文", updated.content.trim())
        assertNotNull(updated.mood)
        assertTrue(updated.mood!!.contains("Vibe: calm"))
        assertFalse(updated.content.contains("<mood>", ignoreCase = true))
        assertFalse(updated.content.contains("</mood>", ignoreCase = true))
        assertEquals("internal reasoning", updated.reasoning?.trim())
    }

    @Test
    fun `mood in reasoning channel is recovered when content has no mood`() {
        val messageId = Uuid.random()
        val accessor = MutableMessagesAccessor(
            listOf(UIMessage(id = messageId, role = MessageRole.ASSISTANT, content = "")),
        )
        val coordinator = coordinator(accessor)

        coordinator.updateAssistant(
            id = messageId,
            content = "最终答案",
            reasoning = "<mood>Vibe: thoughtful</mood><think>分析过程</think>",
            isStreaming = false,
        )

        val updated = accessor.messages.single()
        assertEquals("最终答案", updated.content)
        assertEquals("Vibe: thoughtful", updated.mood?.trim())
        assertEquals("分析过程", updated.reasoning?.trim())
    }

    private fun coordinator(accessor: MutableMessagesAccessor): ChatStreamCoordinator =
        ChatStreamCoordinator(
            accessor = accessor,
            sessionRepository = mockk(relaxed = true),
            memoryTicker = mockk(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            appContext = mockk(relaxed = true),
            notificationManager = mockk<MuseNotificationManager>(relaxed = true),
            assistantRepository = mockk<AssistantRepository>(relaxed = true),
            visionBridge = mockk<VisionBridge>(relaxed = true),
            toolRegistry = mockk<ToolRegistry>(relaxed = true),
            skillRepository = mockk<SkillRepository>(relaxed = true),
            idListJson = Json,
            lorebookRepository = mockk<LorebookRepository>(relaxed = true),
            promptInjectionRepository = mockk<PromptInjectionRepository>(relaxed = true),
            transformerPipeline = mockk<TransformerPipeline>(relaxed = true),
            hookRegistry = mockk<HookRegistry>(relaxed = true),
        )

    private class MutableMessagesAccessor(
        initial: List<UIMessage>,
    ) : ChatStateAccessor {
        var messages: List<UIMessage> = initial

        override val snapshot: io.zer0.muse.ui.ChatUiState = io.zer0.muse.ui.ChatUiState()
        override val messagesSnapshot: List<UIMessage> get() = messages
        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        override fun update(transform: (io.zer0.muse.ui.ChatUiState) -> io.zer0.muse.ui.ChatUiState) = Unit

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) {
            messages = transform(messages)
        }
    }
}
