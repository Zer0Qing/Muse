package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
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
    fun `renderProgress keeps full result while bounding arguments`() {
        val longArgs = "x".repeat(600)
        val longResult = "y".repeat(500)
        val xml = SubagentXmlRenderer.renderProgress(
            round = 1,
            maxToolCalls = 8,
            toolName = "test",
            argsJson = longArgs,
            result = longResult,
            success = true,
        )
        assertTrue(xml.contains("truncated"))
        assertTrue(xml.contains(longResult))
    }

    @Test
    fun `renderResult success produces correct XML`() {
        val xml = SubagentXmlRenderer.renderResult(
            success = true,
            rounds = 2,
            toolCalls = 2,
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
            success = false,
            rounds = 1,
            toolCalls = 0,
            summary = "部分结果",
            error = "超时",
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
    fun `renderResult preserves long summary`() {
        val longSummary = "z".repeat(5000)
        val xml = SubagentXmlRenderer.renderResult(
            success = true,
            rounds = 1,
            toolCalls = 0,
            summary = longSummary,
        )
        assertTrue(xml.contains(longSummary))
        assertFalse(xml.contains("truncated"))
    }

    // ── SubagentRunner 参数校验测试 ─────────────────────────────────────────

    @Test
    fun `run returns error when task is blank`() = runTest {
        val runner =
            SubagentRunner(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockThreadStore(),
                mockLimiter(),
                mockk(relaxed = true),
                ToolApprovalRouter(),
            )
        val result = runner.run(SubagentRunner.Params(task = ""))
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `run returns error when task is whitespace only`() = runTest {
        val runner =
            SubagentRunner(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockThreadStore(),
                mockLimiter(),
                mockk(relaxed = true),
                ToolApprovalRouter(),
            )
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

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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
        // 捕获实际传给 listToolsAsToolDefinitions 的名单 —— 这才是「模型看得到什么」。
        // 原来这里只 assertTrue(result.success)，注释写着「间接确认」，实际一句断言都没有，
        // HIGH 工具被放进来也不会红。改成真断言。
        val offered = slot<List<String>>()
        every { mockToolRegistry.listToolsAsToolDefinitions(capture(offered)) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试"))
        assertTrue(result.success)

        val names = offered.captured
        assertTrue("SAFE 工具应在名单内，实际: $names", names.contains("web_search"))
        assertTrue("HIGH 工具必须被剔除，实际: $names", names.none { it == "subagent_run" || it == "delegate_agent" })
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

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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
        // 风险等级要显式声明:闸门取不到就按 HIGH 处理（保守）,而 echo 本身是只读工具
        every { mockToolRegistry.getToolRiskLevel("echo") } returns ToolRiskLevel.SAFE

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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
    fun `persistent approval pauses the run before later tool calls`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写入", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            ToolRegistry.ToolDef("echo", "回显", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写入", "{}"),
            ToolDefinition("echo", "回显", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(
            text = "",
            finishReason = "tool_calls",
            toolCalls = listOf(
                ToolCall(id = "write-1", name = "write_file", arguments = "{}"),
                ToolCall(id = "echo-1", name = "echo", arguments = "{}"),
            ),
        )

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(
            SubagentRunner.Params(
                task = "写文件后回显",
                onApprovalRequired = { _, _, _, _ ->
                    SubagentRunner.ApprovalRequestResult.Created("approval-1")
                },
            ),
        )

        assertFalse(result.success)
        assertTrue("持久审批应结束当前执行", result.approvalPending)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        coVerify(exactly = 1) {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { mockToolRegistry.executeFromJson(any(), any()) }
    }

    @Test
    fun `approved one shot grant allows the matching tool call`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写入", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写入", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall("write-1", "write_file", "{}")),
            ),
            ChatCompletion(text = "文件已处理", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", "{}") } returns "written"

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(
            SubagentRunner.Params(
                task = "写文件",
                onApprovalRequired = { _, _, _, _ ->
                    SubagentRunner.ApprovalRequestResult.Approved("approval-1")
                },
            ),
        )

        assertTrue(result.success)
        assertEquals("文件已处理", result.summary)
        coVerify(exactly = 1) { mockToolRegistry.executeFromJson("write_file", "{}") }
    }

    @Test
    fun `host tool context carries the task resource scope and step identity`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "回显", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "回显", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("echo") } returns ToolRiskLevel.SAFE
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall("echo-1", "echo", "{}")),
            ),
            ChatCompletion(text = "done", finishReason = "stop"),
        )
        val receivedContext = slot<ToolExecutionContext>()
        coEvery {
            mockToolRegistry.executeFromJson("echo", "{}", capture(receivedContext))
        } returns "ok"

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        runner.run(
            SubagentRunner.Params(
                task = "只查看 notes.md",
                onToolStarted = { _, _ -> "step-1" },
                toolExecutionContext = ToolExecutionContext(
                    scope = "main",
                    spaceId = "default",
                    assistantId = "assistant-a",
                    sessionId = "session-a",
                    resourceScope = "notes.md",
                    isSubagentContext = true,
                ),
            ),
        )

        assertEquals("notes.md", receivedContext.captured.resourceScope)
        assertEquals("step-1", receivedContext.captured.stepId)
        assertEquals("session-a", receivedContext.captured.sessionId)
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
        every { mockToolRegistry.getToolRiskLevel("echo") } returns ToolRiskLevel.SAFE

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
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

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "测试", maxToolCalls = 3))

        assertTrue(result.success)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        // 工具执行结果应是错误信息
        assertTrue(result.progressEntries[0].result.contains("Error"))
        assertFalse(result.progressEntries[0].success)
    }

    // ── 审批闸门：子代理的判定必须与会话模式一致 ────────────────────────────

    /**
     * 有审批通道时：调用退到主会话审批链，STRICT 会话下的 NORMAL 工具必须进不了执行。
     *
     * 桥接这里扮演主会话审批链（与 [io.zer0.muse.ui.chat.ChatToolApprovalCoordinator] 同一条解析器调用），
     * 因此测试能同时钉住「模式生效」与「传的是全量参数」。
     */
    @Test
    fun `approval bridge blocks normal tool in strict session and receives full args`() = runTest {
        val seen = mutableListOf<Map<String, Any?>>()
        val previews = mutableListOf<String>()
        val bridge = object : ToolApprovalBridge {
            override suspend fun requestToolApproval(
                toolName: String,
                toolCallId: String,
                argsPreview: String,
                args: Map<String, Any?>,
            ): ToolApprovalState {
                seen += args
                previews += argsPreview
                return ToolPermissionResolver.resolve(
                    toolName = toolName,
                    risk = ToolRiskLevel.NORMAL,
                    mode = SessionPermissionMode.STRICT,
                    perToolPolicy = null,
                    args = args,
                )
            }
        }
        val router = ToolApprovalRouter().also { it.delegate = bridge }
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写文件", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写文件", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL

        val mockChatService = mockk<ChatService>(relaxed = true)
        // 参数总量远超预览长度,判定必须看到完整值
        val longPath = "/sdcard/notes/" + "a".repeat(400) + ".txt"
        val arguments = """{"path":"$longPath","content":"hello"}"""
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = arguments)),
            ),
            ChatCompletion(text = "工具被拦住了", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            router,
        )
        val result = runner.run(SubagentRunner.Params(task = "写文件", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertEquals(1, result.progressEntries.size)
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
        // 判定收到的是全量参数,预览才是截断的
        assertEquals(longPath, seen.single()["path"])
        assertTrue("预览不应该带上完整参数", previews.single().length <= 200)
    }

    /** 有审批通道且用户批准时，工具照常执行。 */
    @Test
    fun `approval bridge keeps normal tool executable when approved`() = runTest {
        val router = ToolApprovalRouter().also {
            it.delegate = object : ToolApprovalBridge {
                override suspend fun requestToolApproval(
                    toolName: String,
                    toolCallId: String,
                    argsPreview: String,
                    args: Map<String, Any?>,
                ): ToolApprovalState = ToolApprovalState.Approved()
            }
        }
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写文件", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写文件", "{}"),
        )
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "写完了", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            router,
        )
        val result = runner.run(SubagentRunner.Params(task = "写文件", maxToolCalls = 3))

        coVerify(exactly = 1) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertTrue(result.progressEntries[0].success)
        assertEquals("written", result.progressEntries[0].result)
    }

    /** 无审批通道 + STRICT（settings 缺失时的兜底）:NORMAL 工具被拦。 */
    @Test
    fun `no approval channel blocks normal tool without settings`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写文件", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写文件", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "工具被拦住了", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        // settings = null ⇒ 模式取不到,兜底 STRICT
        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(SubagentRunner.Params(task = "写文件", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
    }

    /** 无审批通道 + ASK 会话:Pending 等于拒绝,不能默默放行。 */
    @Test
    fun `no approval channel turns pending into denial`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写文件", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写文件", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "工具被拦住了", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"
        val settings = mockSettings(SessionPermissionMode.ASK)

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
            settings,
        )
        val result = runner.run(SubagentRunner.Params(task = "写文件", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
    }

    /** 无审批通道 + TRUSTED 会话:正常放行（闸门按模式判定,不是一律拒绝）。 */
    @Test
    fun `no approval channel follows trusted session mode`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "写文件", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "写文件", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "写完了", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
            mockSettings(SessionPermissionMode.TRUSTED),
        )
        val result = runner.run(SubagentRunner.Params(task = "写文件", maxToolCalls = 3))

        coVerify(exactly = 1) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertTrue(result.progressEntries[0].success)
    }

    /**
     * 参数化硬拒在子代理路径上生效，且看的是全量参数。
     *
     * 会话模式设为 TRUSTED（静态判定全放行）,所以被拦只能来自参数化策略;
     * 危险标记特意放在 200 字符之后,只看预览就抓不到。
     */
    @Test
    fun `parametrized hard denial applies on subagent path with full args`() = runTest {
        val probeTool = "probe_param_guard"
        ParamPolicies.register(probeTool) { args ->
            val path = args["path"] as? String ?: return@register null
            if (path.contains("secret")) {
                ToolApprovalState.Denied("路径命中参数化硬拒,已拦截")
            } else {
                null
            }
        }
        try {
            val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
            every { mockToolRegistry.listTools() } returns listOf(
                ToolRegistry.ToolDef(probeTool, "探针工具", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            )
            every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
                ToolDefinition(probeTool, "探针工具", "{}"),
            )
            every { mockToolRegistry.getToolRiskLevel(probeTool) } returns ToolRiskLevel.NORMAL
            val mockChatService = mockk<ChatService>(relaxed = true)
            val path = "/sdcard/" + "p".repeat(260) + "/secret.txt"
            coEvery {
                mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
            } returnsMany listOf(
                ChatCompletion(
                    text = "",
                    finishReason = "tool_calls",
                    toolCalls = listOf(ToolCall(id = "call_1", name = probeTool, arguments = """{"path":"$path"}""")),
                ),
                ChatCompletion(text = "工具被拦住了", finishReason = "stop"),
            )
            coEvery { mockToolRegistry.executeFromJson(probeTool, any()) } returns "done"

            val runner = SubagentRunner(
                mockChatService,
                mockToolRegistry,
                mockThreadStore(),
                mockLimiter(),
                mockk(relaxed = true),
                ToolApprovalRouter(),
                mockSettings(SessionPermissionMode.TRUSTED),
            )
            val result = runner.run(SubagentRunner.Params(task = "探针", maxToolCalls = 3))

            coVerify(exactly = 0) { mockToolRegistry.executeFromJson(probeTool, any()) }
            assertFalse(result.progressEntries[0].success)
            assertTrue(
                "应回传参数化硬拒的原因，实际: ${result.progressEntries[0].result}",
                result.progressEntries[0].result.contains("参数化硬拒"),
            )
        } finally {
            // 参数策略是全局注册表:用完立即改回「不拦截」,不给其他测试留副作用
            ParamPolicies.register(probeTool) { null }
        }
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

    // v1.0.53: 会话权限模式 mock — 闸门在无审批通道时读全局默认模式
    private fun mockSettings(mode: SessionPermissionMode): io.zer0.muse.data.SettingsRepository {
        val repo = io.mockk.mockk<io.zer0.muse.data.SettingsRepository>(relaxed = true)
        io.mockk.every { repo.defaultSessionPermissionModeFlow } returns kotlinx.coroutines.flow.flowOf(mode)
        return repo
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

    @Test
    fun `run never offers screen automation tools to subagents`() = runTest {
        // 子代理是「文本工作者」，不该在用户没看屏的时候按 back/home/拉通知栏 ——
        // 与「子 agent 不应操作浏览器」同一条 UI 状态隔离原则。
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("read_file", "read", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            // 下面这些是 NORMAL/SAFE，能穿过「只允许 SAFE+NORMAL」的过滤，必须被显式黑名单挡掉
            ToolRegistry.ToolDef("screen_back", "back", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            ToolRegistry.ToolDef("screen_home", "home", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            ToolRegistry.ToolDef("screen_open_settings", "settings", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            ToolRegistry.ToolDef("screen_permission_status", "status", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        val offered = slot<List<String>>()
        every { mockToolRegistry.listToolsAsToolDefinitions(capture(offered)) } returns emptyList()
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        runner.run(SubagentRunner.Params(task = "整理笔记"))

        val names = offered.captured
        assertTrue("普通只读工具应保留，实际: $names", names.contains("web_search"))
        assertTrue("屏幕自动化工具必须全部剔除，实际: $names", names.none { it.startsWith("screen_") })
    }

    @Test
    fun `a named profile that does not exist is refused instead of silently downgraded`() = runTest {
        val runner = SubagentRunner(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
            settings = null,
            profileResolver = { null },
        )

        val result = runner.run(SubagentRunner.Params(task = "x", profile = "ghost"))

        assertFalse(result.success)
        assertTrue(
            "角色不存在要明确说，否则用户以为角色生效了: ${result.error}",
            result.error?.contains("找不到角色") == true,
        )
    }

    @Test
    fun `unrestricted agent mode exposes automation and screen tools omitted for plain subagents`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("ui_agent", "ui agent", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
            ToolRegistry.ToolDef("automation_workflow", "workflow", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
            ToolRegistry.ToolDef("screen_back", "back", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            ToolRegistry.ToolDef("subagent_run", "recursive", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions() } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
            ToolDefinition("ui_agent", "ui agent", "{}"),
            ToolDefinition("automation_workflow", "workflow", "{}"),
            ToolDefinition("screen_back", "back", "{}"),
            ToolDefinition("subagent_run", "recursive", "{}"),
        )
        val offered = slot<List<ToolDefinition>>()
        coEvery {
            mockChatService.completeText(
                messages = any(),
                model = any(),
                providerConfig = any(),
                temperature = any(),
                maxTokens = any(),
                tools = capture(offered),
                reasoningLevel = any(),
                mode = any(),
            )
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(
            SubagentRunner.Params(
                task = "自动化操作并检查",
                access = "full",
                unrestrictedAgentMode = true,
            ),
        )
        assertTrue(result.success)
        val names = offered.captured.map { it.name }.toSet()
        assertTrue("Agent 模式应包含 ui_agent，实际: $names", "ui_agent" in names)
        assertTrue("Agent 模式应包含 automation_workflow，实际: $names", "automation_workflow" in names)
        assertTrue("Agent 模式应包含 screen_back，实际: $names", "screen_back" in names)
        assertFalse("递归工具仍应禁止，实际: $names", "subagent_run" in names)
    }

    @Test
    fun `unrestricted agent mode narrows large tool pool and preserves find_tools loads across rounds`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        val optionalTools = listOf(
            "get_weather", "set_alarm", "set_timer", "toggle_wifi", "toggle_bluetooth",
            "toggle_flashlight", "set_brightness", "get_brightness", "set_volume", "get_volume",
            "vibrate", "open_app", "open_system_setting", "get_location", "share_text",
            "echo", "translate", "ping_host", "dns_lookup", "generate_uuid",
        )
        val globalTools = listOf("workspace_write", "browser_navigate")
        val allNames = listOf("find_tools", "calculator", "web_search") + optionalTools + globalTools
        every { mockToolRegistry.listTools() } returns allNames.map { name ->
            ToolRegistry.ToolDef(name, "$name desc", emptyMap(), riskLevel = ToolPermissionResolver.riskLevelFor(name))
        }
        every { mockToolRegistry.listToolsAsToolDefinitions() } returns allNames.map { name ->
            ToolDefinition(name, "$name desc", "{}")
        }
        every { mockToolRegistry.getToolRiskLevel("find_tools") } returns ToolRiskLevel.SAFE
        coEvery { mockToolRegistry.executeFromJson("find_tools", any()) } returns
            "Matched 1 tool(s), now available from your next call in this session:\n- workspace_write: write file"

        val offeredPerRound = mutableListOf<List<ToolDefinition>>()
        coEvery {
            mockChatService.completeText(
                messages = any(),
                model = any(),
                providerConfig = any(),
                temperature = any(),
                maxTokens = any(),
                tools = capture(offeredPerRound),
                reasoningLevel = any(),
                mode = any(),
            )
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall("ft-1", "find_tools", """{"query":"工作区"}""")),
            ),
            ChatCompletion(text = "完成", finishReason = "stop"),
        )

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(
            SubagentRunner.Params(
                task = "查一下今天天气",
                access = "full",
                unrestrictedAgentMode = true,
            ),
        )
        assertTrue(result.success)
        assertEquals(2, offeredPerRound.size)

        val round1Names = offeredPerRound[0].map { it.name }.toSet()
        assertTrue("第一轮应经分层收窄显著少于全量(${allNames.size})，实际=${round1Names.size}", round1Names.size < allNames.size)
        assertTrue("第一轮必须保留 find_tools", "find_tools" in round1Names)
        assertTrue("第一轮应包含命中的 get_weather", "get_weather" in round1Names)
        assertFalse("第一轮不应暴露无关全局工具 workspace_write", "workspace_write" in round1Names)
        assertFalse("第一轮不应暴露无关全局工具 browser_navigate", "browser_navigate" in round1Names)

        val round2Names = offeredPerRound[1].map { it.name }.toSet()
assertTrue("find_tools 动态装载的 workspace_write 应在下一轮可见", "workspace_write" in round2Names)
        assertFalse("未装载的 browser_navigate 仍应收窄", "browser_navigate" in round2Names)
    }

    @Test
    fun `standard subagent narrows large tool pool via ToolExposurePolicy and keeps find_tools`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        val safeOptional = listOf(
            "echo", "translate", "ping_host", "dns_lookup", "get_public_ip",
            "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text",
            "generate_uuid", "random_number", "json_pretty", "generate_password",
            "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info",
            "get_sensors_list", "get_brightness", "get_volume",
        )
        val allNames = listOf("find_tools", "calculator", "get_weather") + safeOptional
        every { mockToolRegistry.listTools() } returns allNames.map { name ->
            ToolRegistry.ToolDef(name, "$name desc", emptyMap(), riskLevel = ToolRiskLevel.SAFE)
        }
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } answers {
            firstArg<List<String>>().map { name -> ToolDefinition(name, "$name desc", "{}") }
        }
        val offered = slot<List<ToolDefinition>>()
        coEvery {
            mockChatService.completeText(
                messages = any(),
                model = any(),
                providerConfig = any(),
                temperature = any(),
                maxTokens = any(),
                tools = capture(offered),
                reasoningLevel = any(),
                mode = any(),
            )
        } returns ChatCompletion(text = "晴天", finishReason = "stop")

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
        )
        val result = runner.run(SubagentRunner.Params(task = "北京今天天气怎么样"))
        assertTrue(result.success)
        val names = offered.captured.map { it.name }.toSet()
        assertTrue("标准子代理也应走收窄", names.size < allNames.size)
        assertTrue("标准子代理应保留 find_tools", "find_tools" in names)
        assertTrue("命中天气族应保留 get_weather", "get_weather" in names)
        assertFalse("无关工具 base64_encode 应收窄", "base64_encode" in names)
    }

    @Test
    fun `prompts localize user headers in English and avoid phantom recursive tool warnings`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        val settings = mockSettings(SessionPermissionMode.ASK)
        every { settings.getLanguageSync() } returns "en"

        val sentMessages = slot<List<io.zer0.ai.core.UIMessage>>()
        coEvery {
            mockChatService.completeText(
                messages = capture(sentMessages),
                model = any(),
                providerConfig = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                reasoningLevel = any(),
                mode = any(),
            )
        } returns ChatCompletion(text = "Done", finishReason = "stop")

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
            settings = settings,
        )
        val result = runner.run(
            SubagentRunner.Params(
                task = "Summarize release notes",
                contextText = "Keep under 5 bullets",
            ),
        )
        assertTrue(result.success)
        val sysMsg = sentMessages.captured.first { it.role == io.zer0.ai.core.MessageRole.SYSTEM }.content
        val userMsg = sentMessages.captured.first { it.role == io.zer0.ai.core.MessageRole.USER }.content
        assertFalse("系统提示不应再提及未暴露的幽灵工具 subagent_run", sysMsg.contains("subagent_run"))
        assertFalse("系统提示不应再提及未暴露的幽灵工具 delegate_agent", sysMsg.contains("delegate_agent"))
        assertTrue("英文模式下 user 消息应使用英文标题", userMsg.contains("## Task"))
        assertTrue("英文模式下约束段应使用英文标题", userMsg.contains("## Constraints / Acceptance Criteria"))
    }

    @Test
    fun `agent worker system prompt enforces resourceScope verifiable evidence and tools boundary`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("find_tools", "find", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("read_file", "read", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions() } returns listOf(
            ToolDefinition("find_tools", "find", "{}"),
            ToolDefinition("read_file", "read", "{}"),
        )
        val settings = mockSettings(SessionPermissionMode.ASK)
        every { settings.getLanguageSync() } returns "zh"

        val sentMessages = slot<List<io.zer0.ai.core.UIMessage>>()
        coEvery {
            mockChatService.completeText(
                messages = capture(sentMessages),
                model = any(),
                providerConfig = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                reasoningLevel = any(),
                mode = any(),
            )
        } returns ChatCompletion(text = "完成", finishReason = "stop")

        val runner = SubagentRunner(
            mockChatService,
            mockToolRegistry,
            mockThreadStore(),
            mockLimiter(),
            mockk(relaxed = true),
            ToolApprovalRouter(),
            settings = settings,
        )
        runner.run(
            SubagentRunner.Params(
                task = "检查笔记目录",
                unrestrictedAgentMode = true,
                toolExecutionContext = ToolExecutionContext(
                    scope = "main",
                    spaceId = "default",
                    resourceScope = "notes/",
                    isSubagentContext = true,
                ),
            ),
        )
        val sysMsg = sentMessages.captured.first { it.role == io.zer0.ai.core.MessageRole.SYSTEM }.content
        assertTrue("应显式注入 resourceScope", sysMsg.contains("notes/"))
        assertTrue("应要求提供可核验证据", sysMsg.contains("可核验"))
        assertFalse("不应宣称拥有全部未暴露工具", sysMsg.contains("全部本地工具"))
    }

    @Test
    fun `SubagentRunSkill toolDef declares access and profile parameters`() {
        val def = SubagentRunSkill.toolDef()
        assertTrue("schema 应声明 access 参数", "access" in def.parameters)
        assertTrue("schema 应声明 profile 参数", "profile" in def.parameters)
    }
}
