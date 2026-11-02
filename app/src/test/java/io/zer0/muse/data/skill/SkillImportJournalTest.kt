package io.zer0.muse.data.skill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 导入 journal（对比清单 P1-09）。
 *
 * 要防的是**进程被杀**留下的残局：复制到一半的目录会让同名包被判成"已存在"，
 * 而那个目录其实是残缺的 —— 用户会看到"明明没装成功，却说我已安装"。
 */
class SkillImportJournalTest {

    private lateinit var root: File
    private lateinit var journal: SkillImportJournal

    @Before
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("muse-journal").toFile()
        journal = SkillImportJournal(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `begin leaves a marker and commit removes it`() {
        val target = File(root, "demo").apply { mkdirs() }

        journal.begin("demo", target)
        assertTrue("导入中要留下标记", markerExists("demo"))

        journal.commit("demo")
        assertFalse("导入成功后标记必须消失", markerExists("demo"))
    }

    @Test
    fun `recovery removes the half copied directory and reports the id`() {
        val target = File(root, "demo").apply { mkdirs() }
        File(target, "SKILL.md").writeText("残缺")
        journal.begin("demo", target)

        val recovered = journal.recover()

        assertEquals(listOf("demo"), recovered)
        assertFalse("残目录必须被清掉", target.exists())
        assertFalse(markerExists("demo"))
    }

    @Test
    fun `a tampered marker cannot make us delete files outside the root`() {
        val outside = kotlin.io.path.createTempDirectory("muse-outside").toFile()
        val victim = File(outside, "important.txt").apply { writeText("不该被删") }
        File(root, ".importing-evil.journal").writeText("${victim.parentFile.absolutePath}\n0")

        journal.recover()

        assertTrue("根目录之外的文件不能动: ${victim.absolutePath}", victim.exists())
        outside.deleteRecursively()
    }

    @Test
    fun `a marker whose target already vanished is simply cleaned up`() {
        File(root, ".importing-gone.journal").writeText("${File(root, "gone").absolutePath}\n0")

        val recovered = journal.recover()

        assertEquals(listOf("gone"), recovered)
        assertFalse(markerExists("gone"))
    }

    @Test
    fun `recovery on a missing root is a no-op`() {
        val missing = SkillImportJournal(File(root, "nope"))

        assertTrue(missing.recover().isEmpty())
    }

    private fun markerExists(skillId: String): Boolean =
        File(root, ".importing-$skillId.journal").exists()
}
