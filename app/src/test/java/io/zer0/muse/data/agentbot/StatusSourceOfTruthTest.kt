package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 状态定义只能有一个答案。
 *
 * 第七十二轮发现：`NEEDS_USER` 从 `TERMINAL` 移除后，状态机的终态分支里还留着它。
 * 两处定义漂移，编译能过、行为也对（因为前面有分支先匹配），但读代码的人会被误导。
 *
 * 这个类把 `ALL` / `TERMINAL` / 状态机 / 文案映射绑在一起。
 */
class StatusSourceOfTruthTest {

    private val machine: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskStatusMachine.kt").readText()
    }

    private val cards: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentStreamCards.kt").readText()
    }

    @Test
    fun `ALL contains every status exactly once`() {
        assertEquals("状态不得重复", BotTaskStatus.ALL.size, BotTaskStatus.ALL.toSet().size)
        assertEquals("状态数应为 12", 12, BotTaskStatus.ALL.size)
    }

    @Test
    fun `TERMINAL and NON_TERMINAL partition ALL`() {
        val union = BotTaskStatus.TERMINAL + BotTaskStatus.NON_TERMINAL
        assertEquals("两者并集必须等于全集", BotTaskStatus.ALL.toSet(), union.toSet())
        assertTrue(
            "两者不能有交集",
            (BotTaskStatus.TERMINAL intersect BotTaskStatus.NON_TERMINAL).isEmpty(),
        )
        assertEquals(
            "非终态数 = 全集 - 终态",
            BotTaskStatus.ALL.size - BotTaskStatus.TERMINAL.size,
            BotTaskStatus.NON_TERMINAL.size,
        )
    }

    @Test
    fun `every status has a branch in the state machine`() {
        // 每个状态都要在 next() 里被显式处理，否则会落到 else -> null 静默失效。
        // 切到下一个顶层函数，不能找 "    /**" —— 函数体内就有 KDoc，
        // 那会把窗口提前截断（本轮踩过）。
        val nextAt = machine.indexOf("fun next(current: String, event: Event)")
        val fnEnd = machine.indexOf("\n    fun ", nextAt)
        val body = machine.substring(nextAt, if (fnEnd > nextAt) fnEnd else machine.length)
        val codeLines = body.lines().filterNot { it.trimStart().startsWith("//") }
        val code = codeLines.joinToString("\n")
        // 注意：终态用合并语法（A, B, -> null），所以只能验"状态名出现过"，
        // 不能要求每个都有独立的 "->"。
        val missing = BotTaskStatus.ALL.filter { !code.contains("BotTaskStatus." + it) }
        assertTrue("这些状态在状态机里没有分支：" + missing, missing.isEmpty())
    }

    @Test
    fun `every status has a user facing label`() {
        val at = cards.indexOf("internal fun agentStatusLabel(")
        assertTrue("找不到 agentStatusLabel", at >= 0)
        val fnEnd = cards.indexOf("internal fun ", at + 15)
        val body = cards.substring(at, if (fnEnd > at) fnEnd else cards.length)
        val missing = BotTaskStatus.ALL.filter { !body.contains(it) }
        assertTrue("这些状态没有用户可读文案：" + missing, missing.isEmpty())
    }

    @Test
    fun `terminal statuses appear in the terminal branch of the state machine`() {
        val at = machine.indexOf("// 终态：任何事件都不再改写")
        assertTrue("终态分支必须存在", at > 0)
        val fnEnd = machine.indexOf("\n    fun ", at)
        val body = machine.substring(at, if (fnEnd > at) fnEnd else machine.length)
        val codeLines = body.lines().filterNot { it.trimStart().startsWith("//") }
        val code = codeLines.joinToString("\n")

        // 终态分支必须列出所有终态。
        val missing = BotTaskStatus.TERMINAL.filter { !code.contains(it) }
        assertTrue("终态分支漏了这些：" + missing, missing.isEmpty())

        // 且不能列出任何非终态（那会遮蔽它自己的分支）。
        val wrong = BotTaskStatus.NON_TERMINAL.filter { code.contains("BotTaskStatus." + it) }
        assertTrue("终态分支不该包含非终态：" + wrong, wrong.isEmpty())
    }

    @Test
    fun `ALL matches the declared constants exactly`() {
        // 手写清单会漂移，所以用源码扫描比对。
        // 反射派生试过但回退了：const val 是编译期内联的，
        // R8 会裁掉字段，届时 ALL 变空列表而所有测试照样通过。
        val src = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskEntity.kt").readText()
        val at = src.indexOf("object BotTaskStatus {")
        assertTrue("找不到 BotTaskStatus", at > 0)
        val end = src.indexOf("\n}", at)
        val body = src.substring(at, if (end > at) end else src.length)

        val declared = Regex("const val ([A-Z_]+) =").findAll(body)
            .map { it.groupValues[1] }
            .sorted()
            .toList()

        assertEquals(
            "ALL 必须与声明的常量完全一致 —— 少一个就是漏了状态，多一个就是拼错了",
            declared,
            BotTaskStatus.ALL.sorted(),
        )
    }

    @Test
    fun `ALL contains no duplicates`() {
        assertEquals(
            "常量值不得重复，否则两个状态会被当成一个",
            BotTaskStatus.ALL.size,
            BotTaskStatus.ALL.toSet().size,
        )
    }
}
