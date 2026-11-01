package io.zer0.muse.transformer

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只有 token 预算超了、消息条数没超时，压缩也必须触发。
 *
 * 这条曾经被一次「优化」写坏过：把预算判断改成 `overCount && isOver(...)` 的短路形式，
 * 于是「条数没超 → 不算预算 → 判为不超预算 → 直接返回原历史」，压缩永远不触发。
 * 而 `compress_char_budget` 这个 key 在 v2.3.2 就已按 token 预算解释，正是为了
 * 覆盖「条数不多但单条很长」的会话（长文档、卡片源码、日志粘贴）。
 *
 * 这里用极小阈值构造「条数少但必然超预算」的输入，锁住那条分支。
 */
class ContextCompressTokenBudgetTriggerTest {

    @Test
    fun `token budget overrun triggers compression even when message count is under threshold`() = runTest {
        val compressor = mockk<ConversationCompressor>()
        coEvery { compressor.compress(any(), any()) } returns null
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = compressor,
        )
        // 每条都很长 → token 很大；但条数只有 4，远低于阈值 999。
        val messages = List(4) { index ->
            UIMessage(
                role = if (index == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "很长的历史内容用于撑爆 token 预算 ".repeat(200),
            )
        }
        val totalTokens = TokenEstimator.estimate(messages)
        assertTrue("前提：这批消息的 token 必须远超预算", totalTokens > 100)

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "session-budget",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 999,
                    "compress_keep_recent" to 1,
                    "compress_char_budget" to 100,
                    // 摘要模型失败时仍需产出可见的截断标记，避免用例依赖真实模型
                    "compress_force_fallback" to true,
                ),
            ),
        )

        assertTrue(
            "条数没超但 token 超预算时必须压缩，实际结果条数=${result.size}",
            result.size != messages.size,
        )
    }

    @Test
    fun `neither over count nor over budget keeps history untouched`() = runTest {
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = mockk(relaxed = true),
        )
        val messages = List(3) { UIMessage(role = MessageRole.USER, content = "短消息 $it") }

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "session-idle",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 999,
                    "compress_keep_recent" to 1,
                    "compress_char_budget" to 100_000,
                ),
            ),
        )

        assertTrue("两个条件都没超时不得改动历史", result.size == messages.size)
    }
}
