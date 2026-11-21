package io.zer0.muse.transformer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import io.zer0.muse.ui.memory.MemoryViewModel

/**
 * 经验库数据源统一（Task 2）的守卫。
 *
 * 这条线最容易被"顺手加个读取"破坏：prompt 与记忆页各读一张表时，
 * 工具写进去的经验在提示词里永远看不到，而编译、单测、UI 全都正常。
 * 所以这里直接读源码，钉住「生产读取只走 memory 侧经验库」。
 */
class ExperiencePromptSourceTest {

    @Test
    fun `prompt assembler reads the unified memory experience repository`() {
        val source = readSource("app/src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
        assertTrue(
            "prompt 必须读 memory 侧经验库",
            source.contains("io.zer0.memory.experience.ExperienceRepository"),
        )
        assertFalse(
            "prompt 不许再读旧 Room 经验表",
            source.contains("io.zer0.muse.data.experience.ExperienceRepository"),
        )
        assertFalse("prompt 不该再用 Room 的 getAll()", source.contains("repo.getAll()"))
    }

    @Test
    fun `memory page reads and writes the unified repository`() {
        val source = readSource("app/src/main/java/io/zer0/muse/ui/memory/MemoryViewModel.kt")
        assertTrue(source.contains("io.zer0.memory.experience.ExperienceRepository"))
        assertFalse(
            "记忆页不许再读旧 Room 经验表",
            source.contains("io.zer0.muse.data.experience.ExperienceRepository\n"),
        )
        assertTrue("CRUD 走统一仓库", source.contains("experienceRepository.record("))
        assertTrue("列表靠 revision 信号重读", source.contains("experienceRepository.revision.collect"))
    }

    @Test
    fun `legacy table is only reachable through the importer`() {
        val koin = readSource("app/src/main/java/io/zer0/muse/AppKoinModule.kt")
        assertTrue(
            "旧表绑定必须仍然存在，供一次性导入使用",
            koin.contains("single { io.zer0.muse.data.experience.ExperienceRepository(get()) }"),
        )
        val importer = readSource("app/src/main/java/io/zer0/muse/data/experience/ExperienceLegacyImporter.kt")
        assertTrue("导入器负责把旧表搬进统一存储", importer.contains("ExperienceEntry.SOURCE_IMPORTED"))
        assertTrue("逐行失败时不写完成标志，下次继续补", importer.contains("if (failed == 0) markRun()"))
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("../" + relative), File("../../" + relative))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("找不到源文件: " + relative)
    }
}
