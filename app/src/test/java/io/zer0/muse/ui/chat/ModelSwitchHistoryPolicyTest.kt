package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换模型之后，历史里只属于旧模型的附加结构要被剥掉，对话内容要保留。
 *
 * 这些结构（思考签名、加密推理）在换模型后原样回传会被拒或行为异常，
 * 用户看到的是「一换模型就发不出去」。正文永远不该因为换模型而丢。
 */
class ModelSwitchHistoryPolicyTest {

    private fun msg(
        role: MessageRole,
        content: String,
        modelId: String? = null,
        reasoning: String? = null,
        signature: String? = null,
        encrypted: String? = null,
    ) = UIMessage(
        role = role,
        content = content,
        modelId = modelId,
        reasoning = reasoning,
        thinkingSignature = signature,
        thinkingEncryptedContent = encrypted,
    )

    @Test
    fun `同一模型时什么都不动`() {
        val history = listOf(
            msg(MessageRole.ASSISTANT, "你好", modelId = "m1", reasoning = "想一想", signature = "sig"),
        )
        val out = ModelSwitchHistoryPolicy.sanitize(history, "m1")
        assertEquals("同一个模型不该被清洗", history, out)
    }

    @Test
    fun `换模型后剥掉旧模型的思考结构与签名 但保留正文`() {
        val history = listOf(
            msg(
                MessageRole.ASSISTANT,
                "这是正文，不能丢",
                modelId = "old-model",
                reasoning = "旧模型的思考",
                signature = "old-sig",
                encrypted = "old-enc",
            ),
        )
        val out = ModelSwitchHistoryPolicy.sanitize(history, "new-model")
        assertEquals("正文必须保留", "这是正文，不能丢", out.single().content)
        assertNull("思考过程要剥掉", out.single().reasoning)
        assertNull("思考签名要剥掉", out.single().thinkingSignature)
        assertNull("加密推理要剥掉", out.single().thinkingEncryptedContent)
    }

    @Test
    fun `没有模型标记的历史原样保留`() {
        val history = listOf(msg(MessageRole.USER, "问题"), msg(MessageRole.ASSISTANT, "回答"))
        assertEquals(history, ModelSwitchHistoryPolicy.sanitize(history, "new-model"))
    }

    @Test
    fun `没指定当前模型时不做任何清洗`() {
        val history = listOf(msg(MessageRole.ASSISTANT, "x", modelId = "old", reasoning = "r"))
        assertEquals(history, ModelSwitchHistoryPolicy.sanitize(history, null))
        assertEquals(history, ModelSwitchHistoryPolicy.sanitize(history, ""))
    }

    @Test
    fun `能识别出来自别的模型的工具轮次`() {
        val history = listOf(
            msg(MessageRole.TOOL, "工具结果", modelId = "old-model"),
            msg(MessageRole.ASSISTANT, "回答", modelId = "new-model"),
        )
        assertTrue(ModelSwitchHistoryPolicy.hasForeignToolTurns(history, "new-model"))
        assertFalse("同一模型的工具轮次不算外来", ModelSwitchHistoryPolicy.hasForeignToolTurns(history, "old-model"))
    }

    @Test
    fun `没有工具轮次时返回 false`() {
        val history = listOf(msg(MessageRole.USER, "问题", modelId = "old-model"))
        assertFalse(ModelSwitchHistoryPolicy.hasForeignToolTurns(history, "new-model"))
    }
}
