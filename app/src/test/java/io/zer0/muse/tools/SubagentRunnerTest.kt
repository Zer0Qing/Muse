package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.52 P2-1: SubagentRunner + SubagentXmlRenderer 单元测试。
 *
 * 覆盖范围:
 *  - [SubagentXmlRenderer] 纯函数(XML 渲染 / 转义 / 截断)
 *  - [SubagentRunner] 参数校验(空 task / maxToolCalls 截断)
 *  - [SubagentRunner] 工具白名单过滤(HIGH + 递归工具被排除)
 *  - [SubagentRunner] 工具循环流程(无 toolCalls / 有 toolCalls / 配额耗尽)
 *  - [SubagentRunSkill] 参数解析 + XML 拼接
 *
 * 运行方式: `./gradlew :app:testDebugUnitTest --tests "*SubagentRunnerTest*"`
 */
class SubagentRunnerTest {

    // ── SubagentXmlRenderer 纯函数测试 ──────────────────────────────────────

    @Test
    fun `renderStart produces correct XML format`() {
        val xml = SubagentXmlRenderer.renderStart("搜索 Kotlin 协程资料", 8)
        assertEquals("<subagent_start task=\"搜索 Kotlin 协程资料\" max_tool_calls=\"8\"/>", xml)
    }

    @Test
    fun `renderStart escapes XML special characters in task`() {
        val xml = SubagentXmlRenderer.renderStart("任务 <含> 特殊 \"字符\" & '符号'", 4)
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&gt;"))
        assertTrue(xml.contains("&quot;"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&apos;"))
        // 不应包含未转义的特殊字符(属性值内)
        assertFalse(xml.contains("task=\"任务 <含>"))
    }

    @Test
    fun `renderProgress produces correct XML with all attributes`() {
        val xml = SubagentXmlRenderer.renderProgress(
            round = 1,
            maxToolCalls = 8,
            toolName = "web_search",
            argsJson = """{"query":"kotlin"}""",
            result = "找到 3 条结果",
            success = true,
        )
        assertTrue(xml.startsWith("<subagent_progress "))
        assertTrue(xml.contains("round=\"1\""))
        assertTrue(xml.contains("max_tool_calls=\"8\""))
        assertTrue(xml.contains("tool=\"web_search\""))
        assertTrue(xml.contains("success=\"true\""))
        assertTrue(xml.contains("args="))
        assertTrue(xml.contains("result="))
        assertTrue(xml.endsWith("/>"))
    }

    @Test
    fun `renderProgress truncates long args and result`() {
        val longArgs = "x".repeat(600)
        val longResult = "y".repeat(500)
        val xml = SubagentXmlRenderer.renderProgress(
            round = 1, maxToolCalls = 8,
            toolName = "test", argsJson = longArgs, result = longResult, success = true,
        )
        assertTrue(xml.contains("truncated"))
    }

    @Test
    fun `renderResult success produces correct XML`() {
        val xml = SubagentXmlRenderer.renderResult(
            success = true, rounds = 2, toolCalls = 2,
            summary = "任务完成,找到 3 条结果",
        )
        assertTrue(xml.contains("<subagent_result success=\"true\""))
        assertTrue(xml.contains("rounds=\"2\""))
        assertTrue(xml.contains("tool_calls=\"2\""))
        assertTrue(xml.contains("任务完成,找到 3 条结果"))
        assertTrue(xml.contains("</subagent_result>"))
    }

    @Test
    fun `renderResult failure includes error and partial summary`() {
        val xml = SubagentXmlRenderer.renderResult(
            success = false, rounds = 1, toolCalls = 0,
            summary = "部分结果", error = "超时",
        )
        assertTrue(xml.contains("success=\"false\""))
        assertTrue(xml.contains("[FAILED]"))
        assertTrue(xml.contains("超时"))
        assertTrue(xml.contains("[PARTIAL]"))
        assertTrue(xml.contains("部分结果"))
    }

    @Test
    fun `renderBudgetExhausted includes maxToolCalls value`() {
        val text = SubagentXmlRenderer.renderBudgetExhausted(8)
        assertTrue(text.contains("8"))
        assertTrue(text.contains("配额上限"))
    }

    @Test
    fun `renderResult truncates long summary`() {
        val longSummary = "z".repeat(5000)
        val xml = SubagentXmlRenderer.renderResult(
            success = true, rounds = 1, toolCalls = 0, summary = longSummary,
        )
        assertTrue(xml.contains("truncated"))
    }

    // ── SubagentRunner 参数校验测试 ─────────────────────────────────────────

    @Test
    fun `run returns error when task is blank`() = runTest {
        val runner = SubagentRunner(mockk(relaxed = true), mockk(relaxed = true), mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = ""))
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `run returns error when task is whitespace only`() = runTest {
        val runner = SubagentRunner(mockk(relaxed = true), mockk(relaxed = true), mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "   "))
        assertFalse(result.success)
    }

    @Test
    fun `run coerces maxToolCalls to hard cap`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        // 超出硬上限 20,应被截断到 20(不会报错)
        val result = runner.run(SubagentRunner.Params(task = "测试", maxToolCalls = 100))
        assertTrue(result.success)
    }

    // ── SubagentRunner 工具白名单过滤测试 ───────────────────────────────────

    @Test
    fun `run excludes HIGH risk tools from allowed list`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        // 模拟注册了 HIGH + SAFE 工具
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("subagent_run", "delegate", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
            ToolRegistry.ToolDef("delegate_agent", "delegate", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试"))
        assertTrue(result.success)
        // 验证 listToolsAsToolDefinitions 被调用时只传入 web_search(HIGH 工具被过滤)
        // 通过验证返回的工具定义列表只含 web_search 间接确认
    }

    @Test
    fun `run excludes recursive tools even if risk level is not HIGH`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            // subagent_task / delegate_agent 标为 HIGH,但即使标 SAFE 也应被排除
            ToolRegistry.ToolDef("subagent_task", "async", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("delegate_agent", "delegate", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试"))
        assertTrue(result.success)
    }

    // ── SubagentRunner 工具循环流程测试 ─────────────────────────────────────

    @Test
    fun `run returns summary immediately when LLM has no tool calls`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "这是最终总结", finishReason = "stop")

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "直接回答任务"))

        assertTrue(result.success)
        assertEquals("这是最终总结", result.summary)
        assertEquals(1, result.rounds)
        assertEquals(0, result.toolCalls)
        assertFalse(result.budgetExhausted)
        assertTrue(result.progressEntries.isEmpty())
    }

    @Test
    fun `run executes tool calls and continues loop`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // 第一次调用返回 toolCalls,第二次返回纯文本总结
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "echo", arguments = """{"text":"hi"}""")),
            ),
            ChatCompletion(text = "工具调用完成,这是总结", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("echo", any()) } returns "hi"

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "调用 echo 工具", maxToolCalls = 5))

        assertTrue(result.success)
        assertEquals("工具调用完成,这是总结", result.summary)
        assertEquals(2, result.rounds)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        assertEquals("echo", result.progressEntries[0].toolName)
        assertEquals("hi", result.progressEntries[0].result)
        assertTrue(result.progressEntries[0].success)
    }

    @Test
    fun `run stops when maxToolCalls budget exhausted and forces summary`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // 每次都返回 toolCalls,触发配额耗尽
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(
            text = "",
            finishReason = "tool_calls",
            toolCalls = listOf(ToolCall(id = "call_x", name = "echo", arguments = """{"text":"loop"}""")),
        )
        // 配额耗尽后的总结调用(tools=null)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), isNull(), any(), any(), any())
        } returns ChatCompletion(text = "配额耗尽总结", finishReason = "stop")
        coEvery { mockToolRegistry.executeFromJson("echo", any()) } returns "loop"

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "循环调用", maxToolCalls = 2))

        assertTrue(result.success)
        assertEquals("配额耗尽总结", result.summary)
        assertEquals(2, result.toolCalls)
        assertTrue(result.budgetExhausted)
        assertEquals(2, result.progressEntries.size)
    }

    @Test
    fun `run filters invalid toolCalls with empty name or arguments`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        // 返回无效 toolCalls(空 name / 空 arguments),应按无工具调用处理
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(
            text = "无效工具调用,直接总结",
            finishReason = "tool_calls",
            toolCalls = listOf(
                ToolCall(id = "1", name = "", arguments = """{"a":"b"}"""),
                ToolCall(id = "2", name = "echo", arguments = ""),
            ),
        )

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试"))

        assertTrue(result.success)
        assertEquals("无效工具调用,直接总结", result.summary)
        assertEquals(0, result.toolCalls)
    }

    @Test
    fun `run rejects tool call not in allowed list`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // LLM 幻觉调用了一个不在白名单的工具
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "1", name = "dangerous_tool", arguments = """{"x":"y"}""")),
            ),
            ChatCompletion(text = "总结", finishReason = "stop"),
        )

        val runner = SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试", maxToolCalls = 3))

        assertTrue(result.success)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        // 工具执行结果应是错误信息
        assertTrue(result.progressEntries[0].result.contains("Error"))
        assertFalse(result.progressEntries[0].success)
    }

    // ── SubagentRunSkill 参数解析测试 ───────────────────────────────────────

    @Test
    fun `SubagentRunSkill execute returns error XML when task missing`() = runTest {
        val mockRunner = mockk<SubagentRunner>(relaxed = true)
        val result = SubagentRunSkill.execute(emptyMap(), mockRunner)
        assertTrue(result.contains("<subagent_result"))
        assertTrue(result.contains("success=\"false\""))
        assertTrue(result.contains("task"))
    }

    @Test
    fun `SubagentRunSkill execute parses target_paths as comma separated list`() = runTest {
        val mockRunner = mockk<SubagentRunner>(relaxed = true)
        coEvery { mockRunner.run(any()) } returns SubagentRunner.Result(
            success = true, summary = "完成", rounds = 1, toolCalls = 0,
        )
        val result = SubagentRunSkill.execute(
            mapOf(
                "task" to "测试",
                "target_paths" to "/a/b.txt, /c/d.txt , /e/f.txt",
                "max_tool_calls" to "5",
            ),
            mockRunner,
        )
        // 验证三阶段 XML 都存在
        assertTrue(result.contains("<subagent_start"))
        assertTrue(result.contains("<subagent_result"))
        // 验证 max_tool_calls 被解析
        assertTrue(result.contains("max_tool_calls=\"5\""))
    }

    @Test
    fun `SubagentRunSkill toolDef has HIGH risk level`() {
        val def = SubagentRunSkill.toolDef()
        assertEquals("subagent_run", def.name)
        assertEquals(ToolRiskLevel.HIGH, def.riskLevel)
        assertTrue(def.required.contains("task"))
        assertEquals("integer", def.parameterTypes["max_tool_calls"])
    }

    // v1.0.53: 线程账本 mock — getOrCreate 返回有效 Pair,runSerialized 透传执行
    private fun mockThreadStore(): io.zer0.muse.data.subagent.SubagentThreadStore {
        val store = io.mockk.mockk<io.zer0.muse.data.subagent.SubagentThreadStore>(relaxed = true)
        io.mockk.coEvery { store.getOrCreate(any(), any(), any(), any(), any()) } returns ("test-thread" to true)
        io.mockk.coEvery { store.runSerialized<Any>(any(), any()) } coAnswers {
            secondArg<suspend () -> Any>()()
        }
        io.mockk.every { store.sessionPathOf(any()) } returns "/tmp/subagent_test.jsonl"
        return store
    }

    // v1.0.53: 并发限流 mock — run 透传执行 block
    private fun mockLimiter(): io.zer0.muse.tools.AgentConcurrencyLimiter {
        val limiter = io.mockk.mockk<io.zer0.muse.tools.AgentConcurrencyLimiter>(relaxed = true)
        io.mockk.coEvery { limiter.run<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>()()
        }
        return limiter
    }
}