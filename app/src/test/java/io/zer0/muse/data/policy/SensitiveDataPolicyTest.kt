package io.zer0.muse.data.policy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 本地敏感内容的保存政策（对比清单 P2-05）。
 *
 * 之前工具输出落盘**没有任何保留期限**：跑过几十次构建的设备上，
 * `tool_outputs/` 会留着几十 MB 的命令输出与日志，其中可能含令牌、路径、个人内容。
 */
class SensitiveDataPolicyTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private lateinit var dir: File

    @Before
    fun setUp() {
        ToolOutputRetention.resetForTests()
        dir = kotlin.io.path.createTempDirectory("muse-outputs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        ToolOutputRetention.resetForTests()
    }

    @Test
    fun `a fresh file is kept`() {
        assertFalse(SensitiveDataPolicy.shouldPurge(now - dayMs, now, retentionDays = 7))
    }

    @Test
    fun `a file past the retention window is purged`() {
        assertTrue(SensitiveDataPolicy.shouldPurge(now - 8 * dayMs, now, retentionDays = 7))
    }

    @Test
    fun `a clock jump backwards never deletes data`() {
        // 文件时间比"现在"还新：宁可多留，也不要因为一次时间跳变清空用户数据
        assertFalse(SensitiveDataPolicy.shouldPurge(now + dayMs, now, retentionDays = 7))
    }

    @Test
    fun `a non positive retention disables purging`() {
        assertFalse(SensitiveDataPolicy.shouldPurge(now - 100 * dayMs, now, retentionDays = 0))
    }

    @Test
    fun `preview collapses newlines and truncates`() {
        val preview = SensitiveDataPolicy.preview("line1\nline2\r\nline3", maxChars = 8)

        assertFalse("预览必须单行", preview.contains("\n"))
        assertTrue("超长要截断: $preview", preview.endsWith("…"))
    }

    @Test
    fun `a short preview is returned untouched`() {
        assertEquals("hello", SensitiveDataPolicy.preview("hello", maxChars = 40))
    }

    @Test
    fun `credential shaped text is detected`() {
        listOf(
            "Authorization: Bearer abcdefghijklmnopqrstuvwx",
            "api_key = ABCDEFGHIJKLMNOP",
            "token: sk-abcdefghijklmnopqrstuvwx",
        ).forEach { sample ->
            assertTrue("应识别为疑似凭据: $sample", SensitiveDataPolicy.looksLikeCredential(sample))
        }
    }

    @Test
    fun `ordinary text is not flagged`() {
        assertFalse(SensitiveDataPolicy.looksLikeCredential("build finished in 12s"))
        assertFalse(SensitiveDataPolicy.looksLikeCredential("token count: 128"))
    }

    @Test
    fun `purge deletes only expired files`() {
        val old = File(dir, "old.txt").apply { writeText("x") }
        val fresh = File(dir, "fresh.txt").apply { writeText("y") }
        old.setLastModified(now - 30 * dayMs)
        fresh.setLastModified(now - dayMs)

        val deleted = ToolOutputRetention.purge(dir, now)

        assertEquals(1, deleted)
        assertFalse("过期文件应被删除", old.exists())
        assertTrue("新文件必须留着", fresh.exists())
    }

    @Test
    fun `purge on a missing directory is a no-op`() {
        assertEquals(0, ToolOutputRetention.purge(File(dir, "nope"), now))
    }

    @Test
    fun `the process purges at most once`() {
        val old = File(dir, "old.txt").apply { writeText("x") }
        old.setLastModified(now - 30 * dayMs)

        assertEquals(1, ToolOutputRetention.purgeOnce(dir, now))
        assertEquals("第二次不应再扫目录", 0, ToolOutputRetention.purgeOnce(dir, now))
    }

    // ── 分类表 ──────────────────────────────────────────────────────────

    @Test
    fun `memory raw text may enter backups but mcp results may not`() {
        assertTrue(
            "记忆原文是用户资产，应该允许备份",
            SensitiveDataPolicy.Category.MEMORY_RAW.mayEnterBackup,
        )
        assertFalse(
            "第三方系统的 MCP 结果不该进用户备份",
            SensitiveDataPolicy.Category.MCP_RESULT.mayEnterBackup,
        )
    }

    @Test
    fun `system context expires fastest and memory lasts longest`() {
        val context = SensitiveDataPolicy.Category.SYSTEM_CONTEXT.retentionDays
        val mcp = SensitiveDataPolicy.Category.MCP_RESULT.retentionDays
        val tool = SensitiveDataPolicy.Category.TOOL_OUTPUT.retentionDays
        val memory = SensitiveDataPolicy.Category.MEMORY_RAW.retentionDays

        assertTrue("读屏快照隔天就没意义", context <= 1)
        assertTrue("MCP 结果比工具输出留得更短", mcp < tool)
        assertTrue("记忆原文留得最久", memory > tool)
    }

    @Test
    fun `diagnostics may see tool output and screen context but not mcp or memory`() {
        assertTrue(SensitiveDataPolicy.Category.TOOL_OUTPUT.mayEnterDiagnostics)
        assertTrue(SensitiveDataPolicy.Category.SYSTEM_CONTEXT.mayEnterDiagnostics)
        assertFalse(SensitiveDataPolicy.Category.MCP_RESULT.mayEnterDiagnostics)
        assertFalse("记忆原文不进诊断日志", SensitiveDataPolicy.Category.MEMORY_RAW.mayEnterDiagnostics)
    }

    @Test
    fun `categories round trip through their wire names`() {
        SensitiveDataPolicy.Category.entries.forEach { category ->
            assertEquals(category, SensitiveDataPolicy.Category.parse(category.wire))
            assertEquals(category, SensitiveDataPolicy.Category.parse(" " + category.wire.uppercase() + " "))
        }
        assertEquals(null, SensitiveDataPolicy.Category.parse("unknown"))
    }

    @Test
    fun `purge decisions follow the category retention`() {
        val ageBeyondMcp = now - 4 * dayMs

        assertTrue(
            "4 天前的内容对 MCP 结果已过期",
            SensitiveDataPolicy.shouldPurge(SensitiveDataPolicy.Category.MCP_RESULT, ageBeyondMcp, now),
        )
        assertFalse(
            "同一份内容对记忆原文还没到期",
            SensitiveDataPolicy.shouldPurge(SensitiveDataPolicy.Category.MEMORY_RAW, ageBeyondMcp, now),
        )
    }

    @Test
    fun `preview length follows the category limit`() {
        val long = "字".repeat(5000)

        val mcp = SensitiveDataPolicy.previewFor(SensitiveDataPolicy.Category.MCP_RESULT, long)
        val memory = SensitiveDataPolicy.previewFor(SensitiveDataPolicy.Category.MEMORY_RAW, long)

        assertEquals(SensitiveDataPolicy.Category.MCP_RESULT.inlineLimitChars + 1, mcp.length)
        assertEquals(SensitiveDataPolicy.Category.MEMORY_RAW.inlineLimitChars + 1, memory.length)
        assertTrue("记忆原文的内联上限应更宽", memory.length > mcp.length)
    }

    // ── 按工具名分类（P2-05 消费方迁移） ────────────────────────────────

    @Test
    fun `mcp tool names map to the shorter retention category`() {
        assertEquals(
            SensitiveDataPolicy.Category.MCP_RESULT,
            SensitiveDataPolicy.categoryForTool("mcp_server__search"),
        )
        assertEquals(
            SensitiveDataPolicy.Category.TOOL_OUTPUT,
            SensitiveDataPolicy.categoryForTool("terminal_exec"),
        )
        assertEquals(
            "取不到名字时按普通输出处理",
            SensitiveDataPolicy.Category.TOOL_OUTPUT,
            SensitiveDataPolicy.categoryForTool(null),
        )
    }

    @Test
    fun `purge classifies each file by its own name`() {
        // 同一个目录里混着两类：MCP 结果 4 天前就该删，普通输出还没到期
        val mcp = File(dir, "mcp_server__tool_uuid.txt").apply { writeText("x") }
        val tool = File(dir, "terminal_exec_uuid.txt").apply { writeText("y") }
        mcp.setLastModified(now - 4 * dayMs)
        tool.setLastModified(now - 4 * dayMs)

        val deleted = ToolOutputRetention.purge(dir, now)

        assertEquals(1, deleted)
        assertFalse("MCP 结果 3 天到期，应被删除", mcp.exists())
        assertTrue("普通输出 7 天到期，必须留着", tool.exists())
    }
}
