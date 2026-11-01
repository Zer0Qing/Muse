package io.zer0.muse.data.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.zer0.muse.tools.SkillImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 技能包导入入口的回归（P1-B）。
 *
 * 最在意的一条是**回滚**：解包成功但落库失败时必须把目录收回去。
 * 留着它就是一份界面看不见、没人引用、却实实在在占着磁盘的技能；
 * 而"下次再导入同一个包"还会因为目录已存在而被拒 —— 用户会看到一个完全解释不通的失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillPackageImporterTest {

    private lateinit var context: Context
    private lateinit var dao: SkillDao
    private lateinit var importer: SkillPackageImporter
    private lateinit var store: SkillPackageStore

    private val manifest = """
        ---
        name: 会议纪要
        description: 把录音转写整理成决议与待办
        ---

        先列出与会者，再分「决议 / 待办 / 风险」三段输出。
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dao = mockk(relaxed = true)
        importer = SkillPackageImporter(context, SkillRepository(dao))
        store = SkillPackageStore(context)
        // Robolectric 的 filesDir 在整个测试类内是同一份：不清就会前一个用例装的包
        // 让后一个用例莫名撞上"技能包已存在"，症状看着像随机失败。
        store.root.deleteRecursively()
    }

    @Test
    fun `a single manifest file installs a lazily loaded skill`() = runBlocking {
        val captured = slot<SkillEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        val outcome = importer.importManifestText(manifest)

        val installed = outcome as? SkillPackageImporter.Outcome.Installed
        assertNotNull("应安装成功: $outcome", installed)
        val skill = requireNotNull(installed).skill
        assertEquals("会议纪要", skill.name)
        assertTrue("必须走 prompt_file 编码", skill.implementationKotlin.startsWith(SkillImporter.PROMPT_FILE_PREFIX))
        assertTrue("正文不进库", !skill.implementationKotlin.contains("决议"))
        assertTrue("相对路径而非绝对路径", !skill.implementationKotlin.contains(context.filesDir.absolutePath))
        assertEquals(skill.id, captured.captured.id)
    }

    @Test
    fun `installed package body is readable through the stored relative path`() = runBlocking {
        val skill = requireNotNull((importer.importManifestText(manifest) as? SkillPackageImporter.Outcome.Installed)?.skill)

        val relative = requireNotNull(SkillImporter.promptFilePath(skill.implementationKotlin))
        val body = SkillPackageStore(context).instructionBodyByPath(relative)

        assertTrue("正文应能按需读出: $body", body?.contains("先列出与会者") == true)
    }

    @Test
    fun `failed save rolls back the extracted directory`() = runBlocking {
        coEvery { dao.upsert(any()) } throws IllegalStateException("db down")

        val outcome = importer.importManifestText(manifest)

        assertTrue("落库失败要转成可读原因: $outcome", outcome is SkillPackageImporter.Outcome.Failed)
        val skillId = store.deriveSkillId("会议纪要")
        assertFalse("包目录必须被回收", store.dirFor(skillId).exists())
        assertNull("正文也不该再读得到", store.instructionBody(skillId))
    }

    @Test
    fun `duplicate import is refused and the first one keeps working`() = runBlocking {
        val first = requireNotNull((importer.importManifestText(manifest) as? SkillPackageImporter.Outcome.Installed)?.skill)

        val second = importer.importManifestText(manifest)

        assertTrue("重复导入应被拒: $second", second is SkillPackageImporter.Outcome.Failed)
        assertNotNull("原技能正文仍可读", store.instructionBody(first.id))
    }

    @Test
    fun `deleting a non-package skill touches no directories`() = runBlocking {
        val plain = SkillEntity(
            id = "plain-skill",
            name = "普通提示词技能",
            description = "d",
            implementationKotlin = SkillImporter.encodePromptText("按这个格式输出"),
        )

        importer.deletePackageFor(plain)

        assertFalse("不该顺手删掉别人的目录", File(store.root, "plain-skill").exists())
    }

    @Test
    fun `deleting a package skill removes its files`() = runBlocking {
        val skill = requireNotNull((importer.importManifestText(manifest) as? SkillPackageImporter.Outcome.Installed)?.skill)
        assertTrue("前置：包已落盘", store.dirFor(skill.id).isDirectory)

        importer.deletePackageFor(skill)

        assertFalse("删除技能后包目录必须一起回收", store.dirFor(skill.id).exists())
    }

    @Test
    fun `zip packages import through the same entry point`() = runBlocking {
        val source = Files.createTempDirectory("zip-import").toFile()
        File(source, "SKILL.md").writeText(manifest)
        val zip = File(source, "pkg.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("SKILL.md"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }

        val installed = importer.importFile(zip) as? SkillPackageImporter.Outcome.Installed

        assertNotNull("ZIP 应能走同一入口: $installed", installed)
        assertEquals("先列出与会者，再分「决议 / 待办 / 风险」三段输出。", store.instructionBody(requireNotNull(installed).skill.id)?.lines()?.firstOrNull())
    }
}
