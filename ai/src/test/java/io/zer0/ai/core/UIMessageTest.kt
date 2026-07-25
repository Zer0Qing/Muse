package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * UIMessage 单元测试。
 *
 * 覆盖构造、默认参数、role 枚举、id 默认生成、imageBase64List / toolCalls /
 * reasoning / citationUrls 等关键字段读写。
 */
class UIMessageTest {

    @Test
    fun `should populate required fields when constructed with role and content`() {
        val msg = UIMessage(role = MessageRole.USER, content = "hello")
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("hello", msg.content)
    }

    @Test
    fun `should generate id by default when not provided`() {
        val msg = UIMessage(role = MessageRole.USER, content = "")
        assertNotNull(msg.id)
    }

    @Test
    fun `should produce different ids when creating two messages`() {
        val a = UIMessage(role = MessageRole.USER, content = "a")
        val b = UIMessage(role = MessageRole.USER, content = "b")
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `should accept provided id when explicitly set`() {
        val id = Uuid.random()
        val msg = UIMessage(id = id, role = MessageRole.USER, content = "x")
        assertEquals(id, msg.id)
    }

    @Test
    fun `should contain four roles when inspecting MessageRole enum`() {
        val roles = MessageRole.values().toSet()
        assertEquals(4, roles.size)
        assertTrue(MessageRole.SYSTEM in roles)
        assertTrue(MessageRole.USER in roles)
        assertTrue(MessageRole.ASSISTANT in roles)
        assertTrue(MessageRole.TOOL in roles)
    }

    @Test
    fun `should default reasoning to null when not provided`() {
        val msg = UIMessage(role = MessageRole.USER, content = "")
        assertNull(msg.reasoning)
    }

    @Test
    fun `should preserve reasoning when provided`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "thinking steps",
        )
        assertEquals("thinking steps", msg.reasoning)
    }

    @Test
    fun `should default imageBase64List to empty list when not provided`() {
        val msg = UIMessage(role = MessageRole.USER, content = "")
        assertTrue(msg.imageBase64List.isEmpty())
    }

    @Test
    fun `should preserve imageBase64List when provided`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            content = "",
            imageBase64List = listOf("abc", "def"),
        )
        assertEquals(listOf("abc", "def"), msg.imageBase64List)
    }

    @Test
    fun `should default toolCalls to null when not provided`() {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        assertNull(msg.toolCalls)
    }

    @Test
    fun `should preserve toolCalls when provided`() {
        val tc = ToolCall(id = "tc1", name = "getWeather", arguments = "{\"city\":\"北京\"}")
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(tc),
        )
        assertNotNull(msg.toolCalls)
        assertEquals(1, msg.toolCalls!!.size)
        assertEquals("tc1", msg.toolCalls!![0].id)
        assertEquals("getWeather", msg.toolCalls!![0].name)
        assertEquals("{\"city\":\"北京\"}", msg.toolCalls!![0].arguments)
    }

    @Test
    fun `should default citationUrls to empty list when not provided`() {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        assertTrue(msg.citationUrls.isEmpty())
    }

    @Test
    fun `should preserve citationUrls when provided`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "see [1]",
            citationUrls = listOf("https://example.com/a", "https://example.com/b"),
        )
        assertEquals(
            listOf("https://example.com/a", "https://example.com/b"),
            msg.citationUrls,
        )
    }

    @Test
    fun `should default favorite to false when not provided`() {
        val msg = UIMessage(role = MessageRole.USER, content = "")
        assertEquals(false, msg.favorite)
    }

    @Test
    fun `should preserve favorite when set to true`() {
        val msg = UIMessage(role = MessageRole.USER, content = "", favorite = true)
        assertEquals(true, msg.favorite)
    }

    @Test
    fun `should default toolCallId to null when not provided`() {
        val msg = UIMessage(role = MessageRole.TOOL, content = "")
        assertNull(msg.toolCallId)
    }

    @Test
    fun `should preserve toolCallId when provided`() {
        val msg = UIMessage(
            role = MessageRole.TOOL,
            content = "result",
            toolCallId = "call_abc",
        )
        assertEquals("call_abc", msg.toolCallId)
    }

    @Test
    fun `should default imageUrls to empty list when not provided`() {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        assertTrue(msg.imageUrls.isEmpty())
    }

    @Test
    fun `should preserve imageUrls when provided`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            imageUrls = listOf("https://img.example.com/1.png"),
        )
        assertEquals(listOf("https://img.example.com/1.png"), msg.imageUrls)
    }

    @Test
    fun `should default modelId to null when not provided`() {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        assertNull(msg.modelId)
    }

    @Test
    fun `should preserve modelId when provided`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = "gpt-4o",
        )
        assertEquals("gpt-4o", msg.modelId)
    }

    @Test
    fun `should populate createdAt when constructed`() {
        val before = System.currentTimeMillis()
        val msg = UIMessage(role = MessageRole.USER, content = "")
        val after = System.currentTimeMillis()
        assertTrue(msg.createdAt in before..after)
    }

    @Test
    fun `should return content as text when calling toText`() {
        val msg = UIMessage(role = MessageRole.USER, content = "plain text")
        assertEquals("plain text", msg.toText())
    }

    @Test
    fun `should return empty string when toText called on empty content`() {
        val msg = UIMessage(role = MessageRole.USER, content = "")
        assertEquals("", msg.toText())
    }
}
