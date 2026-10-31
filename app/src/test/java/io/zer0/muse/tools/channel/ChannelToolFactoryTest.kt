package io.zer0.muse.tools.channel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: ChannelToolFactory 群聊三件套集成面测试。
 *
 * 锁定 GroupChatScheduler 使用的产出契约:三件套定义名、执行器按名分派、
 * 以及 reply/pass/read_context 三条回调链路的参数语义(trim / 范围钳制 / 默认值)。
 */
class ChannelToolFactoryTest {

    @Test
    fun `factory exposes the three channel tools with matching executors`() = runTest {
        val (defs, executors) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = {},
            contextProvider = { "ctx" },
        )

        // 顺序:reply → pass → read_context
        assertEquals(
            listOf("channel_reply", "channel_pass", "channel_read_context"),
            defs.map { it.name },
        )
        // 每个定义都有对应执行器(按名分派)
        assertEquals(defs.map { it.name }.toSet(), executors.keys)
        // 参数 schema 不泄漏框架绑定字段(chatId/assistantId 不可由 LLM 传入)
        val replySchema = defs.first { it.name == "channel_reply" }.parametersJsonSchema
        assertTrue(replySchema.contains("content"))
        assertTrue(!replySchema.contains("chatId"))
        assertTrue(!replySchema.contains("assistantId"))
        // read_context 的 limit 声明为 integer
        val readSchema = defs.first { it.name == "channel_read_context" }.parametersJsonSchema
        assertTrue(readSchema.contains("limit"))
        assertTrue(readSchema.contains("integer"))
    }

    @Test
    fun `reply forwards trimmed content and rejects empty`() = runTest {
        var reply: String? = null
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = { reply = it },
            onPass = {},
            contextProvider = { "ctx" },
        )
        val channelReply = exec.getValue("channel_reply")

        channelReply.invoke(mapOf("content" to "  hello group  "))
        assertEquals("hello group", reply)

        // 空 content 不触发发送,返回错误文本(避免空气泡落库)
        reply = null
        assertEquals("错误: content 不能为空", channelReply.invoke(emptyMap()))
        assertNull(reply)
    }

    @Test
    fun `pass forwards reason and drops blank`() = runTest {
        var reason: String? = "unset"
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = { reason = it },
            contextProvider = { "ctx" },
        )
        val channelPass = exec.getValue("channel_pass")

        channelPass.invoke(mapOf("reason" to "  等别人先说  "))
        assertEquals("等别人先说", reason)

        channelPass.invoke(mapOf("reason" to "   "))
        assertNull(reason)
    }

    @Test
    fun `read_context clamps limit into the 1 to 50 range and returns provider text`() = runTest {
        var seenLimit = -1
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = {},
            contextProvider = { limit ->
                seenLimit = limit
                "hist-$limit"
            },
        )
        val readContext = exec.getValue("channel_read_context")

        // 缺省 limit → 20
        assertEquals("hist-20", readContext.invoke(emptyMap()))
        assertEquals(20, seenLimit)
        // 超上限钳到 50
        readContext.invoke(mapOf("limit" to "999"))
        assertEquals(50, seenLimit)
        // 低于下限钳到 1
        readContext.invoke(mapOf("limit" to "0"))
        assertEquals(1, seenLimit)
        // 非法值回退默认 20
        readContext.invoke(mapOf("limit" to "abc"))
        assertEquals(20, seenLimit)
    }
}
