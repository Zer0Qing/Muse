package io.zer0.muse.transformer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 5 守卫：生产链路只能有一条读取路径。
 *
 * 这些断言直接读源码而不是跑逻辑，因为它们防的是「有人顺手加一个兜底」：
 * 兜底分支在正常路径下永远不会触发，任何行为测试都发现不了它，
 * 直到某天主路径出问题，它悄悄接管并把用户引到第二套真相上。
 */
class MemoryFallbackRemovalTest {

    @Test
    fun `prompt assembler has a single fact store route`() {
        val source = readSource("app/src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
        assertFalse(
            "SystemPromptAssembler 不该再有可空 FactStore 兜底参数",
            source.contains("private val factStore: io.zer0.memory.fact.FactStore? = null"),
        )
        assertFalse(
            "相关记忆检索不该再退回全局 factStore",
            source.contains("factStore.takeIf { storeOwnerId =="),
        )
    }

    @Test
    fun `memory tools no longer fall back to nullable use cases`() {
        val source = readSource("app/src/main/java/io/zer0/muse/tools/AgentToolsRegistrar.kt")
        assertFalse("工具层不该再有 memoryUseCases != null 判断", source.contains("memoryUseCases != null"))
        assertFalse("工具层不该再退回 factStore", source.contains("?: factStore"))
        assertTrue("memoryUseCases 必须是必需依赖", source.contains("private val memoryUseCases: MemoryUseCases,"))
    }

    @Test
    fun `legacy experience repository is removed and the importer reads the DAO directly`() {
        val koin = readSource("app/src/main/java/io/zer0/muse/AppKoinModule.kt")
        val importer = readSource("app/src/main/java/io/zer0/muse/data/experience/ExperienceLegacyImporter.kt")
        assertFalse(
            "旧经验仓库不应再注册，导入器直接读旧 DAO",
            koin.contains("io.zer0.muse.data.experience.ExperienceRepository"),
        )
        assertTrue(
            "导入器必须直接从旧 DAO 读取",
            importer.contains("private val legacy: ExperienceDao"),
        )
    }

    @Test
    fun `production prompt reads memory experience repository`() {
        val source = readSource("app/src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
        assertTrue(source.contains("io.zer0.memory.experience.ExperienceRepository"))
    }

    @Test
    fun `profile snapshot opens the default assistant db, not the main scope`() {
        val source = readSource("app/src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
        // 快照的库按助手 id 取，作用域 main 只进 scope/space 参数。
        // 把 "main" 传给 provider 会打开 facts_main.db（另一个助手的库），快照永远读空，
        // 而设置页的 ProfileSnapshotSection 读的是 "default" —— 两边打架时用户看到“开关开了但没快照”。
        assertFalse(
            "画像快照不得把作用域 main 当成助手 id 去开库",
            source.contains("getFactDb(\"main\")") || source.contains("getFactStore(\"main\")"),
        )
        assertTrue(
            "画像快照与 ProfileSnapshotSection 必须同一口径：助手 id default",
            source.contains("provider.getFactDb(\"default\")") &&
                source.contains("provider.getFactStore(\"default\")"),
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("../" + relative), File("../../" + relative))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("找不到源文件: " + relative)
    }
}
