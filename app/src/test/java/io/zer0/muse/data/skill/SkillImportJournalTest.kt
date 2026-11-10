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

    /**
     * 清不干净时**必须保留标记**。
     *
     * 旧实现不管删没删成都照旧删标记、把 id 记进"已清理"，于是那个半截目录
     * 永远不会再被处理 —— 而界面已经对用户说过"清理过残留"，下次装同名包照样判"已存在"。
     * 这等于让 journal 在最需要它的那一刻失效，所以单独钉住。
     *
     * `deleter` 是为此留的注入点：跨平台稳定地制造一次"目录删不掉"并不现实
     * （只读位、文件占用都不可靠），而这条分支恰恰最要命。
     */
    @Test
    fun `cleanup failure keeps the marker so the next launch retries`() {
        val target = File(root, "demo").apply { mkdirs() }
        File(target, "SKILL.md").writeText("残缺")
        journal.begin("demo", target)

        val recovered = journal.recover(deleter = { false })

        assertTrue("没删掉就不该报成已清理：$recovered", recovered.isEmpty())
        assertTrue("半截目录还在原处（没被假装清掉）", target.exists())
        assertTrue("标记必须留着，下次启动才会重试", markerExists("demo"))

        // 下一次启动真的能清掉
        val second = journal.recover()
        assertEquals(listOf("demo"), second)
        assertFalse(target.exists())
        assertFalse(markerExists("demo"))
    }

    @Test
    fun `cleanup throwing also keeps the marker and never crashes recovery`() {
        val target = File(root, "boom").apply { mkdirs() }
        journal.begin("boom", target)

        val recovered = journal.recover(deleter = { throw java.io.IOException("disk busy") })

        assertTrue("异常不能把整轮恢复带崩，也不能谎报清理成功：$recovered", recovered.isEmpty())
        assertTrue(markerExists("boom"))
    }

    /** 注释里承诺"写失败不阻断导入"，那就得真的不抛；同时这条路径要留痕。 */
    @Test
    fun `begin never throws even when the journal cannot be written`() {
        // root 落在一个普通文件下面：mkdirs() 与 writeText 都会失败
        val blocker = File(root, "blocker").apply { writeText("我是文件不是目录") }
        val blocked = SkillImportJournal(File(blocker, "journal-dir"))
        val seen = mutableListOf<String>()
        io.zer0.common.Logger.sink = { _, tag, msg, _ -> seen += "$tag|$msg" }

        try {
            blocked.begin("demo", File(blocker, "demo"))
        } finally {
            io.zer0.common.Logger.sink = null
        }

        assertTrue("没有崩溃保险这件事必须进日志：$seen", seen.any { it.contains("崩溃保险") })
    }

    private fun markerExists(skillId: String): Boolean = File(root, ".importing-$skillId.journal").exists()
}
