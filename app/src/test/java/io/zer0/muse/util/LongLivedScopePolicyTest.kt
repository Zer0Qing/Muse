package io.zer0.muse.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 长生命周期协程作用域的策略回归。
 *
 * ## 这条规则守的是什么
 *
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 这类作用域**没人等结果**，
 * 而 `SupervisorJob` 只阻止"连坐兄弟"，不接管失败本身：没配异常处理器时，
 * 火忘协程里的异常会落到线程默认未捕获处理器。两个后果都真实存在：
 *
 *  - **生产**：一次后台设置读写失败就能崩掉整个应用；
 *  - **测试**：Robolectric 每个用例换一次 `filesDir`，上一个用例残留的订阅在旧目录被删后抛 IO 异常，
 *    被**下一个** `runTest` 判成 `UncaughtExceptionsBeforeTest` ——
 *    症状是"跑全量偶发挂一条、单跑必过"，而且每轮换受害者，谁都以为是别人的用例写坏了。
 *
 * 策略对象 [GlobalCoroutineExceptionHandler] 早就有了（记录且不冒泡），
 * 缺的只是"每个长生命周期作用域都把它编进 context"。本测试防的就是"新写一个忘了带"。
 */
class LongLivedScopePolicyTest {

    @Test
    fun `source tree is reachable so the rule cannot pass by scanning nothing`() {
        val files = mainSources()
        assertTrue("扫不到源码文件时这条测试形同虚设，候选根目录：$ROOT_CANDIDATES", files.isNotEmpty())
        assertTrue("至少该扫到几十个文件，实际 ${files.size}", files.size > 50)
    }

    @Test
    fun `every long-lived scope composes the global coroutine exception handler`() {
        val offenders = mainSources().flatMap { file -> offendersIn(file) }

        assertTrue(
            "这些长生命周期作用域没接全局异常处理器，后台失败会冒泡到线程默认处理器：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 一行里出现目标构造，且本行与后两行都没有处理器 → 记为违规（后两行覆盖跨行写法）。 */
    private fun offendersIn(file: File): List<String> {
        val lines = file.readText().lines()
        val hits = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import")) return@forEachIndexed
            val pattern = LONG_LIVED.firstOrNull { trimmed.contains(it) } ?: return@forEachIndexed
            val window = lines.subList(index, minOf(index + 3, lines.size))
            if (window.none { it.contains(HANDLER) }) {
                hits += "  ${file.name}:${index + 1} [$pattern] ${trimmed.take(88)}"
            }
        }
        return hits
    }

    private fun mainSources(): List<File> {
        val root = ROOT_CANDIDATES.map { File(it) }.firstOrNull { it.isDirectory } ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val HANDLER = "GlobalCoroutineExceptionHandler"

        val ROOT_CANDIDATES = listOf(
            "src/main/java/io/zer0/muse",
            "app/src/main/java/io/zer0/muse",
        )

        /** 需要接处理器的构造写法（顺序两种都算，别让人靠换顺序绕过检查）。 */
        val LONG_LIVED = listOf(
            "CoroutineScope(SupervisorJob() + Dispatchers.IO)",
            "CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)",
            "CoroutineScope(Dispatchers.IO + SupervisorJob())",
            "CoroutineScope(Dispatchers.IO)",
        )
    }
}
