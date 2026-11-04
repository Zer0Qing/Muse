package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ProviderErrorTest {
    @Test
    fun payloadTooLargeIsNonRetryableInvalidRequest() {
        val error = ProviderError.from(413, "request entity too large")

        assertEquals(ProviderError.InvalidRequest::class, error::class)
        assertFalse(error.isRetryable)
        assertEquals(413, error.httpCode)
    }

    @Test
    fun retryAfterParserRejectsNegativeAndMalformedValues() {
        assertEquals(120, ProviderError.parseRetryAfter("120"))
        assertNull(ProviderError.parseRetryAfter("-1"))
        assertNull(ProviderError.parseRetryAfter("not-a-duration"))
        assertNull(ProviderError.parseRetryAfter(null))
    }

    @Test
    fun retryAfterParserAcceptsHttpDate() {
        val target = Instant.now().plus(90, ChronoUnit.SECONDS)
            .atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val parsed = ProviderError.parseRetryAfter(target)

        assertTrue("HTTP-date should resolve to a non-negative delay", parsed != null && parsed in 0..91)
    }

    @Test
    fun displayMessageRedactsProviderBodySecrets() {
        val error = ProviderError.from(
            400,
            """{"error":{"message":"invalid api key sk-live-super-secret","type":"invalid_request_error"}}""",
        )

        assertFalse(error.displayMessage.contains("sk-live-super-secret"))
        assertTrue(error.displayMessage.contains("HTTP 400"))
        assertTrue(error.displayMessage.contains("[REDACTED]"))
    }

    @Test
    fun `没有可展示的细节时用中文兜底句而不是英文`() {
        // 这些句子会原样进界面（如「视觉分析失败: …」）。以前留的是
        // "network error" / "unknown error" —— 中文用户看到一句没头没尾的英文，
        // 而本模块别处的兜底早就是中文（"流式响应为空，正在切换请求方式"）。
        val network = ProviderError.from(java.io.IOException(""))
        val unknown = ProviderError.from(code = null, body = "")

        assertEquals("网络错误", network.displayMessage)
        assertEquals("未知错误", unknown.displayMessage)
        assertTrue("不能退化成裸英文", !network.displayMessage.contains("network error"))
    }

    @Test
    fun throwableMessagesDoNotCopyCredentialQueryParameters() {
        val error = ProviderError.from(
            java.io.IOException("https://provider.example/v1?api_key=sk-live-query-secret"),
        )

        assertFalse(error.displayMessage.contains("sk-live-query-secret"))
        assertTrue(error.displayMessage.contains("[REDACTED]"))
    }

    @Test
    fun insufficientQuotaIsClassifiedNotAsInvalidRequest() {
        // v2.6.8 用户反馈回归：402 / insufficient_quota 曾被归到 InvalidRequest，
        // 提示文案引导用户去调参数，而真正要做的是充值。
        val byCode = ProviderError.from(402, "insufficient_quota")
        assertEquals(ProviderError.InsufficientQuota::class, byCode::class)
        assertFalse("余额不足不可重试", byCode.isRetryable)

        // 非 402 但 body 明说额度不足，也要识别
        val byBody = ProviderError.from(400, "{\"error\":{\"type\":\"insufficient_quota\"}}")
        assertEquals(ProviderError.InsufficientQuota::class, byBody::class)

        // 中文中转站写法（用户截图里的真实文案）
        val zh = ProviderError.from(403, "用户额度不足, 剩余额度: -0.08")
        assertEquals(ProviderError.InsufficientQuota::class, zh::class)
    }

    @Test
    fun normalInvalidRequestStillClassifiedAsInvalidRequest() {
        // 反向保护：普通参数错误不能被误判为额度不足
        val error = ProviderError.from(400, "invalid model parameter temperature")
        assertEquals(ProviderError.InvalidRequest::class, error::class)
    }
}
