package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── TRUSTED 模式:所有未显式禁用的工具不需逐次审批 ──

    @Test
    fun `trusted mode auto approves all high tools including command tools`() {
        listOf(
            "send_sms",
            "make_phone_call",
            "add_contact",
            "add_calendar_event",
            "execute_javascript",
            "workspace_delete",
            "workspace_write",
            "workspace_move",
            "mcp_mgmt_remove",
            "mcp_mgmt_configure",
            "ui_get_page_info",
            "ui_click",
            "ui_long_press",
            "ui_swipe",
            "ui_set_text",
            "ui_screenshot",
            "ui_back",
            "ui_home",
            "ui_global_action",
            "ui_get_current_app",
            "ui_agent",
            "automation_workflow",
            "execute_shell",
            "execute_node_script",
            "virtual_screen",
            "virtual_screen_input",
            "screen_tap",
            "screen_tap_text",
            "screen_swipe",
            "screen_pinch",
            "screen_swipe_path",
            "screen_input",
            "screen_launch_app",
            "app_force_stop",
            "app_clear_data",
            "app_uninstall",
            "settings_get",
            "settings_put",
            "network_toggle",
            "am_start",
            "list_packages",
            "logcat_tail",
            "input_inject",
            "device_shell",
            "terminal_exec",
            "termux_exec",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED 下 $tool 不应逐次要求审批", ToolApprovalState.Auto, result)
        }
    }

    @Test
    fun agentToolsRemainApprovedInTrustedModeThroughTheCentralRiskResolver() {
        listOf(
            "automation_workflow", "virtual_screen", "virtual_screen_input",
            "screen_read", "screen_current_app", "screen_tap", "screen_tap_text",
            "screen_swipe", "screen_pinch", "screen_swipe_path", "screen_input", "screen_launch_app",
            "app_force_stop", "app_clear_data", "app_uninstall",
            "settings_get", "settings_put", "network_toggle", "am_start",
            "list_packages", "logcat_tail", "input_inject",
        ).forEach { tool ->
            val risk = ToolPermissionResolver.riskLevelFor(tool)
            assertEquals("$tool must resolve to HIGH", ToolRiskLevel.HIGH, risk)
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = risk,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED 下 $tool 不应逐次要求审批", ToolApprovalState.Auto, result)
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
                "clipboard_write",
                ToolRiskLevel.NORMAL,
                SessionPermissionMode.TRUSTED,
                null,
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

    // ── P0-6: 断点恢复重审语义 ──

    @Test
    fun `ask recovery must re-approve high tools even when saved as executing`() {
        // 断点恢复时对 executionState=EXECUTING 的调用重跑审批:风险取 riskLevelFor(单一真源),
        // ASK 模式下 HIGH 工具必须 Pending(重新走审批卡),不得因"已保存为执行中"直接 Auto。
        listOf(
            "send_email",
            "open_url",
            "workspace_write",
            "workspace_delete",
            "make_phone_call",
            "execute_javascript",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolPermissionResolver.riskLevelFor(tool),
                mode = SessionPermissionMode.ASK,
                perToolPolicy = null,
            )
            assertEquals("ASK 恢复时 $tool 必须重新进入审批", ToolApprovalState.Pending, result)
        }
    }

    @Test
    fun `trusted recovery auto approves high tools via single source`() {
        // 恢复重审用 riskLevelFor 而非注册台账:即使注册值被降级为 NORMAL,
        // TRUSTED 模式下不可逆工具仍必须 Pending(避免 TRUSTED 恢复时绕过审批)。
        listOf(
            "workspace_write",
            "workspace_delete",
            "workspace_move",
            "execute_javascript",
            "send_sms",
            "make_phone_call",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolPermissionResolver.riskLevelFor(tool),
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED 恢复时 $tool 不应逐次要求审批", ToolApprovalState.Auto, result)
        }
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

    @Test
    fun `session allow is only a fallback and does not override explicit deny`() {
        assertEquals(
            ToolApprovalPolicy.ALWAYS_ALLOW,
            effectivePerToolPolicy(null, allowedThisSession = true),
        )
        assertEquals(
            ToolApprovalPolicy.ALWAYS_DENY,
            effectivePerToolPolicy(ToolApprovalPolicy.ALWAYS_DENY, allowedThisSession = true),
        )
        assertEquals(
            null,
            effectivePerToolPolicy(null, allowedThisSession = false),
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
        assertEquals("TRUSTED 下未知 scheme 不走审批卡,交由工具自身处理", ToolApprovalState.Auto, unknown)
    }

    // ── P0-8: STRICT 模式优先于参数化 Auto ──

    @Test
    fun `strict mode is not bypassed by param auto for open_url`() {
        // 修复前:https 的 open_url 走参数化策略直接 Auto,先于 STRICT 判定返回 → 旁路审批。
        // 修复后:STRICT 下参数策略只能收紧,https 也必须 Pending。
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.STRICT,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals("STRICT 下 open_url(https) 必须审批", ToolApprovalState.Pending, result)
    }

    @Test
    fun `strict mode still honors param deny for open_url`() {
        // 参数化 Denied 是收紧,STRICT 下仍最优先生效
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.STRICT,
            null,
            mapOf("url" to "file:///etc/passwd"),
        )
        assertTrue("STRICT 下 file:// 仍应拒绝", result is ToolApprovalState.Denied)
    }

    @Test
    fun `ask mode keeps param auto for open_url`() {
        // 非 STRICT 模式(ASK)下参数化 Auto 语义不变
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.ASK,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals(ToolApprovalState.Auto, result)
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

    @Test
    fun `isUnsafeCommand also catches damage the syntax and name checks miss`() {
        // 第 0 步补的地板:既不含任何禁用语法字符,也不在可执行文件黑名单里
        // (`dd` 不在黑名单、`mkfs.ext4` 也不是 `mkfs`),但破坏力已经够拦下。
        assertTrue(ToolPermissionResolver.isUnsafeCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(ToolPermissionResolver.isUnsafeCommand("mkfs.ext4 /dev/block/sda1"))
        assertTrue(ToolPermissionResolver.isUnsafeCommand("echo pwned > /system/etc/hosts"))
    }

    @Test
    fun `read only sandbox verbs are not caught by the new floor`() {
        // 地板只增加拒绝,不能反过来误杀 execute_shell 白名单里的命令形态。
        listOf(
            "ls", "cat notes.txt", "df", "du -sh", "echo hi", "whoami", "pwd", "tree",
            "stat notes.txt", "find . -name x", "grep TODO notes.txt", "head -n 5 notes.txt",
        ).forEach { command ->
            assertFalse("白名单命令不该被地板误杀:$command", ToolPermissionResolver.isUnsafeCommand(command))
        }
    }

    @Test
    fun `two tiers stay apart from the read-only judgement`() {
        // 管道/重定向在只读沙箱上照旧判为不安全,在通用终端第一档里不受影响 —— 两档不能合回一张判据。
        listOf("cat notes.txt | grep x", "ls -la > listing.txt", "echo \$PATH | wc -l").forEach { command ->
            assertTrue("execute_shell 仍应保持严:$command", ToolPermissionResolver.isUnsafeCommand(command))
            assertNull("terminal_exec 第一档不该拦它:$command", ShellCommandBoundary.forbidReason(command, null))
        }
    }
}
