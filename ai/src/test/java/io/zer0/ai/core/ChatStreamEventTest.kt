package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatStreamEvent 单元测试。
 *
 * 覆盖 sealed class 各子类(ContentDelta / ReasoningDelta / ImageDelta /
 * ToolCallDelta / Done / Error)的字段、默认值与多态类型标识。
 */
class ChatStreamEventTest {

    @Test
    fun `should hold delta string when constructing ContentDelta`() {
        val ev = ChatStreamEvent.ContentDelta(delta = "hello")
        assertEquals("hello", ev.delta)
    }

    @Test
    fun `should be ChatStreamEvent subtype when ContentDelta constructed`() {
        val ev: ChatStreamEvent = ChatStreamEvent.ContentDelta("x")
        assertTrue(ev is ChatStreamEvent.ContentDelta)
    }

    @Test
    fun `should hold delta string when constructing ReasoningDelta`() {
        val ev = ChatStreamEvent.ReasoningDelta(delta = "thinking")
        assertEquals("thinking", ev.delta)
    }

    @Test
    fun `should be ChatStreamEvent subtype when ReasoningDelta constructed`() {
        val ev: ChatStreamEvent = ChatStreamEvent.ReasoningDelta("x")
        assertTrue(ev is ChatStreamEvent.ReasoningDelta)
    }

    @Test
    fun `should hold imageBase64 when constructing ImageDelta`() {
        val ev = ChatStreamEvent.ImageDelta(imageBase64 = "AAAA")
        assertEquals("AAAA", ev.imageBase64)
    }

    @Test
    fun `should default mimeType to image png when not provided`() {
        val ev = ChatStreamEvent.ImageDelta(imageBase64 = "")
        assertEquals("image/png", ev.mimeType)
    }

    @Test
    fun `should preserve custom mimeType when provided`() {
        val ev = ChatStreamEvent.ImageDelta(imageBase64 = "", mimeType = "image/jpeg")
        assertEquals("image/jpeg", ev.mimeType)
    }

    @Test
    fun `should hold index when constructing ToolCallDelta`() {
        val ev = ChatStreamEvent.ToolCallDelta(index = 2)
        assertEquals(2, ev.index)
    }

    @Test
    fun `should default id to null when ToolCallDelta constructed without id`() {
        val ev = ChatStreamEvent.ToolCallDelta(index = 0)
        assertNull(ev.id)
    }

    @Test
    fun `should default name to null when ToolCallDelta constructed without name`() {
        val ev = ChatStreamEvent.ToolCallDelta(index = 0)
        assertNull(ev.name)
    }

    @Test
    fun `should default argumentsDelta to null when ToolCallDelta constructed without arguments`() {
        val ev = ChatStreamEvent.ToolCallDelta(index = 0)
        assertNull(ev.argumentsDelta)
    }

    @Test
    fun `should preserve id name and argumentsDelta when ToolCallDelta fully provided`() {
        val ev = ChatStreamEvent.ToolCallDelta(
            index = 1,
            id = "tc_1",
            name = "getWeather",
            argumentsDelta = "{\"city\"",
        )
        assertEquals(1, ev.index)
        assertEquals("tc_1", ev.id)
        assertEquals("getWeather", ev.name)
        assertEquals("{\"city\"", ev.argumentsDelta)
    }

    @Test
    fun `should default finishReason to null when Done constructed without args`() {
        val ev = ChatStreamEvent.Done()
        assertNull(ev.finishReason)
    }

    @Test
    fun `should preserve finishReason when Done constructed with reason`() {
        val ev = ChatStreamEvent.Done(finishReason = "stop")
        assertEquals("stop", ev.finishReason)
    }

    @Test
    fun `should hold message when constructing Error`() {
        val ev = ChatStreamEvent.Error(message = "boom")
        assertEquals("boom", ev.message)
    }

    @Test
    fun `should default throwable to null when Error constructed without throwable`() {
        val ev = ChatStreamEvent.Error(message = "boom")
        assertNull(ev.throwable)
    }

    @Test
    fun `should preserve throwable when Error constructed with throwable`() {
        val ex = RuntimeException("inner")
        val ev = ChatStreamEvent.Error(message = "boom", throwable = ex)
        assertNotNull(ev.throwable)
        assertEquals("inner", ev.throwable!!.message)
    }

    @Test
    fun `should distinguish all six event subtypes when inspecting sealed hierarchy`() {
        val events: List<ChatStreamEvent> = listOf(
            ChatStreamEvent.ContentDelta("c"),
            ChatStreamEvent.ReasoningDelta("r"),
            ChatStreamEvent.ImageDelta("img"),
            ChatStreamEvent.ToolCallDelta(index = 0),
            ChatStreamEvent.Done(),
            ChatStreamEvent.Error("err"),
        )
        assertEquals(6, events.size)
        assertTrue(events[0] is ChatStreamEvent.ContentDelta)
        assertTrue(events[1] is ChatStreamEvent.ReasoningDelta)
        assertTrue(events[2] is ChatStreamEvent.ImageDelta)
        assertTrue(events[3] is ChatStreamEvent.ToolCallDelta)
        assertTrue(events[4] is ChatStreamEvent.Done)
        assertTrue(events[5] is ChatStreamEvent.Error)
    }
}
