package io.zer0.muse.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2.3.5: ToolOrchestrator 纯函数单元测试。
 *
 * 覆盖范围(可见性已放宽为 internal 以便测试):
 *  - [ToolOrchestrator.extractWebSearchUrls] — 从 web_search 结果中提取 URL
 *  - [ToolOrchestrator.toolCallSignature] — 卡死检测签名(顺序无关)
 *  - [ToolOrchestrator.mergeToolArguments] — 审批阶段参数覆盖合并
 *  - [ToolOrchestrator.parseTaskPlanStepCount] — task_plan arguments 解析容错
 *  - [ToolOrchestrator.countTaskPlanSteps] — 多源(AgentPlan 缓存 / 历史消息)汇总
 *  - [ToolOrchestrator.computeMaxRounds] — 动态轮次计算 + hardCap 兜底
 *
 * 注:仅测纯函数,不涉及 runLoop 的协程流程(后续可单独补 integration test)。
 * runLoop 涉及 ChatViewModel/Provider/ToolRegistry 多个组件,需要 mockk 整条链,
 * 投入产出比低,优先保证纯函数正确性。
 *
 * 运行方式: `./gradlew :app:testDebugUnitTest --tests "*ToolOrchestratorPureFunctionsTest*"`
 */
class ToolOrchestratorPureFunctionsTest {

    private lateinit var mockSkillExecutor: SkillExecutor
    private lateinit var orchestrator: ToolOrchestrator

    @Before
    fun setUp() {
        mockSkillExecutor = mockk(relaxed = true)
        // 纯函数测试:除 skillExecutor 外的依赖都不会被调用,直接 relaxed mock
        orchestrator = ToolOrchestrator(
            toolRegistry = mockk(relaxed = true),
            skillRepository = mockk(relaxed = true),
            skillExecutor = mockSkillExecutor,
            assistantRepository = mockk(relaxed = true),
            sessionRepository = mockk(relaxed = true),
            context = mockk<Context>(relaxed = true),
        )
    }

    // ── extractWebSearchUrls ─────────────────────────────────────────────

    @Test
    fun `extractWebSearchUrls extracts single URL line`() {
        val result = """
            搜索结果:
            URL: https://example.com/article1
            摘要: 示例文章
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://example.com/article1"), urls)
    }

    @Test
    fun `extractWebSearchUrls extracts multiple URLs in order`() {
        val result = """
            URL: https://a.com/1
            URL: https://b.com/2
            URL: https://c.com/3
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(
            listOf("https://a.com/1", "https://b.com/2", "https://c.com/3"),
            urls,
        )
    }

    @Test
    fun `extractWebSearchUrls handles leading whitespace`() {
        val result = "    URL:   https://spaced.com/path"
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://spaced.com/path"), urls)
    }

    @Test
    fun `extractWebSearchUrls returns empty list when no URL lines`() {
        val result = """
            搜索完成,未找到相关结果。
            建议换用其他关键词。
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertTrue("无 URL 行时应返回空列表", urls.isEmpty())
    }

    @Test
    fun `extractWebSearchUrls ignores lines without URL prefix`() {
        val result = """
            URL: https://valid.com/1
            NOT-URL: https://invalid.com/x
            普通 URL: https://also-invalid.com/y
            URL:https://valid.com/2
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        // 正则 ^\s*URL:\s*(.+)$ 要求行首(允许前导空白),严格匹配 "URL:" 前缀
        // 第 4 行 "URL:https..." 中间无空格,仍应匹配(正则 \s* 允许 0 个空格)
        assertEquals(
            "应只匹配行首的 'URL:' 前缀(忽略大小写敏感:正则未加 IGNORE_CASE,严格大写)",
            listOf("https://valid.com/1", "https://valid.com/2"),
            urls,
        )
    }

    @Test
    fun `extractWebSearchUrls handles empty input`() {
        assertTrue(orchestrator.extractWebSearchUrls("").isEmpty())
    }

    @Test
    fun `extractWebSearchUrls preserves query string and fragment`() {
        val result = "URL: https://example.com/path?q=hello&lang=en#section"
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://example.com/path?q=hello&lang=en#section"), urls)
    }

    // ── toolCallSignature ───────────────────────────────────────────────

    @Test
    fun `toolCallSignature returns empty string for empty list`() {
        assertEquals("", orchestrator.toolCallSignature(emptyList()))
    }

    @Test
    fun `toolCallSignature returns single call signature`() {
        val tc = ToolCall(id = "call_1", name = "web_search", arguments = """{"q":"kotlin"}""")
        assertEquals(
            """web_search({"q":"kotlin"})""",
            orchestrator.toolCallSignature(listOf(tc)),
        )
    }

    @Test
    fun `toolCallSignature sorts by id ascending`() {
        val tc1 = ToolCall(id = "call_3", name = "c", arguments = "{}")
        val tc2 = ToolCall(id = "call_1", name = "a", arguments = "{}")
        val tc3 = ToolCall(id = "call_2", name = "b", arguments = "{}")
        val sig = orchestrator.toolCallSignature(listOf(tc1, tc2, tc3))
        // 应按 id 排序: call_1 → call_2 → call_3, 即 a|b|c
        assertEquals("""a({})|b({})|c({})""", sig)
    }

    @Test
    fun `toolCallSignature is order-independent for same set`() {
        val tc1 = ToolCall(id = "call_1", name = "a", arguments = "{}")
        val tc2 = ToolCall(id = "call_2", name = "b", arguments = "{}")
        val sigOrder1 = orchestrator.toolCallSignature(listOf(tc1, tc2))
        val sigOrder2 = orchestrator.toolCallSignature(listOf(tc2, tc1))
        // 同集合不同顺序应得相同签名(用于卡死检测,避免并行工具顺序抖动误判)
        assertEquals(
            "同集合不同顺序应得相同签名(避免并行工具顺序抖动误判卡死)",
            sigOrder1,
            sigOrder2,
        )
    }

    @Test
    fun `toolCallSignature distinguishes different arguments`() {
        val tc1 = ToolCall(id = "c1", name = "search", arguments = """{"q":"a"}""")
        val tc2 = ToolCall(id = "c1", name = "search", arguments = """{"q":"b"}""")
        // 注: id 相同虽非正常场景,但用于验证 arguments 不同则签名不同
        val sig1 = orchestrator.toolCallSignature(listOf(tc1))
        val sig2 = orchestrator.toolCallSignature(listOf(tc2))
        assertTrue(
            "参数不同时签名应不同(用于判定是否卡死)",
            sig1 != sig2,
        )
    }

    // ── mergeToolArguments ──────────────────────────────────────────────

    /** 解析 merged JSON 并返回指定字段的字符串值,断言失败时给出清晰错误。 */
    private fun jsonStringField(json: String, key: String): String? {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), json)
        return obj[key]?.let { (it as? JsonPrimitive)?.content }
    }

    @Test
    fun `mergeToolArguments returns original when overrides empty`() {
        val original = """{"q":"kotlin","limit":10}"""
        val merged = orchestrator.mergeToolArguments(original, emptyMap())
        assertEquals(original, merged)
    }

    @Test
    fun `mergeToolArguments overrides existing key`() {
        val original = """{"q":"kotlin","limit":10}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("limit" to "5"))
        assertEquals("limit 应被覆盖为 5", "5", jsonStringField(merged, "limit"))
        assertEquals("q 应保持 kotlin", "kotlin", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments adds new key when not present`() {
        val original = """{"q":"kotlin"}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("lang" to "zh"))
        assertEquals("新增 lang=zh", "zh", jsonStringField(merged, "lang"))
        assertEquals("原 q 保留", "kotlin", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments handles multiple overrides`() {
        val original = """{"q":"kotlin"}"""
        val merged = orchestrator.mergeToolArguments(
            original,
            mapOf("limit" to "5", "lang" to "zh", "safe" to "true"),
        )
        assertEquals("kotlin", jsonStringField(merged, "q"))
        assertEquals("5", jsonStringField(merged, "limit"))
        assertEquals("zh", jsonStringField(merged, "lang"))
        assertEquals("true", jsonStringField(merged, "safe"))
    }

    @Test
    fun `mergeToolArguments falls back to overrides only when original invalid`() {
        val invalidOriginal = "not a valid json"
        val merged = orchestrator.mergeToolArguments(
            invalidOriginal,
            mapOf("q" to "fallback"),
        )
        // 原始 JSON 解析失败时,仅用 overrides 构造新 JSON
        assertEquals(
            "原始 JSON 非法时应仅用 overrides 构造新 JSON",
            "fallback",
            jsonStringField(merged, "q"),
        )
    }

    @Test
    fun `mergeToolArguments handles empty original json`() {
        val merged = orchestrator.mergeToolArguments("{}", mapOf("q" to "value"))
        assertEquals("value", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments preserves non-overridden keys from original`() {
        val original = """{"a":"1","b":"2","c":"3"}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("b" to "X"))
        assertEquals("1", jsonStringField(merged, "a"))
        assertEquals("X", jsonStringField(merged, "b"))
        assertEquals("3", jsonStringField(merged, "c"))
    }

    // ── parseTaskPlanStepCount ──────────────────────────────────────────

    @Test
    fun `parseTaskPlanStepCount returns count for valid steps array`() {
        val json = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"}]}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(3, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns zero for empty steps array`() {
        val json = """{"steps":[]}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(0, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns zero when steps field missing`() {
        val json = """{"other":"value"}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        // 注: 源码逻辑,obj["steps"] 为 null 时 return 0(而不是 null)
        assertEquals("无 steps 字段应返回 0", 0, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns null for invalid json`() {
        val json = """not a json"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertNull("非法 JSON 应返回 null(容错)", count)
    }

    @Test
    fun `parseTaskPlanStepCount returns null for empty string`() {
        val count = orchestrator.parseTaskPlanStepCount("")
        assertNull("空字符串应返回 null", count)
    }

    @Test
    fun `parseTaskPlanStepCount handles steps with complex objects`() {
        val json = """
            {
              "steps": [
                {"title": "搜索", "description": "...", "status": "PENDING"},
                {"title": "总结", "description": "...", "status": "PENDING"}
              ]
            }
        """.trimIndent()
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(2, count)
    }

    // ── countTaskPlanSteps ──────────────────────────────────────────────

    @Test
    fun `countTaskPlanSteps uses active plans from skillExecutor when available`() {
        // SkillExecutor 内存中有活跃计划时优先使用
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "plan1" to AgentPlan(
                id = "plan1",
                title = "P1",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                    AgentPlanStep(id = "s3", title = "c"),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(emptyList())
        assertEquals("应优先用 SkillExecutor 的活跃计划步骤数", 3, count)
    }

    @Test
    fun `countTaskPlanSteps sums multiple active plans`() {
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "plan1" to AgentPlan(
                id = "plan1",
                title = "P1",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
            "plan2" to AgentPlan(
                id = "plan2",
                title = "P2",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(emptyList())
        assertEquals("多个活跃计划的步骤数应累加", 3, count)
    }

    @Test
    fun `countTaskPlanSteps falls back to history messages when no active plans`() {
        // SkillExecutor 无活跃计划时,从历史消息中解析 task_plan 工具调用
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("无活跃计划时应回退到历史消息解析", 4, count)
    }

    @Test
    fun `countTaskPlanSteps takes max step count across multiple task_plan calls`() {
        // 多轮 task_plan 工具调用时取最大值(对应"最大计划复杂度")
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"}]}""",
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc2",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("多轮 task_plan 应取最大步骤数", 3, count)
    }

    @Test
    fun `countTaskPlanSteps ignores non-task_plan tool calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = "tc1", name = "web_search", arguments = """{"q":"a"}"""),
                    ToolCall(id = "tc2", name = "read_file", arguments = """{"path":"x"}"""),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("非 task_plan 工具调用不应被计入", 0, count)
    }

    @Test
    fun `countTaskPlanSteps ignores user role messages with tool_calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        // 即使用户消息携带 tool_calls 字段(理论不该发生),也不应被计入
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("仅 ASSISTANT 角色的 task_plan 应被计入", 0, count)
    }

    @Test
    fun `countTaskPlanSteps returns zero when no plans and no task_plan calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "hi"),
            UIMessage(role = MessageRole.ASSISTANT, content = "hello"),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals(0, count)
    }

    // ── computeMaxRounds ────────────────────────────────────────────────

    @Test
    fun `computeMaxRounds returns default when no plan steps`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "无 task_plan 时应使用 DEFAULT_MAX_TOOL_ROUNDS",
            DEFAULT_MAX_TOOL_ROUNDS,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds returns steps_times_2_plus_5 for small plans`() {
        // 3 步 → 3*2+5 = 11,未超过 HARD_CAP(25)
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                    AgentPlanStep(id = "s3", title = "c"),
                ),
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(11, maxRounds)
    }

    @Test
    fun `computeMaxRounds is capped by MAX_TOOL_ROUNDS_HARD_CAP`() {
        // 步骤数足够多时,公式结果应被 HARD_CAP(25) 截断
        // 30 步 → 30*2+5 = 65,但应被截为 25
        val manySteps = List(30) { idx ->
            AgentPlanStep(id = "s$idx", title = "step-$idx")
        }
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(id = "p1", title = "T", steps = manySteps),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "公式结果超过 HARD_CAP 时应被截断为 ${MAX_TOOL_ROUNDS_HARD_CAP}",
            MAX_TOOL_ROUNDS_HARD_CAP,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds respects caller-provided hardCap below HARD_CAP`() {
        // 调用方传入 hardCap 小于计算值时,以 hardCap 为准(向后兼容 params.maxRounds)
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val hardCap = 3
        val maxRounds = orchestrator.computeMaxRounds(emptyList(), hardCap = hardCap)
        assertEquals(
            "hardCap 小于默认值时应取 hardCap",
            hardCap,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds uses larger of formula and hardCap when hardCap exceeds HARD_CAP`() {
        // 调用方传入 hardCap 大于 HARD_CAP 时,仍受 HARD_CAP 限制(防死循环兜底)
        // 注:无 task_plan 时 base=DEFAULT_MAX_TOOL_ROUNDS(10),min(10, 25, 100)=10
        // 此处验证 hardCap > HARD_CAP 时不会被 hardCap 反向放大到 100
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val maxRounds = orchestrator.computeMaxRounds(
            emptyList(),
            hardCap = 100, // 远超 HARD_CAP
        )
        assertEquals(
            "hardCap 超过 HARD_CAP 时不应被反向放大,以 base 和 HARD_CAP 中较小者为限",
            minOf(DEFAULT_MAX_TOOL_ROUNDS, MAX_TOOL_ROUNDS_HARD_CAP),
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds uses history task_plan when no active plans`() {
        // 活跃计划为空但历史消息含 task_plan 工具调用时,应基于历史步骤数计算
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"},{"title":"e"}]}""",
                    ),
                ),
            ),
        )
        // 5 步 → 5*2+5 = 15
        val maxRounds = orchestrator.computeMaxRounds(messages)
        assertEquals(15, maxRounds)
    }

    @Test
    fun `computeMaxRounds prefers active plans over history`() {
        // 同时有活跃计划和历史 task_plan 时,优先用活跃计划
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"},{"title":"e"}]}""",
                    ),
                ),
            ),
        )
        // 活跃计划 1 步 → 1*2+5 = 7,历史 5 步 → 5*2+5 = 15
        // 应优先用活跃计划:7
        val maxRounds = orchestrator.computeMaxRounds(messages)
        assertEquals(
            "同时存在时应优先用 SkillExecutor 活跃计划而非历史消息",
            7,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds boundary - single step plan yields 7 rounds`() {
        // 边界:1 步 → 1*2+5 = 7
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(7, maxRounds)
    }

    @Test
    fun `computeMaxRounds boundary - ten step plan yields hard cap`() {
        // 边界:10 步 → 10*2+5 = 25,恰好等于 HARD_CAP,不应溢出
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = List(10) { idx -> AgentPlanStep(id = "s$idx", title = "step-$idx") },
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "10 步 → 10*2+5=25,恰为 HARD_CAP,无溢出",
            25,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds boundary - eleven step plan yields hard cap not 27`() {
        // 边界:11 步 → 11*2+5 = 27,但应被截为 HARD_CAP=25
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = List(11) { idx -> AgentPlanStep(id = "s$idx", title = "step-$idx") },
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "11 步 → 27,应被截为 HARD_CAP=25",
            25,
            maxRounds,
        )
    }

    @Test
    fun `tool timeout constant is 120 seconds`() {
        assertEquals(120_000L, TOOL_TIMEOUT_MS)
    }
}
