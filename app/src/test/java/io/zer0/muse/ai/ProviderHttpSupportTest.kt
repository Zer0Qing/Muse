package io.zer0.muse.ai

import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.ProviderHttpSupport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderHttpSupport HTTP 错误分类单元测试。
 *
 * 测试 [ProviderHttpSupport] companion object 中的:
 *  - [ProviderHttpSupport.classifyHttpCode]:HTTP 状态码 → 可读分类字符串
 *  - [ProviderHttpSupport.buildHttpErrorMessage]:构建含分类 + body 摘要的完整错误消息
 *  - [ProviderHttpSupport.readBodySafely]:安全读取 Response body(IOException / IllegalStateException 不抛)
 *  - [ProviderHttpSupport.readBodyCapped]:读取 body 并截断到指定长度
 *
 * 覆盖的状态码映射:
 *  - 401 → "authentication failed"(认证失败)
 *  - 403 → "permission denied"(权限拒绝)
 *  - 408 → "request timeout"(请求超时)
 *  - 429 → "rate limited"(限流)
 *  - 500~599 → "server error"(服务器错误)
 *  - 其他 → null(无需特殊分类)
 */
class ProviderHttpSupportTest {

    // ── classifyHttpCode:HTTP 状态码 → 分类字符串 ────────────────────────

    @Test
    fun `401 映射为认证失败`() {
        assertEquals("authentication failed", ProviderHttpSupport.classifyHttpCode(401))
    }

    @Test
    fun `403 映射为权限拒绝`() {
        assertEquals("permission denied", ProviderHttpSupport.classifyHttpCode(403))
    }

    @Test
    fun `429 映射为限流`() {
        assertEquals("rate limited", ProviderHttpSupport.classifyHttpCode(429))
    }

    @Test
    fun `408 映射为请求超时`() {
        assertEquals("request timeout", ProviderHttpSupport.classifyHttpCode(408))
    }

    @Test
    fun `500 映射为服务器错误`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(500))
    }

    @Test
    fun `502 映射为服务器错误`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(502))
    }

    @Test
    fun `503 映射为服务器错误`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(503))
    }

    @Test
    fun `599 映射为服务器错误(边界上限)`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(599))
    }

    @Test
    fun `499 不属于服务器错误(边界下限外)`() {
        // 4xx 中只有 401/403/408/429 有分类,499 返回 null
        assertNull(ProviderHttpSupport.classifyHttpCode(499))
    }

    @Test
    fun `400 返回 null(无特殊分类)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(400))
    }

    @Test
    fun `404 返回 null(无特殊分类)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(404))
    }

    @Test
    fun `200 返回 null(成功状态码无分类)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(200))
    }

    @Test
    fun `301 返回 null(重定向无分类)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(301))
    }

    // ── buildHttpErrorMessage:构建完整错误消息 ──────────────────────────

    @Test
    fun `buildHttpErrorMessage 包含前缀和状态码`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", 500, "")
        assertTrue("应包含前缀: $msg", msg.contains("OpenAI"))
        assertTrue("应包含状态码: $msg", msg.contains("500"))
        assertTrue("应包含分类: $msg", msg.contains("server error"))
    }

    @Test
    fun `buildHttpErrorMessage 包含 401 认证分类`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Gemini", 401, "")
        assertTrue("应包含 authentication failed: $msg", msg.contains("authentication failed"))
    }

    @Test
    fun `buildHttpErrorMessage 包含 429 限流分类`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Anthropic", 429, "")
        assertTrue("应包含 rate limited: $msg", msg.contains("rate limited"))
    }

    @Test
    fun `buildHttpErrorMessage 包含截断的 body 摘要`() {
        val body = "Invalid API key provided"
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", 401, body)
        assertTrue("应包含 body 内容: $msg", msg.contains(body))
    }

    @Test
    fun `buildHttpErrorMessage 对超长 body 进行截断`() {
        // 构造超长 body(>200 字符),验证被截断到 200
        val longBody = "x".repeat(500)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 500, longBody)
        // 截断后 body 部分不超过 200 字符
        val bodyPart = msg.substringAfter(": ", "")
        assertTrue("body 应被截断到 200 字符以内,实际 ${bodyPart.length}", bodyPart.length <= 200)
    }

    @Test
    fun `buildHttpErrorMessage 无分类时不包含方括号`() {
        // 400 无分类,消息中不应包含 [category]
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 400, "bad request")
        assertTrue("应包含状态码: $msg", msg.contains("400"))
        // 400 无分类,不应有 [xxx]
        assertTrue("无分类时不应包含方括号: $msg", !msg.contains("["))
    }

    @Test
    fun `buildHttpErrorMessage 空 body 时不追加冒号`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 401, "")
        // body 为空时不应有 ": " 分隔符
        assertTrue("空 body 时不应包含冒号分隔: $msg", !msg.endsWith(": "))
    }

    // ── readBodySafely:安全读取 Response body ──────────────────────────

    /** 构造一个含指定 body 的 okhttp Response。 */
    private fun buildResponse(body: String, code: Int = 200): Response {
        return Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("text/plain".toMediaType()))
            .build()
    }

    @Test
    fun `readBodySafely 正常读取 body 内容`() {
        val response = buildResponse("hello world")
        val body = ProviderHttpSupport.readBodySafely(response)
        assertEquals("hello world", body)
    }

    @Test
    fun `readBodySafely body 为 null 时返回空串`() {
        // 构造 body=null 的 Response
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("No Content")
            .build()
        assertEquals("", ProviderHttpSupport.readBodySafely(response))
    }

    @Test
    fun `readBodySafely 读取时抛 IOException 返回空串`() {
        // 用 mockk 构造一个 body.string() 抛 IOException 的 Response
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws java.io.IOException("connection reset")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("IOException 应返回空串", "", ProviderHttpSupport.readBodySafely(response))
    }

    @Test
    fun `readBodySafely 读取时抛 IllegalStateException 返回空串`() {
        // v1.97: EventSourceListener 回调中 body 已被 stripped,读取抛 IllegalStateException
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws IllegalStateException("body is stripped")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("IllegalStateException 应返回空串", "", ProviderHttpSupport.readBodySafely(response))
    }

    // ── readBodyCapped:读取并截断 body ──────────────────────────────────

    @Test
    fun `readBodyCapped 超长 body 被截断到指定长度`() {
        val longBody = "abcdefghij".repeat(100) // 1000 字符
        val response = buildResponse(longBody)
        val capped = ProviderHttpSupport.readBodyCapped(response, maxLen = 50)
        assertEquals("应截断到 50 字符", 50, capped.length)
        assertEquals("abcdefghij".repeat(5), capped)
    }

    @Test
    fun `readBodyCapped 短 body 原样返回`() {
        val shortBody = "short"
        val response = buildResponse(shortBody)
        val capped = ProviderHttpSupport.readBodyCapped(response, maxLen = 500)
        assertEquals(shortBody, capped)
    }

    @Test
    fun `readBodyCapped 默认截断到 ERROR_BODY_MAX_LEN`() {
        // 默认 maxLen = 500(ERROR_BODY_MAX_LEN)
        val longBody = "x".repeat(1000)
        val response = buildResponse(longBody)
        val capped = ProviderHttpSupport.readBodyCapped(response)
        assertEquals("默认应截断到 500 字符", ProviderHttpSupport.ERROR_BODY_MAX_LEN, capped.length)
    }

    @Test
    fun `readBodyCapped 读取异常时返回空串`() {
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws java.io.IOException("read error")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("", ProviderHttpSupport.readBodyCapped(response, maxLen = 100))
    }

    // ── 综合场景:错误码 → 分类 → 错误消息 全链路 ────────────────────────

    @Test
    fun `401 错误全链路 分类到错误消息`() {
        val code = 401
        val category = ProviderHttpSupport.classifyHttpCode(code)
        assertEquals("authentication failed", category)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", code, "invalid api key")
        assertTrue(msg.contains("HTTP 401"))
        assertTrue(msg.contains("authentication failed"))
        assertTrue(msg.contains("invalid api key"))
    }

    @Test
    fun `503 错误全链路 分类到错误消息`() {
        val code = 503
        val category = ProviderHttpSupport.classifyHttpCode(code)
        assertEquals("server error", category)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Gemini", code, "service unavailable")
        assertTrue(msg.contains("HTTP 503"))
        assertTrue(msg.contains("server error"))
        assertTrue(msg.contains("service unavailable"))
    }
}
