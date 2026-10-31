package io.zer0.muse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 所有 `registerReceiver(...)` 调用都必须显式声明导出标志。
 *
 * 背景：`ContextCompat.registerReceiver` 在 flags **既不含 RECEIVER_EXPORTED 也不含
 * RECEIVER_NOT_EXPORTED** 时无条件抛 `IllegalArgumentException`，与系统版本无关。
 * 历史实现按 `Build.VERSION.SDK_INT >= TIRAMISU` 决定传 `RECEIVER_EXPORTED` 还是 `0`，
 * 于是 Android 12 及以下的设备一注册就崩（vivo V1990A / Android 10 实测）。
 * 这类回归编译期看不出来、只有真机跑到那条路径才炸，所以用源码扫描钉住。
 *
 * 判定方式：对每个调用点按括号配平取出完整实参表（多行调用也成立），实参表里必须出现
 * `RECEIVER_EXPORTED` 或 `RECEIVER_NOT_EXPORTED`；标志先赋给局部变量再传入的写法也放行
 * （会在同文件里找回 `val/var <name> =` 的右值一起看）。若把标志做成函数参数、
 * 跨文件常量等本测试认不出的形式，测试会失败并要求人工确认——这是刻意的，
 * 宁可报一次也不要静默漏掉一个会在旧系统上崩的接收器。
 *
 * 注释与字符串字面量在扫描前被替换为空格（保留换行），所以 KDoc 里提到的
 * `registerReceiver` 不会被当成调用点。
 */
class RegisterReceiverFlagTest {

    @Test
    fun `every registerReceiver call declares an exported flag`() {
        val files = sourceRoots().flatMap { root -> kotlinSources(root) }
        assertTrue("没有扫描到任何 .kt 文件", files.isNotEmpty())

        val sources = files.map { file ->
            relativeName(file) to Files.newBufferedReader(file.toPath()).use { it.readText() }
        }
        val violations = registerReceiverViolations(sources)
        val callCount = sources.sumOf { (_, text) -> countReceiveCalls(text) }

        assertTrue(
            "以下 registerReceiver 调用没有传 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，" +
                "ContextCompat 会直接抛 IllegalArgumentException（旧系统上必崩）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
        // 兜底：确认确实扫到了两处已知调用点，避免路径/正则失效导致空跑通过。
        assertTrue("只扫到 $callCount 处 registerReceiver 调用，疑似扫描失效", callCount >= 2)
        listOf(
            "terminal${File.separator}TermuxChannel.kt",
            "schedule${File.separator}ScheduledTaskRunner.kt",
        ).forEach { suffix ->
            assertTrue(
                "扫描结果里没有 $suffix，说明源码根路径没找对：" + sources.map { it.first },
                sources.any { (name, _) -> name.endsWith(suffix) },
            )
        }
    }

    /** 自检：扫描器必须真的能抓住缺标志的调用，否则上面那条断言可能是空转。 */
    @Test
    fun `scan reports a call that omits the export flag`() {
        val violating = """
            class Sample {
                fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
                    val flags = 0
                    ContextCompat.registerReceiver(context, receiver, filter, flags)
                    context.registerReceiver(receiver, filter)
                }
            }
        """.trimIndent()

        val violations = registerReceiverViolations(listOf("Sample.kt" to violating))
        assertEquals("两处缺标志的调用都要报出来：$violations", 2, violations.size)
        assertTrue("应报出文件与行号：$violations", violations[0].startsWith("Sample.kt:4 -> "))
        assertTrue("应报出文件与行号：$violations", violations[1].startsWith("Sample.kt:5 -> "))

        val compliant = """
            class Sample {
                fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                    val flags = ContextCompat.RECEIVER_NOT_EXPORTED
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        filter,
                        flags,
                    )
                    // ContextCompat.registerReceiver(context, receiver, filter, 0) 注释里的不算
                    Log.d("tag", "registerReceiver(ctx, r, filter)")
                }
            }
        """.trimIndent()

        assertEquals(
            "注释、字符串字面量与带标志的调用都不该报：${registerReceiverViolations(listOf("Sample.kt" to compliant))}",
            emptyList<String>(),
            registerReceiverViolations(listOf("Sample.kt" to compliant)),
        )
    }

    /** 逐文件找出缺导出标志的调用点，返回 `文件名:行号 -> 调用文本`。 */
    private fun registerReceiverViolations(sources: List<Pair<String, String>>): List<String> {
        val violations = mutableListOf<String>()
        sources.forEach { (name, raw) ->
            val scan = maskCommentsAndStrings(raw)
            CALL_SITE.findAll(scan).forEach { match ->
                val call = balancedCall(scan, match.range.last)
                if (!declaresExportFlag(call, raw)) {
                    violations += "$name:${lineOf(scan, match.range.first)} -> ${oneLine(call)}"
                }
            }
        }
        return violations
    }

    private fun countReceiveCalls(raw: String): Int = CALL_SITE.findAll(maskCommentsAndStrings(raw)).count()

    /** 实参表里是否声明了导出标志；支持"先赋给局部变量再传入"。 */
    private fun declaresExportFlag(call: String, rawSource: String): Boolean {
        if (call.contains(EXPORTED) || call.contains(NOT_EXPORTED)) return true
        // 退化路径：调用里出现了某个局部变量，且它在本文件里的声明右值带标志。
        return IDENTIFIER.findAll(call).map { it.value }.any { name ->
            val decl = declaredValue(name).find(rawSource) ?: return@any false
            val value = rawSource.substring(decl.range.last + 1).take(DECLARATION_WINDOW)
            value.contains(EXPORTED) || value.contains(NOT_EXPORTED)
        }
    }

    /** 从 `(` 位置起按括号配平取出完整调用文本。 */
    private fun balancedCall(source: String, openIndex: Int): String {
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex, i + 1)
                }
            }
        }
        return source.substring(openIndex)
    }

    private fun lineOf(source: String, index: Int): Int = source.take(index).count { it == '\n' } + 1

    private fun oneLine(call: String): String =
        call.replace(WHITESPACE, " ").let { if (it.length > 160) it.take(160) + "..." else it }

    /**
     * 把行注释、块注释（Kotlin 可嵌套）、字符串与字符字面量替换成空格，保留换行与长度，
     * 这样后续的行号、括号位置仍然对应原文件。
     */
    private fun maskCommentsAndStrings(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            i = when {
                source.startsWith("//", i) -> maskLineComment(source, out, i)
                source.startsWith("/*", i) -> maskBlockComment(source, out, i)
                source.startsWith("\"\"\"", i) -> maskRawString(source, out, i)
                source[i] == '"' || source[i] == '\'' -> maskQuoted(source, out, i)
                else -> i + 1
            }
        }
        return out.toString()
    }

    private fun maskLineComment(source: String, out: StringBuilder, from: Int): Int {
        var i = from
        while (i < source.length && source[i] != '\n') {
            out.setCharAt(i, ' ')
            i++
        }
        return i
    }

    private fun maskBlockComment(source: String, out: StringBuilder, from: Int): Int {
        var i = from
        var depth = 0
        while (i < source.length) {
            if (source.startsWith("/*", i)) {
                depth++
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
            } else if (source.startsWith("*/", i)) {
                depth--
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
                if (depth == 0) return i
            } else {
                if (source[i] != '\n') out.setCharAt(i, ' ')
                i++
            }
        }
        return i
    }

    private fun maskRawString(source: String, out: StringBuilder, from: Int): Int {
        var i = from + 3
        for (k in from until i) out.setCharAt(k, ' ')
        while (i < source.length && !source.startsWith("\"\"\"", i)) {
            if (source[i] != '\n') out.setCharAt(i, ' ')
            i++
        }
        val end = minOf(i + 3, source.length)
        for (k in i until end) out.setCharAt(k, ' ')
        return end
    }

    private fun maskQuoted(source: String, out: StringBuilder, from: Int): Int {
        val quote = source[from]
        var i = from + 1
        out.setCharAt(from, ' ')
        while (i < source.length && source[i] != quote) {
            if (source[i] == '\\' && i + 1 < source.length) {
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
            } else {
                if (source[i] != '\n') out.setCharAt(i, ' ')
                i++
            }
        }
        if (i < source.length) {
            out.setCharAt(i, ' ')
            i++
        }
        return i
    }

    private fun sourceRoots(): List<File> =
        listOf("app/src/main/java", "src/main/java")
            .map(::File)
            .filter(File::isDirectory)
            .ifEmpty { listOfNotNull(File("../app/src/main/java").takeIf(File::isDirectory)) }

    private fun kotlinSources(root: File): List<File> =
        // 用 java.io 遍历：单测跑在 android.jar 下，避免依赖未被 mock 的 NIO 目录 API。
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    private fun relativeName(file: File): String =
        file.path.replace('/', File.separatorChar).let { path ->
            val marker = "src${File.separator}main${File.separator}java${File.separator}"
            if (path.contains(marker)) path.substringAfter(marker) else file.name
        }

    private companion object {
        const val EXPORTED = "RECEIVER_EXPORTED"
        const val NOT_EXPORTED = "RECEIVER_NOT_EXPORTED"
        const val DECLARATION_WINDOW = 400

        val CALL_SITE = Regex("""\bregisterReceiver\s*\(""")
        val IDENTIFIER = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
        val WHITESPACE = Regex("""\s+""")

        fun declaredValue(name: String): Regex =
            Regex("""\b(?:val|var)\s+${Regex.escape(name)}\b[^=\n]*=""")
    }
}
