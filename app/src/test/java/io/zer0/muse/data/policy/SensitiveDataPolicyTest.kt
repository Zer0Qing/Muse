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

        val deleted = ToolOutputRetention.purge(dir, now, retentionDays = 7)

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
}
