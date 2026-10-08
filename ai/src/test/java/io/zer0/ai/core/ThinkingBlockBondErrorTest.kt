package io.zer0.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.3 (P2-3): 「思考块绑定到不同会话」类 400 的识别测试。
 *
 * 只应精确命中这一种诊断，其他 400（参数/鉴权/模型名）不应误判。
 */
class ThinkingBlockBondErrorTest {

    @Test
    fun `matches claude bound to different conversation`() {
        val body =
            """{"type":"error","error":{"message":"Thinking block is bound to a different conversation"}}"""
        assertTrue(ProviderError.isThinkingBlockBondError(body))
    }

    @Test
    fun `matches invalid signature`() {
        assertTrue(ProviderError.isThinkingBlockBondError("""{"error":{"message":"Invalid signature in thinking block"}}"""))
    }

    @Test
    fun `matches gemini thought signature`() {
        assertTrue(ProviderError.isThinkingBlockBondError("""{"error":{"message":"Thought_signature is invalid"}}"""))
    }

    @Test
    fun `does not match plain 400 param error`() {
        assertFalse(ProviderError.isThinkingBlockBondError("""{"error":{"message":"max_tokens is too large"}}"""))
    }

    @Test
    fun `does not match auth error`() {
        assertFalse(ProviderError.isThinkingBlockBondError("""{"error":{"message":"invalid api key"}}"""))
    }

    @Test
    fun `null and blank are not matches`() {
        assertFalse(ProviderError.isThinkingBlockBondError(null))
        assertFalse(ProviderError.isThinkingBlockBondError(""))
    }
}
