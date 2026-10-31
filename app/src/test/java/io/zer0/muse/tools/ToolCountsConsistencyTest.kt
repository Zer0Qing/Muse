package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三档工具计数与真实消费面的一致性。
 *
 * 第一档必须等于 ChatStreamCoordinator 下发的 schema 工具数，第二档必须等于 find_tools
 * 空查询的条数，第三档等于本地注册总数。另外用「改动前的过滤实现」作为准绳逐条比对，
 * 保证这次收敛只统一了口径，没有改动过滤结果。
 */
class ToolCountsConsistencyTest {

    private fun localTool(name: String, category: String = "built-in") = ToolRegistry.ToolDef(
        name = name,
        description = "$name desc",
        parameters = mapOf("p" to "param"),
        required = setOf("p"),
        category = category,
        riskLevel = ToolPermissionResolver.riskLevelFor(name),
    )

    private fun aiDef(name: String) = ToolDefinition(
        name = name,
        description = "$name desc",
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
    )

    private fun skill(id: String) = SkillEntity(id = id, name = id, description = "$id desc")

    /** 本地注册表（含 find_tools 与两个 MCP 服务的工具）。 */
    private val registered = listOf(
        localTool("find_tools"),
        localTool("browser_click", "phone"),
        localTool("workspace_write", "file"),
        localTool("generate_image", "system"),
        localTool("mcp_sv1__search", "mcp"),
        localTool("mcp_sv2__read", "mcp"),
    )

    /** 各助手启用的技能：含一个与本地工具同名的、一个 channel_ 前缀的。 */
    private val skills = listOf(
        skill("generate_image"),
        skill("channel_broadcast"),
        skill("my_skill"),
        skill("other_skill"),
        skill("my_skill"),
    )

    /** 本会话登记的 find_tools 可检索技能目录（含 find_tools 自身以外的条目）。 */
    private val sessionSkillDefs = listOf(localTool("my_skill", "skill"), localTool("other_skill", "skill"))

    // ── 改动前的过滤实现（准绳，不参与生产代码） ─────────────────────

    private fun legacyLocalDefs(
        configuredToolIds: Set<String>,
        mcpServerIds: Set<String>,
        denied: Set<String>,
    ): List<ToolDefinition> = registered.map { aiDef(it.name) }.filter { definition ->
        val notDenied = definition.name !in denied
        val selectedByTool =
            configuredToolIds.isEmpty() || definition.name in configuredToolIds ||
                definition.name == FindToolsTool.TOOL_NAME
        val isMcpTool = definition.name.startsWith("mcp_") && definition.name.contains("__")
        val selectedByMcp = !isMcpTool || mcpServerIds.any { s -> definition.name.startsWith("mcp_${s}__") }
        notDenied && selectedByTool && selectedByMcp
    }

    private fun legacySkills(configuredSkillIds: Set<String>): List<SkillEntity> {
        val localToolNames = registered.map { it.name }.toSet()
        return skills
            .distinctBy { it.id }
            .filterNot { it.id in localToolNames || it.id.startsWith("channel_") }
            .filter { configuredSkillIds.isEmpty() || it.id in configuredSkillIds }
    }

    /** 场景矩阵：覆盖空/非空白名单、MCP 绑定、ALWAYS_DENY。 */
    private val scenarios = listOf(
        ToolSchemaRules.Criteria(),
        ToolSchemaRules.Criteria(configuredToolIds = setOf("browser_click", "workspace_write")),
        ToolSchemaRules.Criteria(mcpServerIds = setOf("sv1")),
        ToolSchemaRules.Criteria(deniedToolNames = setOf("browser_click", "my_skill")),
        ToolSchemaRules.Criteria(
            configuredToolIds = setOf("browser_click"),
            mcpServerIds = setOf("sv2"),
            configuredSkillIds = setOf("my_skill"),
            deniedToolNames = setOf("workspace_write"),
        ),
    )

    @Test
    fun `shared rules keep the pre-change filtering result item by item`() {
        scenarios.forEach { criteria ->
            val expectedLocal =
                legacyLocalDefs(criteria.configuredToolIds, criteria.mcpServerIds, criteria.deniedToolNames)
            val actualLocal = ToolSchemaRules.selectLocalTools(registered.map { aiDef(it.name) }, criteria)
            assertEquals("本地工具顺序与内容必须逐一相同", expectedLocal, actualLocal)

            val expectedSkills = legacySkills(criteria.configuredSkillIds)
            val actualSkills =
                ToolSchemaRules.selectSkills(skills, registered.map { it.name }.toSet(), criteria)
            assertEquals("技能顺序与内容必须逐一相同", expectedSkills.map { it.id }, actualSkills.map { it.id })
        }
    }

    @Test
    fun `callable count equals the schema actually sent to the model`() {
        scenarios.forEach { criteria ->
            val counts = ToolCounter.compute(
                ToolCounter.Input(
                    localToolDefs = registered.map { aiDef(it.name) },
                    registeredTools = registered,
                    sessionSkillDefs = sessionSkillDefs,
                    sessionVisibleNames = null,
                    enabledSkills = skills,
                    criteria = criteria,
                ),
            )
            // ChatStreamCoordinator 的下发路径：选出的本地工具 + 技能经 RouteTable 去重后的 definitions
            val outgoing =
                RouteTable.snapshot(
                    legacyLocalDefs(criteria.configuredToolIds, criteria.mcpServerIds, criteria.deniedToolNames),
                    legacySkills(criteria.configuredSkillIds),
                ).definitions
            assertEquals("第一档必须等于实际下发的 schema 工具数", outgoing.size, counts.callableInSchema)
            assertEquals("第三档为本地注册总数", registered.size, counts.registeredTotal)
        }
    }

    @Test
    fun `session visible count equals find_tools empty query listing`() {
        val visibleNames = setOf("find_tools", "browser_click", "my_skill", "other_skill")
        val counts = ToolCounter.compute(
            ToolCounter.Input(
                localToolDefs = registered.map { aiDef(it.name) },
                registeredTools = registered,
                sessionSkillDefs = sessionSkillDefs,
                sessionVisibleNames = visibleNames,
                enabledSkills = skills,
                criteria = ToolSchemaRules.Criteria(),
            ),
        )
        // find_tools 空查询的渲染路径：检索目录经同名规则收窄后交给 fullList
        val searchable =
            ToolSchemaRules.visibleSubset(registered + sessionSkillDefs, visibleNames)
        val header = FindToolsTool.fullList(searchable)
        assertTrue(
            "第二档必须等于 find_tools 空查询的条数：$header",
            header.contains("${counts.sessionVisible} tools total"),
        )
        assertTrue("find_tools 自身不计入", counts.sessionVisible == visibleNames.size - 1)
        assertTrue("会话可见档不含未登记技能", counts.sessionVisible < counts.registeredTotal + sessionSkillDefs.size)
    }

    @Test
    fun `three tiers stay distinct and ordered as designed`() {
        val counts = ToolCounter.compute(
            ToolCounter.Input(
                localToolDefs = registered.map { aiDef(it.name) },
                registeredTools = registered,
                sessionSkillDefs = sessionSkillDefs,
                sessionVisibleNames = null,
                enabledSkills = skills,
                criteria = ToolSchemaRules.Criteria(deniedToolNames = setOf("browser_click")),
            ),
        )
        // 空 MCP 绑定会剔掉两个 mcp_ 工具，禁用再剔一个，技能只进不与本地工具同名的两个
        assertEquals(3 + 2, counts.callableInSchema)
        assertEquals("未登记可见集时不做收窄（扣掉 find_tools 自身）", 7, counts.sessionVisible)
        assertEquals(6, counts.registeredTotal)
    }

    @Test
    fun `registry entry uses the same numbers as the pure counter`() {
        val counts =
            ToolCounter.compute(
                ToolCounter.Input(
                    localToolDefs = registered.map { aiDef(it.name) },
                    registeredTools = registered,
                    sessionSkillDefs = sessionSkillDefs,
                    sessionVisibleNames = setOf("find_tools", "my_skill"),
                    enabledSkills = skills,
                    criteria = ToolSchemaRules.Criteria(),
                ),
            )
        assertEquals(4 + 2, counts.callableInSchema)
        assertEquals(1, counts.sessionVisible)
    }
}
