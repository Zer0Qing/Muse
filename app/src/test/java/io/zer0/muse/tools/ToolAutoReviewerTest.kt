package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AUTO_REVIEW 规则复核器回归测试。
 *
 * 锁死四类边界：只读/工作区内安全写入自动放行；删除、外发、系统包管理、资金与越界路径
 * 必须升级为 Pending。任何规则无法证明安全时都不允许默认放行。
 */
class ToolAutoReviewerTest {

    @Test
    fun `read only tools are auto approved`() {
        listOf(
            "workspace_read",
            "workspace_list",
            "workspace_grep",
            "get_weather",
            "read_file",
            "search_memory",
            "calculator",
            "web_fetch",
        ).forEach { tool ->
            val decision = ToolAutoReviewer.review(tool, emptyMap(), risk = ToolRiskLevel.SAFE)
            assertTrue("$tool 应自动放行", decision.allowed)
            assertEquals("read-only", decision.ruleId)
        }
    }

    @Test
    fun `safe workspace writes are auto approved`() {
        listOf(
            "workspace_write" to mapOf("path" to "notes/today.md", "content" to "hello"),
            "workspace_edit" to mapOf("path" to "notes/today.md", "old_string" to "a", "new_string" to "b"),
            "workspace_mkdir" to mapOf("path" to "notes/archive"),
            "workspace_copy" to mapOf("from" to "notes/a.md", "to" to "notes/b.md"),
            "workspace_move" to mapOf("from" to "notes/a.md", "to" to "notes/archive/a.md"),
        ).forEach { (tool, args) ->
            val decision = ToolAutoReviewer.review(tool, args, risk = ToolRiskLevel.HIGH)
            assertTrue("$tool 的合法工作区写入应自动放行", decision.allowed)
            assertEquals("workspace-safe-write", decision.ruleId)
        }
    }

    @Test
    fun `workspace delete always requires approval even for safe path`() {
        val decision = ToolAutoReviewer.review(
            "workspace_delete",
            mapOf("path" to "notes/today.md"),
            risk = ToolRiskLevel.HIGH,
        )
        assertFalse(decision.allowed)
        assertEquals("destructive-tool", decision.ruleId)
    }

    @Test
    fun `external send tools require approval`() {
        listOf("send_sms", "send_email", "share_text", "channel_reply", "upload_photo", "publish_post")
            .forEach { tool ->
                val decision = ToolAutoReviewer.review(tool, emptyMap(), risk = ToolRiskLevel.NORMAL)
                assertFalse("$tool 不应自动放行", decision.allowed)
                assertEquals("external-send", decision.ruleId)
            }
    }

    @Test
    fun `system package commands require approval`() {
        listOf(
            "terminal_exec" to "sudo apt-get install -y nginx",
            "termux_exec" to "apt install curl",
            "execute_shell" to "dpkg -i package.deb",
        ).forEach { (tool, command) ->
            val decision = ToolAutoReviewer.review(tool, mapOf("command" to command), risk = ToolRiskLevel.HIGH)
            assertFalse("$tool:$command 不应自动放行", decision.allowed)
            assertEquals("system-package", decision.ruleId)
        }
    }

    @Test
    fun `destructive shell commands require approval`() {
        val decision = ToolAutoReviewer.review(
            "terminal_exec",
            mapOf("command" to "rm -rf /data/local/tmp/muse"),
            risk = ToolRiskLevel.HIGH,
        )
        assertFalse(decision.allowed)
        assertEquals("destructive-command", decision.ruleId)
    }

    @Test
    fun `financial tools require approval`() {
        listOf("pay_order", "transfer_money", "wallet_checkout", "bank_card_bind")
            .forEach { tool ->
                val decision = ToolAutoReviewer.review(tool, emptyMap(), risk = ToolRiskLevel.HIGH)
                assertFalse("$tool 不应自动放行", decision.allowed)
                assertEquals("financial", decision.ruleId)
            }
    }

    @Test
    fun `out of bounds paths require approval`() {
        listOf(
            "../escape.txt",
            "/etc/passwd",
            "C:\\Windows\\System32\\drivers\\etc\\hosts",
            "notes:/../../escape.txt",
        ).forEach { path ->
            val decision = ToolAutoReviewer.review("workspace_write", mapOf("path" to path), risk = ToolRiskLevel.HIGH)
            assertFalse("$path 不应自动放行", decision.allowed)
            assertEquals("out-of-bounds-path", decision.ruleId)
        }
    }

    @Test
    fun `mount prefixed workspace path is allowed`() {
        val decision = ToolAutoReviewer.review(
            "workspace_write",
            mapOf("path" to "notes:/docs/a.md"),
            risk = ToolRiskLevel.HIGH,
        )
        assertTrue(decision.allowed)
        assertEquals("workspace-safe-write", decision.ruleId)
    }

    @Test
    fun `absolute path inside explicit workspace root is allowed`() {
        val root = Files.createTempDirectory("muse-auto-review").toFile()
        try {
            val inside = File(root, "notes/a.md").absolutePath
            val decision = ToolAutoReviewer.review(
                "workspace_write",
                mapOf("path" to inside),
                risk = ToolRiskLevel.HIGH,
                workspaceRoot = root.absolutePath,
            )
            assertTrue("工作区内绝对路径应放行", decision.allowed)
            assertEquals("workspace-safe-write", decision.ruleId)

            val outside = File(root.parentFile, "muse-auto-review-outside.txt").absolutePath
            val outsideDecision = ToolAutoReviewer.review(
                "workspace_write",
                mapOf("path" to outside),
                risk = ToolRiskLevel.HIGH,
                workspaceRoot = root.absolutePath,
            )
            assertFalse("工作区外绝对路径应升级审批", outsideDecision.allowed)
            assertEquals("out-of-bounds-path", outsideDecision.ruleId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `third party tools never auto approve`() {
        listOf("plugin_12_demo_get_data", "mcp_server__get_status").forEach { tool ->
            val decision = ToolAutoReviewer.review(tool, emptyMap(), risk = ToolRiskLevel.SAFE)
            assertFalse("$tool 不应自动放行", decision.allowed)
            assertEquals("third-party", decision.ruleId)
        }
    }

    @Test
    fun `unknown tools fall back to approval`() {
        val decision = ToolAutoReviewer.review("mystery_tool", emptyMap(), risk = ToolRiskLevel.NORMAL)
        assertFalse(decision.allowed)
        assertEquals("default-escalation", decision.ruleId)
    }

    @Test
    fun `blank tool name requires approval`() {
        val decision = ToolAutoReviewer.review("   ", emptyMap(), risk = ToolRiskLevel.SAFE)
        assertFalse(decision.allowed)
        assertEquals("invalid-tool", decision.ruleId)
    }

    @Test
    fun `audit sink receives allow and escalation decisions`() {
        val events = mutableListOf<Pair<String, ToolAutoReviewer.Outcome>>()
        val sink = ToolAutoReviewer.AuditSink { toolName, decision ->
            events += toolName to decision.outcome
        }
        ToolAutoReviewer.review("workspace_read", emptyMap(), risk = ToolRiskLevel.SAFE, auditSink = sink)
        ToolAutoReviewer.review("workspace_delete", mapOf("path" to "a.md"), risk = ToolRiskLevel.HIGH, auditSink = sink)

        assertEquals(
            listOf(
                "workspace_read" to ToolAutoReviewer.Outcome.ALLOW,
                "workspace_delete" to ToolAutoReviewer.Outcome.REQUIRE_APPROVAL,
            ),
            events,
        )
    }

    @Test
    fun `auto review policy is honored by the central resolver`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                toolName = "workspace_write",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = ToolApprovalPolicy.AUTO_REVIEW,
                args = mapOf("path" to "notes/a.md"),
            ),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(
                toolName = "workspace_delete",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = ToolApprovalPolicy.AUTO_REVIEW,
                args = mapOf("path" to "notes/a.md"),
            ),
        )
    }
}
