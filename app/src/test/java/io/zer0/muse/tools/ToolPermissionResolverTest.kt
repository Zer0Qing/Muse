package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * I-AUDIT: 危险工具审批门回归测试。
 *
 * 此前 ToolPermissionResolver / ParamPolicies / ContentSafetyRules 完全无测试,
 * 越权/绕过(混淆 rm -rf、file:// URL、会话权限模式)可无告警合入(审计 P1-1)。
 * 本测试用纯逻辑断言锁死审批门四要素:会话模式 × 风险等级 × 单工具策略 × 参数化策略。
 */
class ToolPermissionResolverTest {

    @Before
    fun setUp() {
        // 参数化策略为进程级单例(ToolPermissionResolver init 已注册一次),此处幂等再注册,
        // 确保 open_url / execute_javascript 的判定在本测试内必然生效。
        ParamPolicies.registerBuiltIn()
    }

    // ── TRUSTED 模式:不可逆 HIGH 工具必须保留审批(含本批次补强项) ──

    @Test
    fun `trusted mode still requires approval for irreversible high tools`() {
        // 原有通信/资金类 + 本批次补强的文件系统不可逆操作与 MCP 连接增删。
        listOf(
            "send_sms",
            "make_phone_call",
            "add_contact",
            "add_calendar_event",
            "execute_javascript",
            "workspace_delete",
            "workspace_write",
            "workspace_move",
            "mcp_server_remove",
            "mcp_server_configure",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED 下 $tool 应仍要求审批", ToolApprovalState.Pending, result)
        }
    }

    @Test
    fun `trusted mode auto-approves high tools not in the irreversible approval list`() {
        listOf("toggle_wifi", "toggle_bluetooth", "set_brightness", "open_url").forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED 下 $tool 应直接放行", ToolApprovalState.Auto, result)
        }
    }

    @Test
    fun `trusted mode auto-approves safe and normal tools`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.TRUSTED, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "clipboard_write", ToolRiskLevel.NORMAL, SessionPermissionMode.TRUSTED, null,
            ),
        )
    }

    // ── ASK 模式 ──

    @Test
    fun `ask mode auto-approves safe, asks normal and high`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("clipboard_write", ToolRiskLevel.NORMAL, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("workspace_delete", ToolRiskLevel.HIGH, SessionPermissionMode.ASK, null),
        )
    }

    // ── STRICT 模式 ──

    @Test
    fun `strict mode auto-approves only allowlisted safe tools`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("calculator", ToolRiskLevel.SAFE, SessionPermissionMode.STRICT, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("workspace_delete", ToolRiskLevel.HIGH, SessionPermissionMode.STRICT, null),
        )
    }

    // ── 单工具策略 ──

    @Test
    fun `always deny forbids the tool in every mode`() {
        SessionPermissionMode.entries.forEach { mode ->
            val result = ToolPermissionResolver.resolve(
                "get_current_time",
                ToolRiskLevel.SAFE,
                mode,
                ToolApprovalPolicy.ALWAYS_DENY,
            )
            assertTrue("ALWAYS_DENY 应在 $mode 下拒绝", result is ToolApprovalState.Denied)
        }
    }

    @Test
    fun `always allow overrides mode and auto-approves`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "workspace_delete",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.TRUSTED,
                ToolApprovalPolicy.ALWAYS_ALLOW,
            ),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "workspace_delete",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.STRICT,
                ToolApprovalPolicy.ALWAYS_ALLOW,
            ),
        )
    }

    // ── 参数化策略(ParamPolicies) ──

    @Test
    fun `open_url rejects file scheme, http auto, unknown pending`() {
        val file = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "file:///etc/passwd"),
        )
        assertTrue("file:// 应拒绝", file is ToolApprovalState.Denied)

        val http = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals(ToolApprovalState.Auto, http)

        val unknown = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "custom-scheme://x"),
        )
        assertEquals(ToolApprovalState.Pending, unknown)
    }

    @Test
    fun `execute_javascript rejects dangerous code patterns`() {
        listOf("rm -rf /tmp/x", "Runtime.getRuntime().exec(\"id\")", "document.cookie").forEach { code ->
            val result = ToolPermissionResolver.resolve(
                "execute_javascript",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.TRUSTED,
                null,
                mapOf("code" to code),
            )
            assertTrue("危险 JS 应被拒绝: $code", result is ToolApprovalState.Denied)
        }
    }

    // ── 命令黑名单硬边界 ──

    @Test
    fun `isUnsafeCommand blocks metacharacters and blocked executables`() {
        assertTrue(ToolPermissionResolver.isUnsafeCommand("rm -rf /data")) // 黑名单可执行文件
        assertTrue(ToolPermissionResolver.isUnsafeCommand("echo $(id)")) // 命令替换
        assertTrue(ToolPermissionResolver.isUnsafeCommand("ls; rm -rf /")) // 命令分隔符
        assertTrue(ToolPermissionResolver.isUnsafeCommand("cat /etc/passwd | sh")) // 管道
        assertFalse(ToolPermissionResolver.isUnsafeCommand("ls")) // 安全命令放行
    }
}
