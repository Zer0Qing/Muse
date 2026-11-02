package io.zer0.muse.data.plugin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 作者资料与代码的一致性（插件计划 P4）。
 *
 * 两件东西最容易"写完就烂"：类型声明和示例包。
 *  - `.d.ts` 里少一个桥动作 → 作者照着写会踩"为什么被拒绝"，而拒绝日志里出现的正是那个动作名；
 *  - 示例清单写了不存在的字段 → 新手第一份插件就报错。
 *
 * 所以这里把它们和代码绑在一起：桥动作表里每个动作都必须在 `.d.ts` 里出现，
 * 每个示例清单都必须能被真的解析成 [PluginManifest]。
 */
class PluginDtsSyncTest {

    /** 测试工作目录是 app 模块，作者资料在仓库根的 docs/ 下。 */
    private fun docsFile(relative: String): File = File("../docs/plugin/$relative")

    @Test
    fun `every bridge action is documented in the type declarations`() {
        val dts = docsFile("muse-plugin.d.ts")
        assertTrue("找不到类型声明文件: ${dts.absolutePath}", dts.isFile)
        val text = dts.readText()

        val actions = PluginCapabilityGateway.CAPABILITY_ACTIONS.values.flatten().toSet()
        assertTrue("动作表不该为空", actions.isNotEmpty())
        val missing = actions.filterNot { text.contains("action: $it") }
        assertTrue(
            "以下桥动作没有出现在 muse-plugin.d.ts 里（作者会踩坑）: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every declared capability is documented in the type declarations`() {
        val text = docsFile("muse-plugin.d.ts").readText()

        // 只校验"能声明的那些"：CAPABILITY_ACTIONS 的键就是清单里可写的名字
        val declared = PluginCapabilityGateway.CAPABILITY_ACTIONS.keys
        val missing = declared.filterNot { text.contains("\"$it\"") }
        assertTrue("以下能力没有出现在类型声明里: $missing", missing.isEmpty())
    }

    @Test
    fun `the sample plugins are real and parseable`() {
        val samples = docsFile("samples")
        assertTrue("找不到示例目录: ${samples.absolutePath}", samples.isDirectory)
        val dirs = samples.listFiles { file -> file.isDirectory }.orEmpty()
        assertTrue("示例不该少于 3 个", dirs.size >= 3)

        dirs.forEach { dir ->
            val manifestFile = File(dir, "manifest.json")
            assertTrue("${dir.name} 缺 manifest.json", manifestFile.isFile)
            val parsed = io.zer0.common.AppJson.decodeFromString(
                PluginManifest.serializer(),
                manifestFile.readText(),
            )
            assertTrue("${dir.name} 的 id 为空", parsed.id.isNotBlank())
            assertTrue("${dir.name} 没有声明任何工具", parsed.tools.isNotEmpty())
            assertTrue("${dir.name} 的入口文件不存在: ${parsed.entry}", File(dir, parsed.entry).isFile)
            parsed.uiView?.let { view ->
                assertTrue("${dir.name} 声明的界面文件不存在: $view", File(dir, view).isFile)
            }
        }
    }
}
