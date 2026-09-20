package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认助手能看到哪些内置工具的回归测试。
 *
 * [ToolRegistry.BUILT_IN_TOOL_IDS] 同时是「新装助手的能力集」与「老用户助手的补全来源」
 * （见 AssistantRepository 的迁移逻辑），所以漏登记会让某类工具在聊天里彻底不可见——
 * 曾经 MCP 管理工具就是这样：registry 里注册了，但不在内置列表里，默认助手看不到。
 */
class BuiltInToolIdsTest {

    @Test
    fun mcpManagementToolsAreExposedToTheDefaultAssistant() {
        // 与 McpRegistry.registerManagementTools 注册的名字一一对应。
        val mcpManagementTools = listOf(
            "mcp_server_list",
            "mcp_server_configure",
            "mcp_server_remove",
            "mcp_server_bind_assistant",
            "mcp_server_reconnect",
        )

        mcpManagementTools.forEach { tool ->
            assertTrue(
                "内置工具列表缺少 $tool，默认助手在聊天里将无法调用它",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(tool),
            )
        }
    }

    @Test
    fun builtInToolIdsAreUniqueAndNonBlank() {
        val ids = ToolRegistry.BUILT_IN_TOOL_IDS
        assertTrue("内置工具列表不应有空白 id", ids.none { it.isBlank() })
        assertTrue("内置工具列表不应有重复 id", ids.distinct().size == ids.size)
    }
}
