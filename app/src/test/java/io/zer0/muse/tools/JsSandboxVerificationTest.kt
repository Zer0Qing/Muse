package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙盒自检的两个环节：脚本必须交出返回值，解析必须能分辨“没返回值”和“内容不对”。
 *
 * 这两件事之前同时错位：脚本没有 return，回调拿到 null，
 * 解析又把 null 当成“格式不对”，于是一个环境问题一直被报成解析失败。
 */
class JsSandboxVerificationTest {

    @Test
    fun `自检脚本必须显式返回结果`() {
        // evaluateJavascript 取的是整个表达式的值；IIFE 里最后一句写成语句而非 return 时，
        // 回调会收到 "null"，沙盒随后被判为不可用。
        assertTrue(
            "VERIFY_JS 必须以 return 交出 JSON 字符串，否则 evaluateJavascript 只会拿到 null",
            JsSandbox.VERIFY_JS.contains("return JSON.stringify"),
        )
    }

    @Test
    fun `没有返回值时报未返回结果`() {
        val (ok, issues) = JsSandbox.parseVerificationResult("null")
        assertFalse(ok)
        assertEquals(listOf("自检未返回结果"), issues)
    }

    @Test
    fun `空回调同样报未返回结果`() {
        val (ok, issues) = JsSandbox.parseVerificationResult(null)
        assertFalse(ok)
        assertEquals(listOf("自检未返回结果"), issues)
    }

    @Test
    fun `双重编码的自检结果能解析通过`() {
        val payload = """{"verified":true,"issues":[]}"""
        val (ok, issues) = JsSandbox.parseVerificationResult(encode(payload))
        assertTrue(ok)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `未通过时带出具体原因`() {
        val payload = """{"verified":false,"issues":["window_open_not_blocked"]}"""
        val (ok, issues) = JsSandbox.parseVerificationResult(encode(payload))
        assertFalse(ok)
        assertEquals(listOf("window_open_not_blocked"), issues)
    }

    /** evaluateJavascript 回调拿到的是 JSON 编码后的字符串，这里复现那层编码。 */
    private fun encode(json: String): String = "\"" + json.replace("\"", "\\\"") + "\""

    @Test
    fun `返回非对象时报解析失败而不是静默通过`() {
        val (ok, issues) = JsSandbox.parseVerificationResult("\"just a string\"")
        assertFalse(ok)
        assertTrue(issues.single().startsWith("自检结果解析失败"))
    }
}
