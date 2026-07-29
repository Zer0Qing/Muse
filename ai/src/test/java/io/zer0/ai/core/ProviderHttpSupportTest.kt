package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderHttpSupport companion object 静态方法单元测试(P8)。
 *
 * 覆盖 [ProviderHttpSupport.isRetryableHttpCode] / [ProviderHttpSupport.calculateRetryDelay] /
 * [ProviderHttpSupport.classifyHttpCode] / [ProviderHttpSupport.buildHttpErrorMessage] 等
 * P5-D 抽取的共享逻辑,确保三家 Provider(OpenAI/Anthropic/Gemini)的重试/退避/分类行为一致。
 */
class ProviderHttpSupportTest {

    // ── isRetryableHttpCode ────────────────────────────────────────────────

    @Test
    fun `isRetryableHttpCode returns true for 429 rate limit`() {
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(429))
    }

    @Test
    fun `isRetryableHttpCode returns true for 408 request timeout`() {
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(408))
    }

    @Test
    fun `isRetryableHttpCode returns true for 503 service unavailable`() {
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(503))
    }

    @Test
    fun `isRetryableHttpCode returns true for 529 anthropic overloaded`() {
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(529))
    }

    @Test
    fun `isRetryableHttpCode returns true for all 5xx server errors`() {
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(500))
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(501))
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(502))
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(504))
        assertTrue(ProviderHttpSupport.isRetryableHttpCode(599))
    }

    @Test
    fun `isRetryableHttpCode returns false for 400 bad request`() {
        assertFalse(ProviderHttpSupport.isRetryableHttpCode(400))
    }

    @Test
    fun `isRetryableHttpCode returns false for 401 unauthorized`() {
        assertFalse(ProviderHttpSupport.isRetryableHttpCode(401))
    }

    @Test
    fun `isRetryableHttpCode returns false for 403 forbidden`() {
        assertFalse(ProviderHttpSupport.isRetryableHttpCode(403))
    }

    @Test
    fun `isRetryableHttpCode returns false for 404 not found`() {
        assertFalse(ProviderHttpSupport.isRetryableHttpCode(404))
    }

    // ── calculateRetryDelay ────────────────────────────────────────────────

    @Test
    fun `calculateRetryDelay uses Retry-After header when present`() {
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 1, retryAfterHeader = "30")
        assertEquals(30_000L, delay)
    }

    @Test
    fun `calculateRetryDelay uses exponential backoff when Retry-After is null`() {
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 1, retryAfterHeader = null)
        // 1s base + 0~499ms jitter
        assertTrue("delay should be in [1000, 1500), got $delay", delay in 1000L..1499L)
    }

    @Test
    fun `calculateRetryDelay doubles base delay for retry 2`() {
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 2, retryAfterHeader = null)
        // 2s base + 0~499ms jitter
        assertTrue("delay should be in [2000, 2500), got $delay", delay in 2000L..2499L)
    }

    @Test
    fun `calculateRetryDelay doubles base delay for retry 3`() {
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 3, retryAfterHeader = null)
        // 4s base + 0~499ms jitter
        assertTrue("delay should be in [4000, 4500), got $delay", delay in 4000L..4499L)
    }

    @Test
    fun `calculateRetryDelay ignores non-numeric Retry-After header`() {
        // 非数字 header 视为 null,走指数退避
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 1, retryAfterHeader = "abc")
        assertTrue("delay should fall back to backoff, got $delay", delay in 1000L..1499L)
    }

    @Test
    fun `calculateRetryDelay ignores empty Retry-After header`() {
        val delay = ProviderHttpSupport.calculateRetryDelay(retryCount = 1, retryAfterHeader = "")
        assertTrue("delay should fall back to backoff, got $delay", delay in 1000L..1499L)
    }

    // ── classifyHttpCode ───────────────────────────────────────────────────

    @Test
    fun `classifyHttpCode returns authentication failed for 401`() {
        assertEquals("authentication failed", ProviderHttpSupport.classifyHttpCode(401))
    }

    @Test
    fun `classifyHttpCode returns permission denied for 403`() {
        assertEquals("permission denied", ProviderHttpSupport.classifyHttpCode(403))
    }

    @Test
    fun `classifyHttpCode returns request timeout for 408`() {
        assertEquals("request timeout", ProviderHttpSupport.classifyHttpCode(408))
    }

    @Test
    fun `classifyHttpCode returns rate limited for 429`() {
        assertEquals("rate limited", ProviderHttpSupport.classifyHttpCode(429))
    }

    @Test
    fun `classifyHttpCode returns server error for 5xx`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(500))
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(503))
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(599))
    }

    @Test
    fun `classifyHttpCode returns null for business errors`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(400))
        assertNull(ProviderHttpSupport.classifyHttpCode(404))
    }

    // ── buildHttpErrorMessage ──────────────────────────────────────────────

    @Test
    fun `buildHttpErrorMessage includes prefix code and category`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage(prefix = "OpenAI", code = 429, body = "")
        assertTrue(msg.contains("OpenAI"))
        assertTrue(msg.contains("429"))
        assertTrue(msg.contains("rate limited"))
    }

    @Test
    fun `buildHttpErrorMessage includes truncated body when present`() {
        val longBody = "x".repeat(300)
        val msg = ProviderHttpSupport.buildHttpErrorMessage(prefix = "Test", code = 500, body = longBody)
        // body 截断到 200 字符
        assertTrue("body should be truncated, length=${msg.length}", msg.length < 300)
        assertTrue(msg.contains("server error"))
    }

    @Test
    fun `buildHttpErrorMessage omits body section when blank`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage(prefix = "Test", code = 401, body = "")
        assertFalse(msg.contains(": "))
    }
}
