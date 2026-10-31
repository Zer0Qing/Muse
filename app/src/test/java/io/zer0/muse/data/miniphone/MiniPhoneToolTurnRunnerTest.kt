package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.tools.ToolCategories
import io.zer0.muse.tools.ToolCategory
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小手机私信里那一轮工具生成的行为护栏。
 *
 * 生成入口与工具注册表都用假实现:不连模型、不真跑工具,只验「哪些工具给得出去」、
 * 「工具结果怎么回填」、「轮次与超时怎么收口」这三件事。
 */
class MiniPhoneToolTurnRunnerTest {

    @Test
    fun `工具轮先发活动再发文本并把结果回填成 TOOL 消息`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("quick_note_list"))
        coEvery { registry.executeFromJson("quick_note_list", "{}") } returns "有一张便签"
        val sent = stubCompletions(chatService) { index, _ ->
            if (index == 1) {
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "quick_note_list", arguments = "{}")),
                )
            } else {
                ChatCompletion(text = " 今天有一张便签 ")
            }
        }

        val events = runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf(MpReplyEvent.Activity(MpActivityKind.NOTES), MpReplyEvent.Text("今天有一张便签")), events)
        coVerify(exactly = 1) { registry.executeFromJson("quick_note_list", "{}") }

        val backfilled = sent.messages[1].last()
        assertEquals(MessageRole.TOOL, backfilled.role)
        assertEquals("有一张便签", backfilled.content)
        assertEquals("call-1", backfilled.toolCallId)
    }

    @Test
    fun `被禁类别的工具不会出现在请求里`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("execute_python_script"), def("get_weather"))
        val sent = stubCompletions(chatService) { _, _ -> ChatCompletion(text = "今天晴") }

        runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf("get_weather"), sent.tools.first().orEmpty().map { it.name })
    }

    @Test
    fun `高风险工具即使类别在白名单里也不会出现`() = runBlocking {
        // 前提断言:这两个名字确实一边在允许的类别里、一边被风险真源判成 HIGH
        assertEquals(ToolCategory.STANDARD, ToolCategories.categoryOf(SAVE_MEMORY))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor(SAVE_MEMORY))

        val chatService = mockk<ChatService>()
        val registry = registryWith(def(SAVE_MEMORY), def("get_weather"))
        val sent = stubCompletions(chatService) { _, _ -> ChatCompletion(text = "今天晴") }

        runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf("get_weather"), sent.tools.first().orEmpty().map { it.name })
    }

    @Test
    fun `三轮工具用尽后改发一次不带工具的请求`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("quick_note_list"))
        val sent = stubCompletions(chatService) { index, tools ->
            if (tools == null) {
                ChatCompletion(text = "好的")
            } else {
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(ToolCall(id = "call-$index", name = "quick_note_list", arguments = "{}")),
                )
            }
        }

        val events = runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals("三轮工具之后还要一次收尾请求", 4, sent.tools.size)
        assertTrue("前三轮带工具", sent.tools.take(MAX_TOOL_ROUNDS).all { it != null })
        assertNull("收尾那一轮不带工具", sent.tools.last())
        assertEquals(MAX_TOOL_ROUNDS, events.count { it is MpReplyEvent.Activity })
        assertEquals(MpReplyEvent.Text("好的"), events.last())
    }

    @Test
    fun `生成挂住时按超时发 Failed`() = runBlocking {
        val chatService = mockk<ChatService>()
        coEvery {
            chatService.completeText(
                messages = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                toolChoice = any(),
            )
        } coAnswers {
            delay(SLOW_REPLY_MS)
            ChatCompletion(text = "太晚了")
        }

        val startedAt = System.currentTimeMillis()
        val events = runner(chatService, registryWith(), timeoutMs = SHORT_TIMEOUT_MS)
            .reply(messages(), null, null)
            .toList()
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(listOf(MpReplyEvent.Failed), events)
        assertTrue("超时应当立刻收口，实际耗时 ${elapsed}ms", elapsed < TIMEOUT_UPPER_BOUND_MS)
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 一次运行里被抓到的请求:每轮请求收到的工具定义,以及那一轮的消息列表。 */
    private class Captured {
        val tools: MutableList<List<ToolDefinition>?> = mutableListOf()
        val messages: MutableList<List<UIMessage>> = mutableListOf()
    }

    /**
     * 按调用序号回答:第 [index] 次(从 1 起)请求会拿到什么。
     *
     * 同一份桩同时记下每轮请求的工具集与消息列表,断言据此展开。
     */
    private fun stubCompletions(
        chatService: ChatService,
        answer: (index: Int, tools: List<ToolDefinition>?) -> ChatCompletion,
    ): Captured {
        val captured = Captured()
        val toolsSlot = slot<List<ToolDefinition>?>()
        val messagesSlot = slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(
                messages = capture(messagesSlot),
                temperature = any(),
                maxTokens = any(),
                tools = captureNullable(toolsSlot),
                toolChoice = any(),
            )
        } coAnswers {
            captured.tools += toolsSlot.captured
            captured.messages += messagesSlot.captured
            answer(captured.tools.size, toolsSlot.captured)
        }
        return captured
    }

    private fun runner(
        chatService: ChatService,
        registry: ToolRegistry,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): MiniPhoneToolTurnRunner = MiniPhoneToolTurnRunner(
        chatService = chatService,
        toolRegistry = registry,
        timeoutMs = timeoutMs,
    )

    private fun registryWith(vararg definitions: ToolDefinition): ToolRegistry {
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.listToolsAsToolDefinitions(any()) } returns definitions.toList()
        return registry
    }

    private fun def(name: String): ToolDefinition = ToolDefinition(
        name = name,
        description = "测试用工具",
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
    )

    private fun messages(): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.SYSTEM, content = "你是阿绫"),
        UIMessage(role = MessageRole.USER, content = "在吗"),
    )

    private companion object {
        const val SAVE_MEMORY = "save_memory"
        const val MAX_TOOL_ROUNDS = 3

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val SHORT_TIMEOUT_MS = 150L
        const val SLOW_REPLY_MS = 5_000L

        /** 超时收口的耗时上限:留足 JVM 冷启动余量,但远小于挂住的 5 秒。 */
        const val TIMEOUT_UPPER_BOUND_MS = 2_000L
    }
}
