package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 2.2 spike：find_tools 装载后能否在**同一用户回合的下一轮**生效。
 *
 * ## 背景
 *
 * 助手自审第 1 条：「工具不在本轮 schema 里就得先 find_tools 再等下一轮 —— 一多轮跳转，
 * 中间一旦用户又发一句，前面积累的上下文容易断。理想是『检索即装载，同轮可用』。」
 *
 * ## spike 要回答的两个问题
 *
 * 1. `markLoaded` 写入后，同一次 `runLoop` 的下一轮会不会重新读取？
 * 2. 重新读取后，`filterToolsForRequest` 会不会真的放行？
 *
 * ## 结论（本测试即证据）
 *
 * 问题 1：**会**。`ChatToolLoopRunner.requestRoundFlow` 在每一轮请求前都调
 * `SessionToolLoadRegistry.loadedFor(sessionId)`，而它在工具循环内每轮被调用一次。
 * 所以「下一轮」= 同一个用户回合内的下一次模型请求，不是「用户再发一句」。
 *
 * 问题 2：**会**。`dynamicAllowed` 包含 `loadedToolNames`，OPTIONAL/GLOBAL 工具
 * 只要被装载就放行。
 *
 * 因此「同轮可用」在当前架构下**已经成立**（同轮 = 同一次用户输入的后续工具轮）。
 * 真正的限制是：模型在**同一次**模型输出里不能既调 find_tools 又调刚检索到的工具 ——
 * 那是 provider 协议决定的（tool_calls 在响应返回时已定），不是 Muse 能改的。
 *
 * 这个测试钉住上述结论，防止后续重构把「每轮重读」这个前提改掉。
 *
 * 注意：必须用**真正属于 OPTIONAL** 的工具名。用 STANDARD/CORE 的工具会让测试失去
 * 验证力 —— 它们本来就恒发，装载前也可见，断言「装载前不可见」会因为工具不在测试集里
 * 而假通过。
 */
class FindToolsSameTurnSpikeTest {

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = """{"type":"object","properties":{}}""",
    )

    /** 两个真正的 OPTIONAL 工具（收窄后才可见），外加填充项把工具面撑过阈值。 */
    private val optionalA = "set_alarm"
    private val optionalB = "toggle_flashlight"

    private fun largeToolSet(): List<ToolDefinition> = (1..30).map { tool("filler_tool_$it") } +
        listOf(tool(optionalA), tool(optionalB))

    init {
        // 前置断言：选错分类会让整组测试失去意义，所以在类初始化时就挡住。
        require(optionalA in ToolCategories.OPTIONAL) { "$optionalA 必须属于 OPTIONAL" }
        require(optionalB in ToolCategories.OPTIONAL) { "$optionalB 必须属于 OPTIONAL" }
    }

    @Test
    fun `loaded tool is visible on the next round even though the user said nothing new`() {
        val tools = largeToolSet()
        // 用户原话不含闹钟/手电筒关键词 → 收窄后这两个 OPTIONAL 工具本不该出现
        val userText = "帮我处理一下这个"

        val beforeLoad = ToolExposurePolicy.filterToolsForRequest(userText = userText, tools = tools)
        assertFalse(
            "装载前不该可见（否则这个测试没有验证力）",
            beforeLoad.any { it.name == optionalA },
        )

        val afterLoad =
            ToolExposurePolicy.filterToolsForRequest(
                userText = userText,
                tools = tools,
                loadedToolNames = setOf(optionalA),
            )
        assertTrue(
            "装载后同一句用户输入下，下一轮必须能看到它 —— 这就是「同轮可用」",
            afterLoad.any { it.name == optionalA },
        )
    }

    @Test
    fun `loading one tool does not widen the whole schema`() {
        val tools = largeToolSet()

        val afterLoad =
            ToolExposurePolicy.filterToolsForRequest(
                userText = "帮我处理一下这个",
                tools = tools,
                loadedToolNames = setOf(optionalA),
            )

        assertFalse(
            "只该放行被装载的那个，不能顺带把别的 OPTIONAL 工具也放出来",
            afterLoad.any { it.name == optionalB },
        )
    }

    @Test
    fun `loaded tools accumulate across rounds`() {
        val tools = largeToolSet()
        val sessionId = "spike-session"
        SessionToolLoadRegistry.clear(sessionId)

        SessionToolLoadRegistry.markLoaded(sessionId, listOf(optionalA))
        val round2 =
            ToolExposurePolicy.filterToolsForRequest(
                "帮我处理一下这个",
                tools,
                loadedToolNames = SessionToolLoadRegistry.loadedFor(sessionId),
            )
        assertTrue(round2.any { it.name == optionalA })

        SessionToolLoadRegistry.markLoaded(sessionId, listOf(optionalB))
        val round3 =
            ToolExposurePolicy.filterToolsForRequest(
                "帮我处理一下这个",
                tools,
                loadedToolNames = SessionToolLoadRegistry.loadedFor(sessionId),
            )
        assertTrue("先前装载的不能被后来的覆盖", round3.any { it.name == optionalA })
        assertTrue(round3.any { it.name == optionalB })

        SessionToolLoadRegistry.clear(sessionId)
    }

    @Test
    fun `small tool surface is never narrowed so loading is irrelevant`() {
        val small = listOf(tool("a"), tool("b"))

        val result = ToolExposurePolicy.filterToolsForRequest("随便说点什么", small)

        assertTrue("小工具面直接全发，装载与否不影响", result.size == small.size)
    }

    @Test
    fun `global tool is not visible until loaded`() {
        // GLOBAL 类只在 dynamicAllowed 里放行，装载前后差异最明显
        val globalTool = "app_uninstall"
        require(globalTool in ToolCategories.GLOBAL) { "$globalTool 必须属于 GLOBAL" }
        val tools = (1..30).map { tool("filler_$it") } + tool(globalTool)

        val before = ToolExposurePolicy.filterToolsForRequest("帮我处理一下这个", tools)
        assertFalse(before.any { it.name == globalTool })

        val after =
            ToolExposurePolicy.filterToolsForRequest(
                "帮我处理一下这个",
                tools,
                loadedToolNames = setOf(globalTool),
            )
        assertTrue(after.any { it.name == globalTool })
    }
}
