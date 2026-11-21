package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划 V20：前台优先，后台排队。
 *
 * 用预留槽位实现，而不是改排队顺序 —— 排队顺序对用户不可见，
 * 槽位预留是硬保证：无论后台排多久，前台永远至少有一个槽。
 */
class ForegroundPriorityTest {

    private val limiter: String by lazy {
        File("src/main/java/io/zer0/muse/tools/AgentConcurrencyLimiter.kt").readText()
    }

    private val runner: String by lazy {
        File("src/main/java/io/zer0/muse/tools/SubagentRunner.kt").readText()
    }

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `limiter reserves slots for foreground work`() {
        assertTrue(limiter.contains("reservedForForeground"));
        assertTrue(
            "后台路径必须存在，否则预留无从生效",
            limiter.contains("suspend fun <T> runAsBackground("),
        )
    }

    @Test
    fun `background cap excludes the reserved slot`() {
        assertTrue(
            "后台最多占到 上限-预留，剩下永远留给前台",
            limiter.contains("maxConcurrent - reservedForForeground"),
        )
    }

    @Test
    fun `background path releases its own counter`() {
        assertTrue(limiter.contains("backgroundActive.decrementAndGet()"));
    }

    @Test
    fun `runner defaults to foreground so old callers are unchanged`() {
        assertTrue(
            "默认 false：旧 subagent_run / subagent_task 行为不变（计划 V25）",
            runner.contains("val background: Boolean = false"),
        )
    }

    @Test
    fun `runner dispatches to the background path only when asked`() {
        assertTrue(runner.contains("if (params.background)"));
        assertTrue(runner.contains("concurrencyLimiter.runAsBackground(work)"));
    }

    @Test
    fun `central scheduler marks its runs as background`() {
        assertTrue(
            "中枢后台任务必须走预留路径，否则用户发消息会被后台挡住",
            scheduler.contains("background = true"),
        )
    }

    @Test
    fun `reserved slot is exactly one so background still makes progress`() {
        assertTrue(limiter.contains("reservedForForeground: Int = 1"));
    }
}
