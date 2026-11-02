package io.zer0.muse.data.skill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 技能包资源读取（对比清单 P1-09）。
 *
 * 最要紧的是**路径约束**：技能包是用户从外部拿到的，里面写什么都可能。
 * 一个 `../../databases/muse.db` 就能把应用的私有数据库读出来 —— 这类输入一律拒绝。
 */
class SkillPackageResourcesTest {

    private lateinit var workspace: File
    private lateinit var resources: SkillPackageResources

    @Before
    fun setUp() {
        workspace = kotlin.io.path.createTempDirectory("muse-skills").toFile()
        resources = SkillPackageResources(workspace)
        File(workspace, "demo/references").mkdirs()
        File(workspace, "demo/scripts").mkdirs()
        File(workspace, "demo/SKILL.md").writeText("---\nname: demo\n---\n正文")
        File(workspace, "demo/references/schema.md").writeText("表结构说明")
        File(workspace, "demo/scripts/run.sh").writeText("echo hi")
        // 包外的敏感文件：任何越界读取都必须失败
        File(workspace, "secret.txt").writeText("不该被读到")
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `resources are listed without the manifest and with nested paths`() {
        val listed = resources.list("demo")

        assertEquals(listOf("references/schema.md", "scripts/run.sh"), listed)
        assertTrue("SKILL.md 是入口不是资源", "SKILL.md" !in listed)
    }

    @Test
    fun `an unknown package lists nothing instead of throwing`() {
        assertTrue(resources.list("nope").isEmpty())
    }

    @Test
    fun `reading a resource returns its text`() {
        val outcome = resources.read("demo", "references/schema.md")

        assertTrue(outcome is SkillPackageResources.Outcome.Text)
        assertEquals("表结构说明", (outcome as SkillPackageResources.Outcome.Text).content)
    }

    @Test
    fun `traversal outside the package is refused`() {
        listOf("../secret.txt", "../../secret.txt", "references/../../secret.txt").forEach { path ->
            val outcome = resources.read("demo", path)
            assertTrue("越界必须拒绝: $path -> $outcome", outcome is SkillPackageResources.Outcome.Failed)
        }
    }

    @Test
    fun `an absolute path is refused`() {
        val outcome = resources.read("demo", "/etc/hosts")

        assertTrue(outcome is SkillPackageResources.Outcome.Failed)
    }

    @Test
    fun `a missing file is reported with its name`() {
        val outcome = resources.read("demo", "references/none.md")

        assertTrue(outcome is SkillPackageResources.Outcome.Failed)
        assertTrue((outcome as SkillPackageResources.Outcome.Failed).reason.contains("不存在"))
    }

    @Test
    fun `an oversized file is refused instead of flooding the context`() {
        val big = "x".repeat(SkillPackageResources.MAX_RESOURCE_BYTES + 10)
        File(workspace, "demo/references/big.md").writeText(big)

        val outcome = resources.read("demo", "references/big.md")

        assertTrue(outcome is SkillPackageResources.Outcome.Failed)
        assertTrue((outcome as SkillPackageResources.Outcome.Failed).reason.contains("过大"))
    }

    @Test
    fun `a blank path is refused`() {
        assertTrue(resources.read("demo", "   ") is SkillPackageResources.Outcome.Failed)
    }
}
