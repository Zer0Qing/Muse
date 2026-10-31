package io.zer0.muse.tools

import io.zer0.muse.data.plugin.PluginManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 动态注册工具的风险来源。
 *
 * 插件工具 / MCP 工具的名字由第三方自取。风险等级不能再从名字推断:
 * 名字里带 `get_` / `read_` 不代表只读,把外发调用命名成 `get_diagnostics` 就能在
 * ASK 模式下免审。这里钉住三件事:
 *  1. 听起来无害的插件工具判 NORMAL(ASK 下弹审批),不再拿 SAFE;
 *  2. 听起来危险的插件工具也不会被名字抬到另一条链路,同样按保守默认走;
 *  3. 内置工具的等级声明不受这次收紧影响。
 */
class DynamicToolRiskTest {

    /** 插件工具的真实注册形态:PluginManager.skillId(pluginId, toolName)。 */
    private fun pluginToolId(toolName: String): String = PluginManager.skillId("demo", toolName)

    /** MCP 工具的真实注册形态:McpRegistry.registerTools 里的 `mcp_{serverId}__{tool}`。 */
    private fun mcpToolId(toolName: String): String = "mcp_demo__$toolName"

    @Test
    fun `plugin tool with harmless name is NORMAL not SAFE`() {
        listOf("get_diagnostics", "read_status", "get_info", "list_everything", "search_outbox").forEach { name ->
            val id = pluginToolId(name)
            assertEquals("$id 不能判 SAFE(ASK 下等于免审)", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id 在 ASK 模式下必须等待审批",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, null, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `plugin tool is not trusted through a SAFE registration ledger value`() {
        // 注册台账(ToolDef.riskLevel)的缺省值就是 SAFE。第三方工具一旦漏声明,
        // 旧链路会把台账里的 SAFE 直接当判定结果 → ASK 下 Auto。这里必须回落 NORMAL。
        listOf("get_diagnostics", "read_status").forEach { name ->
            val id = pluginToolId(name)
            assertEquals(
                "$id 的台账 SAFE 不得被直接采信",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `plugin tool with dangerous sounding name is not downgraded to SAFE`() {
        listOf("send_email", "delete_account", "execute_shell", "wire_transfer").forEach { name ->
            val id = pluginToolId(name)
            assertEquals("$id 不能被降级成 SAFE", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id 在 ASK 模式下必须等待审批",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `legacy plugin tool ids are also never SAFE`() {
        // 长度前缀编码之前的旧 id 形态(plugin_<pluginId>_<tool>),历史安装里仍可能存在。
        val legacy = PluginManager.legacySkillId("demo", "get_diagnostics")
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(legacy))
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(legacy, null, SessionPermissionMode.ASK, null),
        )
    }

    @Test
    fun `mcp server tools follow the same conservative default`() {
        listOf("get_diagnostics", "read_status", "info").forEach { name ->
            val id = mcpToolId(name)
            assertEquals("$id 不能判 SAFE", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id 在 ASK 模式下必须等待审批",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `built-in tool levels are untouched by the conservative default`() {
        // 名字推断对内置工具照旧生效:这些名字写死在代码里,工具作者没有控制权。
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("web_search"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("read_file"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("get_current_time"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("write_file"))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor("send_email"))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor("open_url"))

        // 内置的插件市场 / MCP 管理工具共用前缀,但不是第三方工具,等级照旧。
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("plugin_market_search"))
        assertFalse(ToolPermissionResolver.isThirdPartyToolName("plugin_market_search"))
        assertFalse(ToolPermissionResolver.isThirdPartyToolName("mcp_mgmt_list"))
        // mcp_mgmt_* 不在显式风险表里,走到“未知工具 → NORMAL”是本次改动前就有的口径(未变)。
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("mcp_mgmt_list"))

        // 内置 SAFE 工具在 ASK 模式下仍然免审,不被这次收紧波及。
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("mcp_mgmt_list", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("plugin_market_search", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
    }
}
