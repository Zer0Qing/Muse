package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理访问级别与角色文件（对比清单 P1-08）。
 *
 * 此前 `Params.access` 是"预留字段"：调用方写 `access=read` 与写 `access=write` 没有任何区别，
 * 工具集合永远按同一套规则过滤。这里守的是三档真的不同，且**写错值只会降权不会升权**。
 */
class SubagentAccessTest {

    @Test
    fun `unknown values fall back to read only`() {
        // "read" 不在这里：它是文档值，由下一个用例覆盖
        listOf(null, "", "   ", "readonly", "bogus", "WRITE-ALL", "admin-ish").forEach { raw ->
            assertEquals(
                "未知值必须降级为只读（写错一个词不能换来更大权限）: $raw",
                SubagentAccess.READ_ONLY,
                SubagentAccess.parse(raw),
            )
        }
    }

    @Test
    fun `documented values map to their levels`() {
        assertEquals(SubagentAccess.READ_ONLY, SubagentAccess.parse("read"))
        assertEquals(SubagentAccess.CODER, SubagentAccess.parse("write"))
        assertEquals(SubagentAccess.CODER, SubagentAccess.parse(" CODER "))
        assertEquals(SubagentAccess.FULL, SubagentAccess.parse("full"))
    }

    @Test
    fun `read only cannot reach anything that changes state`() {
        assertTrue(SubagentAccess.READ_ONLY.allows(ToolRiskLevel.SAFE))
        assertTrue("可逆的本地副作用也算改动，只读档不给", !SubagentAccess.READ_ONLY.allows(ToolRiskLevel.NORMAL))
        assertTrue(!SubagentAccess.READ_ONLY.allows(ToolRiskLevel.HIGH))
    }

    @Test
    fun `coder keeps the old behaviour of allowing reversible local actions`() {
        assertTrue(SubagentAccess.CODER.allows(ToolRiskLevel.SAFE))
        assertTrue(SubagentAccess.CODER.allows(ToolRiskLevel.NORMAL))
        assertTrue("高风险仍需显式升级", !SubagentAccess.CODER.allows(ToolRiskLevel.HIGH))
    }

    @Test
    fun `full reaches high risk tools but the gate still applies downstream`() {
        assertTrue(SubagentAccess.FULL.allows(ToolRiskLevel.HIGH))
        // 说明里必须写明"仍逐次审批"，避免读的人以为 full = 免审批
        assertTrue(SubagentAccess.FULL.description().contains("审批"))
        assertTrue(SubagentAccess.FULL.englishDescription().contains("approval"))
    }

    // ── agents/<name>.md ────────────────────────────────────────────────

    private val reviewer = """
        ---
        name: reviewer
        description: 代码审查
        access: read
        ---
        你是一名严格的代码审查者。
    """.trimIndent()

    @Test
    fun `a profile file yields name description access and instructions`() {
        val profile = AgentProfileParser.parse("reviewer", reviewer)

        assertEquals("reviewer", profile?.name)
        assertEquals("代码审查", profile?.description)
        assertEquals(SubagentAccess.READ_ONLY, profile?.access)
        assertEquals("你是一名严格的代码审查者。", profile?.instructions)
    }

    @Test
    fun `a file without frontmatter is not guessed into a profile`() {
        assertNull(AgentProfileParser.parse("x", "你是一个助手"))
        assertNull("没有结束标记也不猜", AgentProfileParser.parse("x", "---\nname: a\n"))
    }

    @Test
    fun `the file name backs up a missing name and access defaults to read only`() {
        val profile = AgentProfileParser.parse(
            "helper",
            "---\ndescription: 帮忙\n---\n正文",
        )

        assertEquals("helper", profile?.name)
        assertEquals(SubagentAccess.READ_ONLY, profile?.access)
    }

    @Test
    fun `instructions are capped so a role file cannot become a knowledge base`() {
        val long = "字".repeat(AgentProfileParser.MAX_INSTRUCTIONS_CHARS + 500)
        val profile = AgentProfileParser.parse("x", "---\nname: x\n---\n$long")

        assertEquals(AgentProfileParser.MAX_INSTRUCTIONS_CHARS, profile?.instructions?.length)
    }

    @Test
    fun `collect sorts by file name and keeps the first definition of a duplicated name`() {
        val files = listOf(
            "b.md" to "---\nname: same\ndescription: from b\n---\nb",
            "a.md" to "---\nname: same\ndescription: from a\n---\na",
            "c.md" to "---\nname: other\n---\nc",
            "d.md" to "no frontmatter",
        )

        val profiles = AgentProfileParser.collect(files)

        assertEquals(listOf("same", "other"), profiles.map { it.name })
        assertEquals("按文件名排序，a.md 先到先得", "from a", profiles.first().description)
    }

    @Test
    fun `collect caps the number of profiles`() {
        val files = (1..(AgentProfileParser.MAX_PROFILES + 5)).map { i ->
            "p$i.md" to "---\nname: p$i\n---\nbody"
        }

        assertEquals(AgentProfileParser.MAX_PROFILES, AgentProfileParser.collect(files).size)
    }

    // ── agents/ 目录读取 ────────────────────────────────────────────────

    @Test
    fun `the catalog reads markdown roles from the agents directory`() {
        val workspace = kotlin.io.path.createTempDirectory("muse-agents").toFile()
        try {
            val dir = java.io.File(workspace, AgentProfileCatalog.DIRECTORY_NAME).apply { mkdirs() }
            java.io.File(dir, "reviewer.md").writeText("---\nname: reviewer\naccess: read\n---\n审查")
            java.io.File(dir, "writer.md").writeText("---\nname: writer\naccess: write\n---\n写作")
            // 非 markdown 文件应被忽略
            java.io.File(dir, "notes.txt").writeText("ignored")

            val profiles = AgentProfileCatalog.load(workspace)

            assertEquals(listOf("reviewer", "writer"), profiles.map { it.name })
            assertEquals(SubagentAccess.READ_ONLY, profiles.first().access)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `a missing agents directory yields no roles instead of failing the delegation`() {
        val workspace = kotlin.io.path.createTempDirectory("muse-empty").toFile()
        try {
            assertTrue(AgentProfileCatalog.load(workspace).isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `a broken role file is skipped rather than breaking the whole catalog`() {
        val workspace = kotlin.io.path.createTempDirectory("muse-broken").toFile()
        try {
            val dir = java.io.File(workspace, AgentProfileCatalog.DIRECTORY_NAME).apply { mkdirs() }
            java.io.File(dir, "broken.md").writeText("没有 frontmatter")
            java.io.File(dir, "ok.md").writeText("---\nname: ok\n---\n正文")

            assertEquals(listOf("ok"), AgentProfileCatalog.load(workspace).map { it.name })
        } finally {
            workspace.deleteRecursively()
        }
    }
}
