package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.3 (P2-1): 模型级推理回放声明解析测试。
 *
 * 覆盖「保留思考」新系模型在未知 host / 聚合站下也能声明正确回放策略，
 * 以及未知模型不声明（交由 ProviderCompat / 默认行为）。
 */
class ModelReasoningReplayTest {

    private fun declare(id: String) = ModelReasoningReplayResolver.declare(id)

    @Test
    fun `deepseek思考模型声明reasoning_content且字段必需`() {
        val d = declare("deepseek-v4-flash")
        assertNotNull(d)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
        assertEquals(ReasoningReplayPolicy.REQUIRE_TOOL_CALL, d.policy)
        assertTrue("DeepSeek 思考模式字段不能省，需发空串占位", d.requiresNonEmptyReasoning)
    }

    @Test
    fun `kimi与glm思考系声明reasoning_content`() {
        listOf("kimi-k2.5", "kimi-k3", "glm-z1-air", "glm-4.7", "glm-5").forEach { id ->
            val d = declare(id)
            assertNotNull("$id 应声明回放", d)
            assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
        }
    }

    @Test
    fun `qwen与doubao容忍缺省故不需要空串占位`() {
        listOf("qwen3-235b", "qwq-32b", "doubao-1.5-thinking-pro").forEach { id ->
            val d = declare(id)
            assertNotNull("$id 应声明回放", d)
            assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
            assertTrue("$id 不需要空串占位", !d.requiresNonEmptyReasoning)
        }
    }

    @Test
    fun `openai响应系与anthropic与gemini声明各自载体`() {
        assertEquals(ReasoningCarrier.REASONING_ITEMS, declare("gpt-5")!!.carrier)
        assertEquals(ReasoningCarrier.THINKING_BLOCKS, declare("claude-sonnet-4-5")!!.carrier)
        assertEquals(ReasoningCarrier.THOUGHT_SIGNATURE, declare("gemini-3-pro")!!.carrier)
    }

    @Test
    fun `聚合站前缀归一化后仍能识别`() {
        assertEquals(ReasoningCarrier.REASONING_CONTENT, declare("openrouter/deepseek/deepseek-v4")!!.carrier)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, declare("siliconflow/kimi-k2.5")!!.carrier)
    }

    @Test
    fun `未知模型不声明`() {
        assertNull(declare("some-random-model"))
        assertNull(declare(""))
        assertNull(ModelReasoningReplayResolver.declare(null))
    }

    @Test
    fun `resolve在未知host下由模型级声明兜底`() {
        // 未知中转站 host，模型是 deepseek 思考系 -> 仍应得到回放契约
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            "https://unknown-relay.example.com/v1",
            "deepseek-v4-flash",
        )
        val contract = compat.reasoningReplayContract
        assertNotNull("未知 host 下应由模型级声明兜底", contract)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, contract!!.carrier)
    }

    @Test
    fun `显式host声明不被模型级兜底覆盖`() {
        // 官方 deepseek host 已在 host 层声明契约，模型级不应改写
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            "https://api.deepseek.com/v1",
            "deepseek-v4-flash",
        )
        assertEquals(ReasoningCarrier.REASONING_CONTENT, compat.reasoningReplayContract!!.carrier)
    }
}
