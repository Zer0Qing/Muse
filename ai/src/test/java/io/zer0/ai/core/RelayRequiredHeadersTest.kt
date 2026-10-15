package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2: 中转站强制请求头规则测试。
 *
 * 锁死：OpenCode Go 必须自动带上 `x-opencode-session`，值按会话稳定、
 * 用户显式填的同名头优先。
 */
class RelayRequiredHeadersTest {

    @Test
    fun `opencode go requires session header`() {
        val names = RelayRequiredHeaders.requiredHeaderNames("https://opencode.ai/zen/go/v1")
        assertEquals(listOf("x-opencode-session"), names)
    }

    @Test
    fun `subdomain of opencode ai also matches`() {
        val names = RelayRequiredHeaders.requiredHeaderNames("https://go.opencode.ai/v1")
        assertEquals(listOf("x-opencode-session"), names)
    }

    @Test
    fun `unrelated host requires nothing`() {
        assertTrue(RelayRequiredHeaders.requiredHeaderNames("https://api.deepseek.com/v1").isEmpty())
        assertTrue(RelayRequiredHeaders.requiredHeaderNames("").isEmpty())
    }

    @Test
    fun `auto header present for opencode`() {
        val headers = RelayRequiredHeaders.autoHeaders(
            baseUrl = "https://opencode.ai/zen/go/v1",
            sessionId = "sess-abc",
            providerId = "preset_opencode",
        )
        assertEquals(setOf("x-opencode-session"), headers.keys)
        assertTrue(headers.getValue("x-opencode-session").isNotBlank())
    }

    @Test
    fun `same session yields stable value`() {
        val a = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", "sess-1", "p")
        val b = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", "sess-1", "p")
        assertEquals(a, b)
    }

    @Test
    fun `different session yields different value`() {
        val a = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", "sess-1", "p")
        val b = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", "sess-2", "p")
        assertNotEquals(a, b)
    }

    @Test
    fun `null session falls back to provider-stable value`() {
        val a = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", null, "provider-x")
        val b = RelayRequiredHeaders.autoHeaders("https://opencode.ai/zen/go/v1", null, "provider-x")
        assertEquals(a, b)
        assertTrue(a.getValue("x-opencode-session").isNotBlank())
    }

    @Test
    fun `no auto headers for unrelated host`() {
        assertTrue(
            RelayRequiredHeaders.autoHeaders("https://api.deepseek.com/v1", "s", "p").isEmpty(),
        )
    }

    @Test
    fun `user header wins over auto injection`() {
        val auto = mapOf("x-opencode-session" to "auto-value")
        val user = mapOf("x-opencode-session" to "user-value")
        val merged = RelayRequiredHeaders.mergeWithUserHeaders(auto, user)
        assertEquals("user-value", merged.getValue("x-opencode-session"))
    }

    @Test
    fun `user header case-insensitive wins`() {
        val auto = mapOf("x-opencode-session" to "auto-value")
        val user = mapOf("X-OpenCode-Session" to "user-value")
        val merged = RelayRequiredHeaders.mergeWithUserHeaders(auto, user)
        // 用户的大小写写法保留，自动注入不重复添加
        assertEquals("user-value", merged.getValue("X-OpenCode-Session"))
        assertEquals(1, merged.size)
    }

    @Test
    fun `unrelated user headers are preserved alongside auto`() {
        val auto = mapOf("x-opencode-session" to "auto-value")
        val user = mapOf("User-Agent" to "my-agent/1.0")
        val merged = RelayRequiredHeaders.mergeWithUserHeaders(auto, user)
        assertEquals("auto-value", merged.getValue("x-opencode-session"))
        assertEquals("my-agent/1.0", merged.getValue("User-Agent"))
    }

    @Test
    fun `empty auto leaves user headers untouched`() {
        val user = mapOf("User-Agent" to "x")
        assertEquals(user, RelayRequiredHeaders.mergeWithUserHeaders(emptyMap(), user))
    }
}
