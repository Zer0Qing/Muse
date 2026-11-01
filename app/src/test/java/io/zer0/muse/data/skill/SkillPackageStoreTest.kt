package io.zer0.muse.data.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.tools.SkillImporter
import io.zer0.muse.tools.SkillManagementToolsImpl
import io.zer0.muse.util.SafePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `SKILL.md` 能力包导入与按需读取的回归（P1-B）。
 *
 * 三条我最在意的性质：
 *  1. **穿越包一个字节都不能落盘**；
 *  2. **正文不进数据库**，只存相对路径，用时才读（渐进披露的地基）；
 *  3. 重复导入不覆盖 —— 覆盖会把"更新"和"重装"两种意图混成一个动作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillPackageStoreTest {

    private lateinit var context: Context
    private lateinit var store: SkillPackageStore

    private val manifest = """
        ---
        name: 周报整理
        description: 把本周会话要点整理成三段式周报
        ---

        第一步：读本周记录。
        第二步：按「进展 / 风险 / 下周」三段输出。
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SkillPackageStore(context)
    }

    @Test
    fun `directory package imports and body is read from disk, not from the db`() {
        val source = Files.createTempDirectory("pkg-dir").toFile()
        File(source, "SKILL.md").writeText(manifest)
        File(source, "references").mkdirs()
        File(source, "references/detail.md").writeText("细节")

        val outcome = store.import(source)
        val imported = outcome.imported

        assertTrue("导入应成功: ${outcome.reason}", imported != null)
        val skillId = requireNotNull(imported).skillId
        assertEquals(manifestBody(), store.instructionBody(skillId))
        assertTrue(File(store.dirFor(skillId), "references/detail.md").isFile)
    }

    @Test
    fun `implementation stores a relative path and the body is resolved lazily`() {
        val source = Files.createTempDirectory("pkg-rel").toFile()
        File(source, "SKILL.md").writeText(manifest)
        val skillId = requireNotNull(store.import(source).imported).skillId

        val stored = SkillImporter.encodePromptFile(store.relativeManifestPath(skillId))

        assertTrue("应以 prompt_file: 前缀编码", SkillImporter.isPromptSkill(stored))
        assertEquals("存相对路径，不能是绝对路径", true, !stored.contains(context.filesDir.absolutePath))
        assertEquals(manifestBody(), SkillPackageStore(context).instructionBodyByPath(
            requireNotNull(SkillImporter.promptFilePath(stored)),
        ))
    }

    @Test
    fun `traversal zip is refused without writing a single byte`() {
        val zip = tempZip("evil.zip") { out ->
            out.putNextEntry(ZipEntry("SKILL.md"))
            out.write(manifest.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("../escaped.txt"))
            out.write("pwned".toByteArray())
            out.closeEntry()
        }

        val outcome = store.import(zip)

        assertFalse("穿越包必须被拒", outcome.success)
        assertTrue("原因要说清是路径非法: ${outcome.reason}", outcome.reason?.contains("穿越") == true)
        assertFalse("不能留下半个目录", store.dirFor("evil").exists())
    }

    @Test
    fun `zip with a single top level folder still imports`() {
        val zip = tempZip("wrapped.zip") { out ->
            out.putNextEntry(ZipEntry("my-skill/SKILL.md"))
            out.write(manifest.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("my-skill/references/detail.md"))
            out.write("细节".toByteArray())
            out.closeEntry()
        }

        val imported = store.import(zip).imported

        assertTrue("应接受带单层顶层目录的包", imported != null)
        assertTrue(File(requireNotNull(imported).dir, "SKILL.md").isFile)
        // 顶层目录被剥掉：包内容直接落在 skillId 目录下，不多一层
        assertTrue(File(imported.dir, "references/detail.md").isFile)
    }

    @Test
    fun `missing manifest is refused with an actionable reason`() {
        val zip = tempZip("empty.zip") { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("没有清单".toByteArray())
            out.closeEntry()
        }

        val reason = store.import(zip).reason

        assertTrue("要说清清单应在哪: $reason", reason?.contains("SKILL.md") == true && reason.contains("根目录"))
    }

    @Test
    fun `re-import of the same package is refused instead of overwriting`() {
        // 两次导入用**不同的来源目录名**：id 只认显示名，所以仍必须判定为同一个技能
        val first = Files.createTempDirectory("pkg-a").toFile()
        File(first, "SKILL.md").writeText(manifest)
        val skillId = requireNotNull(store.import(first).imported).skillId
        val second = Files.createTempDirectory("totally-different-name").toFile()
        File(second, "SKILL.md").writeText(manifest)

        val outcome = store.import(second)

        assertFalse("同名包重复导入必须被拒: ${outcome.reason}", outcome.success)
        assertTrue("要给出怎么办: ${outcome.reason}", outcome.reason?.contains("删除旧版本") == true)
        assertEquals("原包内容不受影响", manifestBody(), store.instructionBody(skillId))
    }

    @Test
    fun `body lookup rejects unsafe or outside paths`() {
        assertNull(store.instructionBodyByPath("../../etc/passwd"))
        assertNull(store.instructionBodyByPath("/absolute/SKILL.md"))
        assertNull(store.instructionBodyByPath("workspace/skills/nosuch/SKILL.md"))
    }

    @Test
    fun `execPromptSkill renders the package body for the model`() {
        val source = Files.createTempDirectory("pkg-exec").toFile()
        File(source, "SKILL.md").writeText(manifest)
        val skillId = requireNotNull(store.import(source).imported).skillId
        val skill = SkillEntity(
            id = skillId,
            name = "周报整理",
            description = "把本周会话要点整理成三段式周报",
            implementationKotlin = SkillImporter.encodePromptFile(store.relativeManifestPath(skillId)),
        )

        val rendered = SkillManagementToolsImpl(context, skillRepository = null)
            .execPromptSkill(skill, mapOf("input" to "本周做了 X"))

        assertTrue("正文要真的被读出来: $rendered", rendered.contains("第二步：按「进展 / 风险 / 下周」三段输出"))
        assertTrue("输入要带进去: $rendered", rendered.contains("本周做了 X"))
    }

    @Test
    fun `shared path guard rejects the whole traversal family`() {
        listOf("../x", "/abs/x", "a/../b", "a//b", ".\\x", "C:\\x", "").forEach { bad ->
            assertFalse("应判不安全: $bad", SafePaths.isSafeRelative(bad))
        }
        assertTrue(SafePaths.isSafeRelative("SKILL.md"))
        assertTrue(SafePaths.isSafeRelative("references/detail.md"))
    }

    @Test
    fun `safeResolve keeps results inside the parent`() {
        val parent = Files.createTempDirectory("resolve-parent").toFile()
        val inside = SafePaths.safeResolve(parent, "a/b.txt")
        val outside = SafePaths.safeResolve(parent, "a/../../outside.txt")

        assertEquals(true, inside != null && inside.absoluteFile.canonicalPath.startsWith(parent.absolutePath))
        assertNull("越界解析必须返回 null", outside)
    }

    private fun manifestBody(): String =
        requireNotNull(SkillPackage.parse(manifest).getOrNull()).body

    private fun tempZip(name: String, write: (ZipOutputStream) -> Unit): File {
        val file = File(Files.createTempDirectory("pkg-zip").toFile(), name)
        ZipOutputStream(FileOutputStream(file)).use { zip -> write(zip) }
        return file
    }
}
