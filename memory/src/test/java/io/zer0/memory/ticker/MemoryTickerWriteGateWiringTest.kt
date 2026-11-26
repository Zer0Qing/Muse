package io.zer0.memory.ticker

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆 ticker 与写闸的接线回归。
 *
 * 锁死的契约（不是某段字符串的写法）：
 *  - 每日 checkpoint 走统一闸判定，而闸判定自身必须接进程闸 —— 自动恢复只开
 *    `ProcessWriteGate`，不接就等于这条路径完全没被拦住；
 *  - checkpoint 写入必须能告诉调用方「没写进去」，否则被拦下也会被当成已完成；
 *  - 每个 daily 步骤先落盘 checkpoint、再认完成，不能先改内存。
 */
class MemoryTickerWriteGateWiringTest {

    @Test
    fun `checkpoint and forced compilation still honour the process gate`() {
        val source = normalizedTicker()

        val forceBody = source.body("suspend fun forceCompileNow", "suspend fun readCompiledMemoryMarkdown")
        val gateHelper = source.body("private fun writesBlocked", "private fun dailyFence")
        val dailyStateBody = source.body("private suspend fun writeDailyState", "private suspend fun markStepDone")
        val dailyBody = source.body("private suspend fun doDaily", "suspend fun getAllSummarizedSessionIds")

        // 写入前必须重新判闸（恢复可能恰好在模型返回时开始）。
        assertTrue("forceCompileNow 内缺少写闸复查", forceBody.contains("ProcessWriteGate.restoring"))
        assertTrue("daily 流水线内缺少写闸复查", dailyBody.contains("ProcessWriteGate.restoring"))
        // 统一闸判定必须回落到进程闸：默认 MemoryWriteGate 也查 ProcessWriteGate，
        // 但这里直接钉住 ticker 自己的兜底，避免未注入闸时静默放行。
        assertTrue(
            "writesBlocked 必须回落到 ProcessWriteGate.restoring",
            gateHelper.contains("ProcessWriteGate.restoring"),
        )
        assertTrue(
            "writeDailyState 必须经由统一闸判定",
            dailyStateBody.contains("writesBlocked("),
        )
    }

    @Test
    fun `daily steps persist the checkpoint before declaring the step done`() {
        val source = normalizedTicker()

        val writeStateSignature = source.body("private suspend fun writeDailyState", "{")
        assertTrue(
            "writeDailyState 必须返回是否真的落库",
            writeStateSignature.contains("): Boolean"),
        )

        val dailyBody = source.body("private suspend fun doDaily", "suspend fun getAllSummarizedSessionIds")
        MemoryTicker.DAILY_STEP_KEYS.forEach { step ->
            assertTrue(
                "步骤 $step 必须经由 markStepDone 写 checkpoint",
                dailyBody.contains("markStepDone(\"$step\""),
            )
        }
        assertTrue("checkpoint 写失败必须中止本轮", dailyBody.contains("?: return"))
        assertFalse(
            "不能先改内存里的 completed 再把落库当副作用",
            dailyBody.contains("completed = completed + ("),
        )
    }

    @Test
    fun `daily dedup consolidation and reflection re-check the entry fence`() {
        val dailyBody = normalizedTicker().body("private suspend fun doDaily", "suspend fun getAllSummarizedSessionIds")
        val maintenance = dailyBody.body("val maintenanceStores", "if (hasFailed)")

        // 这三步都要先调模型再写库:一次恢复窗口可能整个落在里面,入口栅栏失效过就不能落库。
        val gateChecks = Regex("writesBlocked\\(fence\\)").findAll(maintenance).count()
        assertTrue("去重/整合/反思三处都要判闸,实际 $gateChecks 处", gateChecks >= 3)
        assertTrue("规则去重必须在闸后", maintenance.contains("dedupPassAllSpaces()"))
        assertTrue(
            "整合必须带上入口栅栏,否则它会自己重取一份永远有效的栅栏",
            maintenance.contains("consolidateAll(store, fence = fence)"),
        )
        assertTrue("反思必须在闸后", maintenance.contains("runReflection("))
    }

    /** 读源码并把连续空白归一化成单个空格：断言只依赖语句结构，不依赖缩进/换行。 */
    private fun normalizedTicker(): String {
        val raw = listOf(
            Path.of("src/main/java/io/zer0/memory/ticker/MemoryTicker.kt"),
            Path.of("memory/src/main/java/io/zer0/memory/ticker/MemoryTicker.kt"),
        )
            .firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
            ?: error("MemoryTicker.kt not found")
        return raw.replace(Regex("\\s+"), " ")
    }

    /** 取 [start] 之后、[end] 之前的一段源码；锚点缺失直接失败，避免断言静默通过。 */
    private fun String.body(start: String, end: String): String {
        val from = indexOf(start)
        assertTrue("找不到起始锚点: $start", from >= 0)
        val to = indexOf(end, from)
        assertTrue("找不到结束锚点: $end", to > from)
        return substring(from, to)
    }
}