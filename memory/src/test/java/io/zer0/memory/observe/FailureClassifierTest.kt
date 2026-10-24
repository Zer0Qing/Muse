package io.zer0.memory.observe

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** D3-P1: 失败分类器测试 — 钉住"能不能重试"的唯一判定。 */
class FailureClassifierTest {

    @Test
    fun `null error defaults to retryable`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(null))
    }

    @Test
    fun `cancellation is fatal`() {
        assertEquals(FailureKind.FATAL, FailureClassifier.classify(CancellationException("cancelled")))
    }

    @Test
    fun `vm error is fatal`() {
        assertEquals(FailureKind.FATAL, FailureClassifier.classify(OutOfMemoryError("oom")))
    }

    @Test
    fun `socket timeout and io are retryable`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(SocketTimeoutException("timeout")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(IOException("connection reset")))
    }

    @Test
    fun `http rate limit and 5xx are retryable`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 429 Too Many Requests")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 503 Unavailable")))
    }

    @Test
    fun `http 4xx auth and validation are permanent`() {
        assertEquals(FailureKind.PERMANENT, FailureClassifier.classify(RuntimeException("HTTP 401 unauthorized")))
        assertEquals(FailureKind.PERMANENT, FailureClassifier.classify(RuntimeException("HTTP 400 bad request")))
    }

    // ── v2.6.9: 402 归类为终态错误 ────────────────────────────────────────

    @Test
    fun `http 402 quota exhausted is permanent`() {
        assertEquals(
            FailureKind.PERMANENT,
            FailureClassifier.classify(RuntimeException("HTTP 402 [insufficient quota]: 余额不足")),
        )
    }

    @Test
    fun `insufficient quota variants are permanent`() {
        listOf(
            "insufficient quota",
            "type=insufficient_quota",
            "quota exceeded",
            "You exceeded your current quota",
            "余额不足",
            "额度不足",
        ).forEach { message ->
            assertEquals(
                "应归为 PERMANENT: $message",
                FailureKind.PERMANENT,
                FailureClassifier.classify(RuntimeException(message)),
            )
        }
    }

    @Test
    fun `quota message mixed with rate limit wording stays permanent`() {
        // 上游常把额度不足包成带限流措辞的消息。若先匹配 RETRYABLE_PATTERNS,
        // 会因为 "rate limit" 判成可重试,继续吃 402。
        assertEquals(
            FailureKind.PERMANENT,
            FailureClassifier.classify(
                RuntimeException("HTTP 402 rate limit exceeded: insufficient_quota, not asked again before 02:49"),
            ),
        )
    }

    @Test
    fun `isTerminalQuotaOrAuth only flags money and credential failures`() {
        assertTrue(FailureClassifier.isTerminalQuotaOrAuth(RuntimeException("HTTP 402 insufficient quota")))
        assertTrue(FailureClassifier.isTerminalQuotaOrAuth(RuntimeException("HTTP 401 unauthorized")))
        assertTrue(FailureClassifier.isTerminalQuotaOrAuth(RuntimeException("余额不足")))
        // 其余错误保持原补跑行为
        assertFalse(FailureClassifier.isTerminalQuotaOrAuth(RuntimeException("HTTP 503 Unavailable")))
        assertFalse(FailureClassifier.isTerminalQuotaOrAuth(RuntimeException("HTTP 429 Too Many Requests")))
        assertFalse(FailureClassifier.isTerminalQuotaOrAuth(IOException("connection reset")))
        assertFalse(FailureClassifier.isTerminalQuotaOrAuth(null))
    }

    @Test
    fun `retryable signals are not broken by the 402 rule`() {
        // 本次只加终态判定,不能把原本可重试的改坏。
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 429 Too Many Requests")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 500 Internal Server Error")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 502 Bad Gateway")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("HTTP 503 Unavailable")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(IOException("connection reset")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(SocketTimeoutException("timeout")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("something odd")))
    }

    @Test
    fun `parse failures are permanent`() {
        assertEquals(FailureKind.PERMANENT, FailureClassifier.classify(IllegalStateException("json 解析失败")))
        assertEquals(FailureKind.PERMANENT, FailureClassifier.classify(IllegalArgumentException("参数非法")))
    }

    @Test
    fun `unknown error defaults to retryable`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("something odd")))
    }
}
