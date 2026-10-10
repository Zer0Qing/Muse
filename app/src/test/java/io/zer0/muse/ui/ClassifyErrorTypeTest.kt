package io.zer0.muse.ui

import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B4 错误恢复补测：classifyErrorType 全分支。
 *
 * 优先级语义（函数注释）：
 *  1. throwable 是 ProviderException → 直接取 providerError（类型路径）
 *  2. 否则字符串推断（inferFromMessage，Deprecated 兼容路径）
 *  3. 都不命中 → UNKNOWN
 */
class ClassifyErrorTypeTest {

    private fun classify(message: String, throwable: Throwable? = null): ChatErrorType =
        ChatViewModel.classifyErrorTypeStatic(message, throwable)

    // ── 类型路径 ──

    @Test
    fun `provider exception network maps to NETWORK`() {
        val t = ProviderException(ProviderError.Network(displayMessage = "conn reset"))
        assertEquals(ChatErrorType.NETWORK, classify("ignored", t))
    }

    @Test
    fun `provider exception rate limit maps to RATE_LIMIT`() {
        val t = ProviderException(ProviderError.RateLimit(displayMessage = "429 slow down"))
        assertEquals(ChatErrorType.RATE_LIMIT, classify("ignored", t))
    }

    @Test
    fun `provider exception server error maps to NETWORK`() {
        // v1.0.1 (P4): 5xx 纳入 NETWORK(可重试)
        val t = ProviderException(ProviderError.ServerError(httpCode = 500, displayMessage = "boom"))
        assertEquals(ChatErrorType.NETWORK, classify("ignored", t))
    }

    @Test
    fun `provider exception auth error maps to API_KEY`() {
        val t = ProviderException(ProviderError.AuthError(displayMessage = "bad key"))
        assertEquals(ChatErrorType.API_KEY, classify("ignored", t))
    }

    @Test
    fun `provider exception invalid request maps to UNKNOWN`() {
        val t = ProviderException(ProviderError.InvalidRequest(displayMessage = "bad param"))
        assertEquals(ChatErrorType.UNKNOWN, classify("ignored", t))
    }

    @Test
    fun `provider exception cancelled maps to UNKNOWN`() {
        val t = ProviderException(ProviderError.Cancelled(displayMessage = "user cancel"))
        assertEquals(ChatErrorType.UNKNOWN, classify("ignored", t))
    }

    // ── 字符串推断路径（无 ProviderException）──

    @Test
    fun `timeout message maps to NETWORK`() {
        assertEquals(ChatErrorType.NETWORK, classify("request timeout after 300s"))
    }

    @Test
    fun `unauthorized message maps to API_KEY`() {
        assertEquals(ChatErrorType.API_KEY, classify("401 unauthorized"))
    }

    @Test
    fun `rate limit message maps to RATE_LIMIT`() {
        assertEquals(ChatErrorType.RATE_LIMIT, classify("429 rate limited"))
    }

    @Test
    fun `server error message maps to NETWORK`() {
        assertEquals(ChatErrorType.NETWORK, classify("500 internal error"))
    }

    @Test
    fun `unmatched message falls back to UNKNOWN`() {
        assertEquals(ChatErrorType.UNKNOWN, classify("something odd happened"))
    }

    @Test
    fun `blank message with no throwable is UNKNOWN`() {
        assertEquals(ChatErrorType.UNKNOWN, classify(""))
    }
}
