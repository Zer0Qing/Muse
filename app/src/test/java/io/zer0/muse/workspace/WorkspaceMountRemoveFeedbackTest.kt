package io.zer0.muse.workspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「移除挂载」按钮的失败必须被接住，文案必须七语齐全。
 *
 * 两个都是**没有运行期报错的失效方式**：
 *
 *  1. `WorkspaceManager.removeMount()` 明确返回"是否真的删掉一条"，
 *     而调用点以前直接丢掉返回值 —— 失败时下面的 `mounts()` 会把原条目重新画出来，
 *     用户按下"移除"看到的就是"什么都没发生"，也没有任何一句话。
 *  2. 新增的提示键只要漏一门语言，那门语言的用户就会看到 `null`/回退中文，
 *     而这在编译期完全不报错。
 *
 * 为什么用源码/资源断言而不是驱动 Compose：这个按钮在挂载管理弹层里，
 * 要造出真实 SAF 目录权限才能点到；而会退化成的正是"返回值又被丢一次"这种一行改动。
 */
class WorkspaceMountRemoveFeedbackTest {

    private fun readUiSource(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/ui/WorkspaceScreen.kt"),
            File("app/src/main/java/io/zer0/muse/ui/WorkspaceScreen.kt"),
        )
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 WorkspaceScreen.kt，工作目录=${File(".").absolutePath}"
        }.readText()
    }

    @Test
    fun `移除挂载的返回值被消费并给出失败提示`() {
        val src = readUiSource()

        assertTrue("必须接住 removeMount 的返回值", src.contains("val removed = workspaceManager.removeMount("))
        assertTrue("失败分支必须存在", src.contains("if (!removed)"))
        assertTrue("失败要弹话给用户（不能只改状态）", src.contains("R.string.workspace_mount_remove_failed"))
        assertTrue("也要留一条日志，否则线上无从查证", src.contains("移除挂载失败"))
    }

    @Test
    fun `失败提示在全部语言里都存在`() {
        val resRoot = requireNotNull(
            listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.exists() },
        ) { "找不到 res 目录" }
        val folders = resRoot.listFiles()
            ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .orEmpty()

        assertTrue("至少应有默认 + 6 门语言，实际 ${folders.size}", folders.size >= 7)

        val missing = folders.filter { dir ->
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            // 只看真的承载文案的目录：`values-v31` 只有 themes.xml（API 级别限定，不是语言），
            // 对它提"必须有这条文案"是错的要求 —— 我第一版就这么误判过一次。
            val holdsStrings = files.any { it.readText().contains("<string name=") }
            holdsStrings && files.none { it.readText().contains("name=\"workspace_mount_remove_failed\"") }
        }
        assertTrue(
            "这些语言缺「移除挂载失败」的文案（缺了不会编译报错，用户会看到空白或串语言）：" +
                missing.joinToString { it.name },
            missing.isEmpty(),
        )
    }
}
