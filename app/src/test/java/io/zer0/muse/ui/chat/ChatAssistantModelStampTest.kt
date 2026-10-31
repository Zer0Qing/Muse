package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v2.6.15: assistant 消息的产出模型戳。
 *
 * 思考块回放的同模型守卫（ProviderPayloadNormalizer.stripCrossModelReasoning）只认
 * `UIMessage.modelId`。若生成链路不写这个字段，守卫就永远拿不到来源信息 —— 曾经就是
 * 「字段存在但从不赋值」，导致守卫空转。本测试钉住两个写入点：
 *  - 发送占位消息（ChatGenerationController.enqueueSend）；
 *  - 工具轮重建的 assistant 消息与最终回复（ChatToolLoopRunner.streamRound）。
 */
class ChatAssistantModelStampTest {

    private fun source(relativePath: String): String = listOf(
        Path.of("src/main/java/$relativePath"),
        Path.of("app/src/main/java/$relativePath"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("$relativePath not found")

    // ── 取值规则 ──

    @Test
    fun `round model wins over the stamp already on the message`() {
        val existing = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")
        val round = Model(id = "round-actual-model", providerId = "test")

        assertEquals("round-actual-model", roundAssistantModelId(round, existing))
    }

    @Test
    fun `existing stamp is kept when round model is unknown`() {
        val existing = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")

        assertEquals("enqueue-time-model", roundAssistantModelId(null, existing))
    }

    @Test
    fun `blank values are treated as absent`() {
        val blankRound = Model(id = "   ", providerId = "test")
        val blankStamp = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "")
        val stamped = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")

        assertEquals("enqueue-time-model", roundAssistantModelId(blankRound, stamped))
        assertNull(roundAssistantModelId(blankRound, blankStamp))
        assertNull(roundAssistantModelId(null, null))
    }

    // ── 写入点接线 ──

    @Test
    fun `send placeholder carries the producing model`() {
        val text = source("io/zer0/muse/ui/chat/ChatGenerationController.kt")
        val enqueueBody = text.substringAfter("fun enqueueSend(")
            .substringBefore("suspend fun consumeSendRequest")

        assertTrue("enqueueSend 里找不到发送占位消息", enqueueBody.contains("val assistantMsg ="))
        assertTrue(
            "发送占位消息必须带 modelId，否则同模型守卫拿不到来源",
            enqueueBody.substringAfter("val assistantMsg =")
                .substringBefore("val outboxId")
                .contains("modelId = accessor.snapshot.selectedModelId"),
        )
    }

    @Test
    fun `tool round rebuild and final answer carry the producing model`() {
        val text = source("io/zer0/muse/ui/chat/ChatToolLoopRunner.kt")

        assertTrue(
            "工具轮重建的 assistant 消息必须带 modelId",
            text.contains("modelId = roundAssistantModelId(roundModel, finalizedAssistant)"),
        )
        assertTrue(
            "最终回复也必须带 modelId",
            text.contains("modelId = roundAssistantModelId(roundModel, finalAssistant)"),
        )
    }
}
