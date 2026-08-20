package io.zer0.muse.data

import io.zer0.memory.fact.DedupVerdict
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.ai.core.Model
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v12: LlmFactDedupJudge 解析与降级行为测试(不真调 LLM)。 */
class LlmFactDedupJudgeTest {

    private class StubLlm(private val response: String) : MemoryLlmClient {
        var lastSystem: String? = null
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            lastSystem = systemPrompt
            return response
        }
    }

    @Test
    fun `parses clean json`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("""{"same": true, "confidence": 0.95, "reason": "同一件事"}"""))
        val v = judge.judge("a", "b", null, null)
        assertTrue(v.same)
        assertTrue(v.highConfidenceSame)
    }

    @Test
    fun `parses json inside markdown fence`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("```json\n{\"same\": false, \"confidence\": 0.8, \"reason\": \"不同\"}\n```"))
        val v = judge.judge("a", "b", null, null)
        assertFalse(v.same)
    }

    @Test
    fun `parses json with extra text around`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("好的,分析如下:\n{\"same\": true, \"confidence\": 0.9, \"reason\": \"语义相同\"}\n完毕"))
        val v = judge.judge("a", "b", null, null)
        assertTrue(v.same)
    }

    @Test
    fun `llm exception degrades to not same`() = runTest {
        val llm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = throw RuntimeException("timeout")
        }
        val judge = LlmFactDedupJudge(llm)
        val v = judge.judge("a", "b", null, null)
        assertFalse("异常应降级为不同一", v.same)
    }

    @Test
    fun `garbage output degrades to not same`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("这是模型在聊天而不是输出 JSON"))
        val v = judge.judge("a", "b", null, null)
        assertFalse("无法解析应降级为不同一", v.same)
        assertEquals(DedupVerdict.NOT_SAME, v)
    }

    @Test
    fun `prompt mentions redacted placeholder semantics`() = runTest {
        val llm = StubLlm("""{"same": true, "confidence": 0.9, "reason": "ok"}""")
        val judge = LlmFactDedupJudge(llm)
        judge.judge("[REDACTED]喜欢摄影", "张三喜欢摄影", "张三", "张三")
        assertTrue("system prompt 应包含 [REDACTED] 语义说明", llm.lastSystem!!.contains("[REDACTED]"))
        assertTrue("system prompt 应包含实体制导", llm.lastSystem!!.contains("实体"))
    }
}
